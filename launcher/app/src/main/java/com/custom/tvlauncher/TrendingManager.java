package com.custom.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zero-dependency TMDB "Trending Today" fetcher for the home screen Now Showing
 * hero.
 * - One list request + up to {@link #MAX_ITEMS} backdrop downloads (w1280,
 * ~150-250 KB each) per refresh.
 * - List is cached in SharedPreferences for {@link #CACHE_TTL_MS}; backdrops
 * are cached on disk so
 * rotation never touches the network. Falls back to the stale cache when
 * offline.
 * - Watch-provider / IMDb / trailer details are fetched lazily, only when
 * "Watch Now" is pressed.
 */
public class TrendingManager {

    public static class Item {
        public int id;
        public String mediaType; // "movie" or "tv"
        public String title;
        public String overview;
        public String year;
        public double rating;
        public String backdropPath;
        public File backdropFile;

        public String typeLabel() {
            return "tv".equals(mediaType) ? "Series" : "Movie";
        }
    }

    public static class Details {
        public String imdbId;
        public String trailerKey;
        public final List<Integer> providerIds = new ArrayList<>();
    }

    public interface ListCallback {
        void onItems(List<Item> items);
    }

    public interface DetailsCallback {
        void onDetails(Details details);
    }

    public static final int MAX_ITEMS = 10;
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private static final String PREFS_NAME = "now_showing_cache";
    private static final String KEY_JSON = "items";
    private static final String KEY_TIME = "time";
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w1280";

    private final Context context;
    private final SharedPreferences prefs;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean fetching;

    public TrendingManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        dir = new File(this.context.getCacheDir(), "now_showing");
    }

    public static boolean hasApiKey() {
        return !TextUtils.isEmpty(BuildConfig.TMDB_API_KEY);
    }

    /**
     * Delivers cached items immediately (if any), then refreshes in the background
     * when stale.
     */
    public void load(ListCallback callback) {
        List<Item> cached = readCache();
        if (!cached.isEmpty())
            callback.onItems(cached);
        refresh(callback, cached.isEmpty());
    }

    /**
     * Background refresh only when the 6 h cache has expired; cheap to call on
     * every resume.
     */
    public void refreshIfStale(ListCallback callback) {
        refresh(callback, false);
    }

    private void refresh(ListCallback callback, boolean force) {
        boolean fresh = System.currentTimeMillis() - prefs.getLong(KEY_TIME, 0) < CACHE_TTL_MS;
        if ((fresh && !force) || fetching || !hasApiKey())
            return;
        fetching = true;
        executor.execute(() -> {
            List<Item> items = fetchTrending();
            main.post(() -> {
                fetching = false;
                if (!items.isEmpty())
                    callback.onItems(items);
            });
        });
    }

    public void details(Item item, DetailsCallback callback) {
        executor.execute(() -> {
            Details d = new Details();
            try {
                String region = Locale.getDefault().getCountry();
                if (TextUtils.isEmpty(region))
                    region = "US";
                JSONObject root = new JSONObject(get(API + "/" + item.mediaType + "/" + item.id
                        + "?append_to_response=external_ids,watch/providers,videos"));
                JSONObject ext = root.optJSONObject("external_ids");
                if (ext != null)
                    d.imdbId = emptyToNull(ext.optString("imdb_id"));
                if (d.imdbId == null)
                    d.imdbId = emptyToNull(root.optString("imdb_id"));
                JSONObject wp = root.optJSONObject("watch/providers");
                JSONObject results = wp == null ? null : wp.optJSONObject("results");
                JSONObject local = results == null ? null : results.optJSONObject(region);
                if (local == null && results != null)
                    local = results.optJSONObject("US");
                JSONArray flat = local == null ? null : local.optJSONArray("flatrate");
                if (flat != null)
                    for (int i = 0; i < flat.length(); i++) {
                        d.providerIds.add(flat.getJSONObject(i).optInt("provider_id"));
                    }
                JSONObject videos = root.optJSONObject("videos");
                JSONArray vr = videos == null ? null : videos.optJSONArray("results");
                if (vr != null)
                    for (int i = 0; i < vr.length(); i++) {
                        JSONObject v = vr.getJSONObject(i);
                        if ("YouTube".equals(v.optString("site")) && "Trailer".equals(v.optString("type"))) {
                            d.trailerKey = v.optString("key");
                            break;
                        }
                    }
            } catch (Exception ignored) {
            }
            main.post(() -> callback.onDetails(d));
        });
    }

    // ------------------------------------------------------------------ network

    private List<Item> fetchTrending() {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(get(API + "/trending/all/day?language=en-US"))
                    .getJSONArray("results");
            if (!dir.exists())
                dir.mkdirs();
            for (int i = 0; i < results.length() && items.size() < MAX_ITEMS; i++) {
                JSONObject o = results.getJSONObject(i);
                String type = o.optString("media_type");
                String backdrop = emptyToNull(o.optString("backdrop_path"));
                if (backdrop == null || !("movie".equals(type) || "tv".equals(type)))
                    continue;
                Item item = new Item();
                item.id = o.getInt("id");
                item.mediaType = type;
                item.title = o.optString("movie".equals(type) ? "title" : "name");
                item.overview = o.optString("overview");
                String date = o.optString("movie".equals(type) ? "release_date" : "first_air_date");
                item.year = date.length() >= 4 ? date.substring(0, 4) : "";
                item.rating = o.optDouble("vote_average", 0);
                item.backdropPath = backdrop;
                item.backdropFile = new File(dir, type + "_" + item.id + ".jpg");
                if (!item.backdropFile.exists() && !download(IMAGE_BASE + backdrop, item.backdropFile))
                    continue;
                items.add(item);
            }
            if (!items.isEmpty()) {
                pruneFiles(items);
                writeCache(items);
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    private String get(String url) throws Exception {
        String key = BuildConfig.TMDB_API_KEY;
        boolean bearer = key.startsWith("eyJ"); // v4 read access token (JWT)
        if (!bearer)
            url += (url.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer)
            c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if (c.getResponseCode() != 200)
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private boolean download(String url, File target) {
        File tmp = new File(target.getPath() + ".part");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200)
                return false;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
            }
            return tmp.renameTo(target);
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            if (c != null)
                c.disconnect();
        }
    }

    // ------------------------------------------------------------------ cache

    private void pruneFiles(List<Item> keep) {
        Set<String> names = new HashSet<>();
        for (Item item : keep)
            names.add(item.backdropFile.getName());
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files)
                if (!names.contains(f.getName()))
                    f.delete();
    }

    private void writeCache(List<Item> items) {
        try {
            JSONArray arr = new JSONArray();
            for (Item item : items) {
                arr.put(new JSONObject()
                        .put("id", item.id).put("type", item.mediaType).put("title", item.title)
                        .put("overview", item.overview).put("year", item.year)
                        .put("rating", item.rating).put("backdrop", item.backdropPath));
            }
            prefs.edit().putString(KEY_JSON, arr.toString()).putLong(KEY_TIME, System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    private List<Item> readCache() {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_JSON, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item item = new Item();
                item.id = o.getInt("id");
                item.mediaType = o.getString("type");
                item.title = o.optString("title");
                item.overview = o.optString("overview");
                item.year = o.optString("year");
                item.rating = o.optDouble("rating", 0);
                item.backdropPath = o.optString("backdrop");
                item.backdropFile = new File(dir, item.mediaType + "_" + item.id + ".jpg");
                if (item.backdropFile.exists())
                    items.add(item);
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }
}
