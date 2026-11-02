package com.custom.tvlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen "Now Showing" hero: rotating TMDB trending backdrops that replace the static wallpaper.
 *
 * Performance budget (low-RAM MediaTek TV):
 * - Discrete crossfades only (no continuous animation), so the GPU is idle between slides.
 * - Backdrops decoded off the UI thread as RGB_565 (~1.8 MB at 1280x720); at most 2 held at once.
 * - Rotation pauses whenever Home is not visible (other app, lock screen, drawer, dialog, focus on button).
 * - Network is only used by {@link TrendingManager} every 6 hours; rotation reads from disk cache.
 */
public class NowShowing {

    public interface Listener {
        /** Called when the hero starts/stops covering the screen, so the static wallpaper can be hidden. */
        void onBackdropVisible(boolean visible);
        /** Called when the Watch Now button gains focus. */
        void onWatchFocused(String hint);
    }

    private static final long SLIDE_MS = 9000;
    private static final int FADE_MS = 700;
    private static final int WHITE = Color.rgb(245, 246, 251);
    private static final int DARK = Color.rgb(18, 24, 38);

    /** TMDB provider id -> Android TV package. Matching installed apps are preferred for Watch Now. */
    private static final Map<Integer, String> PROVIDERS = new LinkedHashMap<>();
    static {
        PROVIDERS.put(8, "com.netflix.ninja");                         // Netflix
        PROVIDERS.put(337, "com.disney.disneyplus");                   // Disney+
        PROVIDERS.put(350, "com.apple.atve.androidtv.appletv");        // Apple TV+
        PROVIDERS.put(119, "com.amazon.amazonvideo.livingroom");       // Prime Video
        PROVIDERS.put(9, "com.amazon.amazonvideo.livingroom");         // Prime Video (alt id)
        PROVIDERS.put(1899, "com.wbd.stream");                         // Max
    }
    private static final String STREMIO = "com.stremio.one";
    private static final String YOUTUBE = "com.google.android.youtube.tv";

    private final Activity activity;
    private final TrendingManager manager;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();

    private final FrameLayout backdropLayer;
    private final ImageView[] images = new ImageView[2];
    private final View scrim;
    private int front = 0;
    private final LinearLayout info;
    private final TextView eyebrow, title, meta, overview, watch;
    private final LinearLayout dots;

    private final List<TrendingManager.Item> items = new ArrayList<>();
    private int index = -1;
    private int generation = 0;          // invalidates in-flight decodes after disable/skip
    private boolean enabled, running, infoAllowed = true, backdropShown;
    private volatile Bitmap blurred;

    private final Runnable advance = new Runnable() {
        @Override public void run() { show(index + 1, true); }
    };

