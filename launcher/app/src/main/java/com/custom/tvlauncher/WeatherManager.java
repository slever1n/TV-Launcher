package com.custom.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight, zero-dependency WeatherManager for TV Launcher.
 * Automatically geolocates via public IP and queries Open-Meteo for live weather.
 * Caches results in SharedPreferences for 2 hours and falls back seamlessly when offline.
 */
public class WeatherManager {

    public interface Callback {
        void onWeatherUpdated(String weatherText);
    }

    private static final String PREFS_NAME = "weather_cache";
    private static final String KEY_TEXT = "cached_weather";
    private static final String KEY_TIME = "cached_time";
    private static final long CACHE_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Callback callback;

    public WeatherManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public String getCachedWeather() {
        return prefs.getString(KEY_TEXT, "");
    }

    public void fetchWeather() {
        long lastFetch = prefs.getLong(KEY_TIME, 0);
        long now = System.currentTimeMillis();
        String cached = prefs.getString(KEY_TEXT, "");

        if (!cached.isEmpty() && (now - lastFetch < CACHE_TTL_MS)) {
            if (callback != null) callback.onWeatherUpdated(cached);
            return;
        }

        executor.execute(() -> {
            String result = queryWeather();
            if (result != null && !result.trim().isEmpty()) {
                prefs.edit().putString(KEY_TEXT, result).putLong(KEY_TIME, System.currentTimeMillis()).apply();
                mainHandler.post(() -> {
                    if (callback != null) callback.onWeatherUpdated(result);
                });
            } else if (!cached.isEmpty()) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onWeatherUpdated(cached);
                });
            }
        });
    }

    private String queryWeather() {
        try {
            // Step 1: Geolocation via free IP API
            double lat = 14.5995; // default fallback (Manila / SE Asia)
            double lon = 120.9842;
            try {
                URL geoUrl = new URL("https://get.geojs.io/v1/ip/geo.json");
                HttpURLConnection geoConn = (HttpURLConnection) geoUrl.openConnection();
                geoConn.setConnectTimeout(4000);
                geoConn.setReadTimeout(4000);
                if (geoConn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(geoConn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONObject geo = new JSONObject(sb.toString());
                    lat = geo.optDouble("latitude", lat);
                    lon = geo.optDouble("longitude", lon);
                }
                geoConn.disconnect();
            } catch (Exception ignored) { }

            // Step 2: Open-Meteo Current Weather
            URL weatherUrl = new URL(String.format(java.util.Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                    lat, lon));
            HttpURLConnection wConn = (HttpURLConnection) weatherUrl.openConnection();
            wConn.setConnectTimeout(4000);
            wConn.setReadTimeout(4000);
            if (wConn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(wConn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                JSONObject root = new JSONObject(sb.toString());
                JSONObject current = root.getJSONObject("current");
                double temp = current.getDouble("temperature_2m");
                int code = current.getInt("weather_code");
                String symbol = getWeatherSymbol(code);
                return symbol + " " + Math.round(temp) + "°";
            }
            wConn.disconnect();
        } catch (Exception ignored) { }
        return null;
    }

    private static String getWeatherSymbol(int code) {
        // WMO Weather interpretation codes (WW)
        if (code == 0) return "☀️"; // Clear sky
        if (code == 1 || code == 2) return "🌤️"; // Mainly clear / partly cloudy
        if (code == 3) return "☁️"; // Overcast
        if (code == 45 || code == 48) return "🌫️"; // Fog
        if (code >= 51 && code <= 55) return "🌦️"; // Drizzle
        if (code >= 61 && code <= 65) return "🌧️"; // Rain
        if (code >= 71 && code <= 77) return "❄️"; // Snow
        if (code >= 80 && code <= 82) return "🌧️"; // Rain showers
        if (code >= 95 && code <= 99) return "🌩️"; // Thunderstorm
        return "⛅";
    }
}