    public NowShowing(Activity activity, TrendingManager manager, Listener listener) {
        this.activity = activity;
        this.manager = manager;
        this.listener = listener;

        // ---- Backdrop layer (sits where the wallpaper ImageView is)
        backdropLayer = new FrameLayout(activity);
        backdropLayer.setVisibility(View.GONE);
        backdropLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(activity);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            images[i] = iv;
            backdropLayer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        // Left-side scrim keeps the title legible over bright posters
        scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xe6050810, 0x99050810, 0x10050810}));
        backdropLayer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // ---- Info block
        info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setClipChildren(false);
        info.setClipToPadding(false);
        info.setVisibility(View.GONE);

        eyebrow = text(10, 0xffd6d8de, "sans-serif-medium");
        eyebrow.setLetterSpacing(.16f);
        info.addView(eyebrow);

        title = text(30, WHITE, "sans-serif-medium");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(10), 0, dp(2), 0x99000000);
        info.addView(title, margins(-1, 6));

        meta = text(12, 0xffc4c9d4, "sans-serif");
        meta.setLetterSpacing(.04f);
        info.addView(meta, margins(-2, 6));

        overview = text(12, 0xd9ffffff, "sans-serif");
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(dp(3), 1f);
        info.addView(overview, margins(dp(470), 9));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);

        watch = text(12, Color.WHITE, "sans-serif-medium");
        watch.setText("\u25B6\uFE0E  Watch Now");
        watch.setGravity(Gravity.CENTER);
        watch.setPadding(dp(18), 0, dp(20), 0);
        watch.setId(View.generateViewId());
        watch.setFocusable(true);
        watch.setClickable(true);
        watch.setContentDescription("Watch now");
        watch.setBackground(pill(0x38ffffff, 1, 0x40ffffff));
        watch.setOnClickListener(v -> watchCurrent());
        watch.setOnFocusChangeListener((v, focused) -> {
            watch.setBackground(pill(focused ? WHITE : 0x38ffffff, focused ? 0 : 1, 0x40ffffff));
            watch.setTextColor(focused ? DARK : Color.WHITE);
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .translationZ(focused ? dp(8) : 0).setDuration(140).start();
            if (focused) {
                handler.removeCallbacks(advance);   // hold the slide while the user is reading it
                listener.onWatchFocused("OK  Watch now     ·     \u25C0 \u25B6  Browse trending     ·     \u2193  Dock");
            } else scheduleNext();
        });
        watch.setPivotX(0);
        actions.addView(watch, new LinearLayout.LayoutParams(-2, dp(32)));

        dots = new LinearLayout(activity);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-2, -2);
        dotsLp.leftMargin = dp(18);
        actions.addView(dots, dotsLp);

        info.addView(actions, margins(-2, 14));
    }

    // ------------------------------------------------------------------ public API

    public View backdropLayer() { return backdropLayer; }
    public View infoBlock() { return info; }
    public boolean isEnabled() { return enabled; }
    public boolean isShowing() { return enabled && backdropShown; }
    /** Small pre-blurred copy of the current backdrop for frosted dialogs. */
    public Bitmap blurred() { return isShowing() ? blurred : null; }

    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        generation++;
        if (on) {
            backdropLayer.setVisibility(View.VISIBLE);
            manager.load(this::onItems);
        } else {
            handler.removeCallbacks(advance);
            boolean hadFocus = watch.hasFocus();
            items.clear();
            index = -1;
            for (ImageView iv : images) { iv.animate().cancel(); iv.setImageDrawable(null); iv.setAlpha(0f); }
            blurred = null;
            backdropLayer.setVisibility(View.GONE);
            info.setVisibility(View.GONE);
            setBackdropShown(false);
            if (hadFocus) listener.onWatchFocused(null);
        }
    }

    /** Resume/pause rotation; call from onResume/onPause and when overlays open or close. */
    public void setRunning(boolean run) {
        running = run;
        if (!enabled) return;
        if (run) {
            if (items.isEmpty()) manager.load(this::onItems);
            else manager.refreshIfStale(this::onItems);
            scheduleNext();
        } else handler.removeCallbacks(advance);
    }

    /** Hide the text block (e.g. while the Now Playing media panel or app drawer is on screen). */
    public void setInfoAllowed(boolean allowed) {
        infoAllowed = allowed;
        updateInfoVisibility();
    }

    public boolean canFocus() { return info.getVisibility() == View.VISIBLE && watch.isShown(); }
    public boolean focus() { return canFocus() && watch.requestFocus(); }
    public boolean hasFocus(View v) { return v == watch; }

    /** D-pad handling while Watch Now is focused. Returns true when consumed. */
    public boolean handleKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { show(index - 1, true); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { show(index + 1, true); return true; }
        return false;
    }

    // ------------------------------------------------------------------ slides

    private void onItems(List<TrendingManager.Item> fresh) {
        if (!enabled || fresh.isEmpty()) return;
        String currentId = index >= 0 && index < items.size() ? key(items.get(index)) : null;
        items.clear();
        items.addAll(fresh);
        rebuildDots();
        int keep = -1;
        if (currentId != null) for (int i = 0; i < items.size(); i++) if (key(items.get(i)).equals(currentId)) keep = i;
        if (keep >= 0) { index = keep; updateDots(); }
        else show(0, backdropShown);
    }

    private void show(int target, boolean animate) {
        if (!enabled || items.isEmpty()) return;
        handler.removeCallbacks(advance);
        int next = Math.floorMod(target, items.size());
        TrendingManager.Item item = items.get(next);
        int gen = ++generation;
        int screenW = Math.max(1, activity.getResources().getDisplayMetrics().widthPixels);
        decoder.execute(() -> {
            Bitmap bmp = decode(item, screenW);
            Bitmap blur = bmp == null ? null : FastBlur.blur(bmp, 0.12f, 10);
            handler.post(() -> {
                if (gen != generation || !enabled) return;
                if (bmp == null) {             // corrupt cache file: skip it
                    items.remove(item);
                    rebuildDots();
                    if (!items.isEmpty()) show(next, animate);
                    return;
                }
                index = next;
                blurred = blur;
                present(item, bmp, animate);
            });
        });
    }

    private void present(TrendingManager.Item item, Bitmap bmp, boolean animate) {
        ImageView out = images[front];
        ImageView in = images[1 - front];
        front = 1 - front;
        in.animate().cancel();
        out.animate().cancel();
        in.setImageBitmap(bmp);
        in.bringToFront();
        scrim.bringToFront(); // keep scrim above both backdrops
        Runnable dropOld = () -> { out.setImageDrawable(null); out.setAlpha(0f); };
        if (animate) {
            in.setAlpha(0f);
            in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(dropOld).start();
            info.animate().cancel();
            info.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                bind(item);
                info.animate().alpha(1f).setDuration(320).start();
            }).start();
        } else {
            in.setAlpha(1f);
            dropOld.run();
            bind(item);
            info.setAlpha(1f);
        }
        setBackdropShown(true);
        updateInfoVisibility();
        scheduleNext();
    }

    private void bind(TrendingManager.Item item) {
        eyebrow.setText(String.format(Locale.US, "TRENDING TODAY  \u00B7  #%d  \u00B7  %s",
                index + 1, item.typeLabel().toUpperCase(Locale.US)));
        title.setText(item.title);
        StringBuilder m = new StringBuilder();
        if (item.rating > 0) m.append("\u2605 ").append(String.format(Locale.US, "%.1f", item.rating));
        if (!TextUtils.isEmpty(item.year)) m.append(m.length() > 0 ? "    " : "").append(item.year);
        m.append(m.length() > 0 ? "    " : "").append(item.typeLabel());
        meta.setText(m);
        overview.setText(item.overview);
        overview.setVisibility(TextUtils.isEmpty(item.overview) ? View.GONE : View.VISIBLE);
        updateDots();
    }

    private void scheduleNext() {
        handler.removeCallbacks(advance);
        if (enabled && running && items.size() > 1 && !watch.hasFocus()) handler.postDelayed(advance, SLIDE_MS);
    }

    private void setBackdropShown(boolean shown) {
        if (backdropShown == shown) return;
        backdropShown = shown;
        listener.onBackdropVisible(shown);
    }

    private void updateInfoVisibility() {
        boolean show = enabled && infoAllowed && backdropShown && index >= 0;
        boolean hadFocus = watch.hasFocus();
        info.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show && hadFocus) listener.onWatchFocused(null);
    }

    private static Bitmap decode(TrendingManager.Item item, int screenW) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(item.backdropFile.getPath(), bounds);
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= screenW) opts.inSampleSize *= 2;
            return BitmapFactory.decodeFile(item.backdropFile.getPath(), opts);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ dots

    private void rebuildDots() {
        dots.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            View d = new View(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            if (i > 0) lp.leftMargin = dp(6);
            dots.addView(d, lp);
        }
        updateDots();
    }

    private void updateDots() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            View d = dots.getChildAt(i);
            boolean active = i == index;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
            lp.width = dp(active ? 18 : 6);
            d.setLayoutParams(lp);
            d.setBackground(pill(active ? Color.WHITE : 0x55ffffff, 0, 0));
        }
    }

    // ------------------------------------------------------------------ Watch Now

    private void watchCurrent() {
        if (index < 0 || index >= items.size()) return;
        TrendingManager.Item item = items.get(index);
        watch.setText("Finding\u2026");
        manager.details(item, d -> {
            watch.setText("\u25B6\uFE0E  Watch Now");
            route(item, d);
        });
    }

    /** Installed subscription app that carries it -> Stremio (IMDb) -> YouTube trailer. */
    private void route(TrendingManager.Item item, TrendingManager.Details d) {
        for (int providerId : d.providerIds) {
            String pkg = PROVIDERS.get(providerId);
            if (pkg != null && launchPackage(pkg)) {
                Toast.makeText(activity, "Search for \u201C" + item.title + "\u201D", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (d.imdbId != null && isInstalled(STREMIO)) {
            String type = "tv".equals(item.mediaType) ? "series" : "movie";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/" + type + "/" + d.imdbId))
                    .setPackage(STREMIO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(i)) return;
        }
        if (d.trailerKey != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + d.trailerKey))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (isInstalled(YOUTUBE)) i.setPackage(YOUTUBE);
            if (start(i)) {
                Toast.makeText(activity, "Playing trailer for \u201C" + item.title + "\u201D", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (isInstalled(STREMIO) && launchPackage(STREMIO)) return;
        Toast.makeText(activity, "No app found for \u201C" + item.title + "\u201D", Toast.LENGTH_LONG).show();
    }

    private boolean isInstalled(String pkg) {
        return activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null
                || activity.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    private boolean launchPackage(String pkg) {
        Intent intent = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        return intent != null && start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean start(Intent intent) {
        try { activity.startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException e) { return false; }
    }

    // ------------------------------------------------------------------ helpers

    private static String key(TrendingManager.Item item) { return item.mediaType + item.id; }

    private TextView text(int sp, int color, String family) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams margins(int width, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private GradientDrawable pill(int color, int stroke, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(100));
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor);
        return g;
    }

    private int dp(float v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
}
