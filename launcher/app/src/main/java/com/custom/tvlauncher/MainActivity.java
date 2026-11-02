package com.custom.tvlauncher;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Remote-first TV home screen.
 * Features 3 top-right icon buttons: Control Center, Network, and Settings.
 * Supports 12 preset wallpapers, custom Google Photos import, and local Wi-Fi uploads.
 */
public class MainActivity extends Activity {
    private static final int WHITE = Color.rgb(245, 246, 251);
    private static final int MUTED = Color.rgb(151, 160, 181);
    private static final int COLUMNS = 5;
    private static final int REQUEST_CODE_PICK_IMAGE = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>();
    private final List<String> favorites = new ArrayList<>();
    private final List<View> favoriteViews = new ArrayList<>();
    private final List<View> libraryViews = new ArrayList<>();
    private final List<View> shortcuts = new ArrayList<>();

    private SharedPreferences preferences;
    private FrameLayout root;
    private ImageView wallpaper;
    private LinearLayout content, dockSection, dockRow;
    private FrameLayout drawer;
    private View drawerShade;
    private TextView selectedName;
    private MediaPanel mediaPanel;
    private boolean drawerOpen;
    private int lastDockIndex;
    private ScrollView scroll;
    private TextView clock, greeting, date, hint;
    private String focusKey = "";
    private String appSignature;
    private int tileWidth;

    // Control Center, Lock Screen, and Server
    private static final int LOCK_DOCK_MAX = 10;
    private static final String EXTRA_BLACKOUT = "com.custom.tvlauncher.BLACKOUT";
    private WallpaperServer wallpaperServer;
    private FrameLayout lockScreenOverlay, lockBlackoutView;
    private ImageView lockScreenWallpaper;
    private GlassClockView lockScreenClock;
    private TextView lockScreenDate, lockScreenHint, lockBlackoutHint;
    private LinearLayout lockDockRow;
    private final List<String> lockDock = new ArrayList<>();
    private final List<View> lockDockViews = new ArrayList<>();
    private int lockDockIndex;
    private boolean lockScreenActive, lockBlackout, lockSelectArmed, resumed;
    private ObjectAnimator kenBurns;
    private long sleepTimerEndTimeMs = 0;
    private int sleepTimerMinutes = 0;
    private int lockTimeoutMinutes = 5;
    private int standbyMinutes = 60;
    private long lastInteractionTimeMs = 0;
    private TextView weatherChip;
    private TextView sleepBadge;
    private WeatherManager weatherManager;
    private Bitmap blurredWallpaperBitmap;
    private final java.util.concurrent.ExecutorService blurExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final String PREF_NOW_SHOWING = "now_showing_enabled";
    private NowShowing nowShowing;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateClock();
            updateSleepBadge();
            handler.postDelayed(this, 1000L * (60 - Calendar.getInstance().get(Calendar.SECOND)));
        }
    };

    /** Fires after the Home screen has been idle for the Auto-Lock time. */
    private final Runnable idleLock = () -> {
        if (!lockScreenActive && lockTimeoutMinutes > 0 && resumed && hasWindowFocus()) showLockScreen(false);
    };
    /** Fires after the lock screen has been showing for the Standby time. */
    private final Runnable standbyRunnable = this::goToStandby;
    /** Sleep timer keeps running while other apps are in front. */
    private final Runnable sleepRunnable = () -> {
        sleepTimerMinutes = 0;
        sleepTimerEndTimeMs = 0;
        goToStandby();
    };
    private final Runnable hideLockHint = () -> lockScreenHint.animate().alpha(0f).setDuration(600).start();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("home", MODE_PRIVATE);
        lockTimeoutMinutes = preferences.getInt("lock_timeout_minutes", 5);
        standbyMinutes = preferences.getInt("standby_minutes", 60);
        lastInteractionTimeMs = SystemClock.elapsedRealtime();
        if (state != null) focusKey = state.getString("focus", "");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();
        buildShell();

        // Start local Wi-Fi wallpaper server
        wallpaperServer = new WallpaperServer(this);
        wallpaperServer.setListener(new WallpaperServer.Listener() {
            @Override public void onWallpaperUploaded(File file, String name) {
                preferences.edit()
                        .putBoolean("wallpaper_is_custom", true)
                        .putString("wallpaper_custom_path", file.getAbsolutePath())
                        .apply();
                applyCurrentWallpaper();
                Toast.makeText(MainActivity.this, "✓ Wallpaper applied from phone/PC!", Toast.LENGTH_LONG).show();
            }

            @Override public void onWallpaperDeleted(String name) {
                if (WallpaperPicker.isCustomSelected(preferences) &&
                        preferences.getString("wallpaper_custom_path", "").endsWith(name)) {
                    preferences.edit().putBoolean("wallpaper_is_custom", false).apply();
                    applyCurrentWallpaper();
                }
            }

            @Override public void onWallpaperSelected(File file) {
                preferences.edit()
                        .putBoolean("wallpaper_is_custom", true)
                        .putString("wallpaper_custom_path", file.getAbsolutePath())
                        .apply();
                applyCurrentWallpaper();
            }
        });
        wallpaperServer.start();

        // Weather service initialization
        weatherManager = new WeatherManager(this);
        weatherManager.setCallback(text -> {
            if (weatherChip != null && text != null && !text.isEmpty()) {
                weatherChip.setText(text);
                if (weatherChip.getVisibility() != View.VISIBLE) {
                    weatherChip.setVisibility(View.VISIBLE);
                    weatherChip.setAlpha(0f);
                    weatherChip.animate().alpha(1f).setDuration(300).start();
                }
            }
        });
        weatherManager.fetchWeather();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (wallpaperServer != null) {
            wallpaperServer.stop();
        }
        super.onDestroy();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            immersive();
            noteInteraction();
        } else {
            updateKeepScreenOn();
        }
        updateNowShowingState();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        try {
            Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 0);
        } catch (Exception ignored) { }
        refreshApps();
        handler.removeCallbacks(ticker);
        ticker.run();
        updateSleepBadge();
        if (weatherManager != null) weatherManager.fetchWeather();
        mediaPanel.start();
        noteInteraction();
        updateNowShowingState();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(idleLock);
        handler.removeCallbacks(standbyRunnable);
        mediaPanel.stop();
        updateKeepScreenOn();
        updateNowShowingState();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("focus", focusKey);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_BLACKOUT, false)) {
            showLockScreen(true);
            return;
        }
        if (lockScreenActive) dismissLockScreen(false);
        if (Intent.ACTION_MAIN.equals(intent.getAction())) home();
    }

    @Override public void onBackPressed() {
        if (lockScreenActive) {
            dismissLockScreen(true);
            return;
        }
        if (drawerOpen) closeDrawer(lastDockIndex);
        else home();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (lockScreenActive) return dispatchLockKey(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Reset idle timer on any key press
            noteInteraction();

            View focused = getCurrentFocus();
            int key = event.getKeyCode();
            if (!drawerOpen && nowShowing.hasFocus(focused)) {
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusDock(lastDockIndex); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (!shortcuts.isEmpty()) shortcuts.get(0).requestFocus();
                    return true;
                }
                if (nowShowing.handleKey(key)) return true;
            }
            if (!drawerOpen && shortcuts.contains(focused) && key == KeyEvent.KEYCODE_DPAD_DOWN
                    && !mediaPanel.isShown() && nowShowing.focus()) {
                return true;
            }
            int dockIndex = favoriteViews.indexOf(focused);
            if (!drawerOpen && dockIndex >= 0) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                    openDrawer(dockIndex);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                    if (!mediaPanel.focusPrimary() && !nowShowing.focus()) {
                        if (!shortcuts.isEmpty()) shortcuts.get(0).requestFocus();
                    }
                    return true;
                }
            }
            int appIndex = libraryViews.indexOf(focused);
            if (drawerOpen && appIndex >= 0 && appIndex < COLUMNS
                    && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                closeDrawer(Math.min(appIndex, Math.max(0, favoriteViews.size() - 1)));
                return true;
            }
            if (!drawerOpen && mediaPanel.containsFocus(focused)) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusDock(lastDockIndex);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                    if (!shortcuts.isEmpty()) shortcuts.get(0).requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void home() { closeDrawer(0); }

    private void focusDock(int index) {
        if (!favoriteViews.isEmpty()) {
            lastDockIndex = Math.min(Math.max(0, index), favoriteViews.size() - 1);
            favoriteViews.get(lastDockIndex).requestFocus();
        } else if (dockRow.getChildCount() > 0) {
            dockRow.getChildAt(0).requestFocus();
        } else if (!shortcuts.isEmpty()) {
            shortcuts.get(0).requestFocus();
        }
    }

    private void openDrawer(int column) {
        if (drawerOpen) return;
        drawerOpen = true;
        lastDockIndex = column;
        dockSection.setVisibility(View.GONE);
        mediaPanel.setHomeVisible(false);
        drawerShade.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0);
        drawer.setTranslationY(dp(24));
        drawer.animate().alpha(1).translationY(0).setDuration(220).start();
        hint.setText(R.string.drawer_hint);
        updateNowShowingState();
        scroll.scrollTo(0, 0);
        wireNavigation();
        if (!libraryViews.isEmpty()) {
            View target = libraryViews.get(Math.min(column, libraryViews.size() - 1));
            target.post(() -> target.requestFocus());
        }
    }

    private void closeDrawer(int column) {
        drawerOpen = false;
        drawer.setVisibility(View.GONE);
        drawerShade.setVisibility(View.GONE);
        dockSection.setVisibility(View.VISIBLE);
        mediaPanel.setHomeVisible(true);
        hint.setText(R.string.dock_hint);
        updateNowShowingState();
        wireNavigation();
        focusDock(column);
    }

    private void buildShell() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // Wallpaper
        wallpaper = new ImageView(this);
        applyCurrentWallpaper();
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        // Now Showing hero (TMDB trending) sits on top of the wallpaper and hides it once a backdrop is up.
        nowShowing = new NowShowing(this, new TrendingManager(this), new NowShowing.Listener() {
            @Override public void onBackdropVisible(boolean visible) {
                wallpaper.setVisibility(visible ? View.GONE : View.VISIBLE);
            }

            @Override public void onWatchFocused(String hintText) {
                if (hintText != null) {
                    focusKey = "nowshowing";
                    hint.setText(hintText);
                } else if (!drawerOpen && !lockScreenActive) {
                    focusDock(lastDockIndex);
                }
            }
        });
        root.addView(nowShowing.backdropLayer(), new FrameLayout.LayoutParams(-1, -1));

        // Vignette
        View vignette = new View(this);
        vignette.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99090f19, 0x160a111a, 0x95060b14}));
        root.addView(vignette, new FrameLayout.LayoutParams(-1, -1));

        // Drawer shade
        drawerShade = new View(this);
        drawerShade.setBackgroundColor(0x9c080e19);
        drawerShade.setVisibility(View.GONE);
        root.addView(drawerShade, new FrameLayout.LayoutParams(-1, -1));

        // Clock Block (Top Left)
        LinearLayout clockBlock = column();
        greeting = text("", 11, 0xffd6d8de);
        greeting.setLetterSpacing(.13f);
        clockBlock.addView(greeting);

        LinearLayout clockLine = row();
        clockLine.setGravity(Gravity.CENTER_VERTICAL);
        clockLine.setPadding(0, dp(8), 0, 0);
        clock = text("", 45, WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockLine.addView(clock);

        date = text("", 11, 0xffe5e3df);
        date.setLetterSpacing(.13f);
        date.setPadding(dp(22), dp(7), 0, 0);
        clockLine.addView(date);

        // Weather condition & temperature chip
        weatherChip = text("", 11, 0xffe5e3df);
        weatherChip.setLetterSpacing(.10f);
        weatherChip.setPadding(dp(12), dp(4), dp(12), dp(4));
        weatherChip.setBackground(round(0x28ffffff, 12, 1, 0x2affffff));
        weatherChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams weatherLP = new LinearLayout.LayoutParams(-2, -2);
        weatherLP.leftMargin = dp(16);
        clockLine.addView(weatherChip, weatherLP);

        // Sleep Timer active countdown badge
        sleepBadge = text("", 11, Color.WHITE);
        sleepBadge.setLetterSpacing(.10f);
        sleepBadge.setPadding(dp(12), dp(4), dp(12), dp(4));
        sleepBadge.setBackground(round(0x40000000, 12, 1, 0x55ffffff));
        sleepBadge.setVisibility(View.GONE);
        sleepBadge.setFocusable(true);
        sleepBadge.setClickable(true);
        sleepBadge.setOnClickListener(v -> openControlCenter());
        sleepBadge.setOnFocusChangeListener((v, f) -> {
            sleepBadge.setBackground(round(f ? WHITE : 0x40000000, 12, f ? 0 : 1, f ? 0 : 0x55ffffff));
            sleepBadge.setTextColor(f ? Color.rgb(18, 24, 38) : Color.WHITE);
        });
        LinearLayout.LayoutParams sleepLP = new LinearLayout.LayoutParams(-2, -2);
        sleepLP.leftMargin = dp(10);
        clockLine.addView(sleepBadge, sleepLP);

        clockBlock.addView(clockLine);
        FrameLayout.LayoutParams clockLP = new FrameLayout.LayoutParams(-2, -2);
        clockLP.leftMargin = dp(48);
        clockLP.topMargin = dp(31);
        root.addView(clockBlock, clockLP);

        // Header Icons (Top Right): 1. Control Center, 2. Network, 3. Settings
        // 14dp padding ensures the 1.1x focus scale has plenty of room on all sides.
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        controls.setPadding(dp(14), dp(14), dp(14), dp(14));

        addIconButton(controls, R.drawable.ic_control_center, "Control Center",
                "Control Center  ·  Lock Screen, Sleep, Standby, Memory, Inputs, Wallpaper",
                this::openControlCenter);

        addIconButton(controls, R.drawable.ic_network, "Network",
                "Network & Wi-Fi settings",
                () -> openSetting(Settings.ACTION_WIFI_SETTINGS));

        addIconButton(controls, R.drawable.ic_settings, "Settings",
                "TV Settings",
                () -> openSetting(Settings.ACTION_SETTINGS));

        FrameLayout.LayoutParams controlsLP = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        controlsLP.topMargin = dp(52 - 14);
        controlsLP.rightMargin = dp(48 - 14);
        root.addView(controls, controlsLP);

        // Media Panel (Now Playing)
        mediaPanel = new MediaPanel(this);
        mediaPanel.setOnHidden(() -> { if (!drawerOpen) focusDock(lastDockIndex); });
        FrameLayout.LayoutParams mediaLP = new FrameLayout.LayoutParams(dp(510), dp(104));
        mediaLP.topMargin = dp(148);
        mediaLP.leftMargin = dp(48);
        root.addView(mediaPanel, mediaLP);

        // Now Showing title / Watch Now block (same slot as the media panel; yields to it when music plays)
        FrameLayout.LayoutParams heroLP = new FrameLayout.LayoutParams(dp(560), -2);
        heroLP.topMargin = dp(128);
        heroLP.leftMargin = dp(48);
        root.addView(nowShowing.infoBlock(), heroLP);
        mediaPanel.setOnVisibilityChanged(this::updateNowShowingState);
        nowShowing.setEnabled(isNowShowingOn());

        // Dock Section
        dockSection = column();
        dockSection.setClipChildren(false);
        dockSection.setClipToPadding(false);

        selectedName = text("", 27, WHITE);
        selectedName.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        selectedName.setSingleLine(true);
        selectedName.setEllipsize(TextUtils.TruncateAt.END);
        selectedName.setShadowLayer(dp(12), 0, dp(2), Color.BLACK);
        selectedName.setPadding(dp(8), 0, dp(8), dp(17));
        dockSection.addView(selectedName);

        dockRow = row();
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(dp(14), dp(15), dp(14), dp(15));
        dockRow.setBackground(round(0xa02c303b, 23, 1, 0x38ffffff));
        dockSection.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams dockLP = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        dockLP.setMargins(dp(48), 0, dp(48), dp(58));
        root.addView(dockSection, dockLP);

        // Drawer
        drawer = new FrameLayout(this);
        drawer.setBackground(round(0xeb141d2b, 23, 1, 0x28ffffff));
        drawer.setVisibility(View.GONE);

        LinearLayout drawerBody = column();
        drawerBody.setPadding(dp(23), dp(20), dp(23), dp(10));

        LinearLayout drawerHeader = row();
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
        drawerHeader.addView(text("All apps", 23, WHITE), new LinearLayout.LayoutParams(0, -2, 1));
        drawerHeader.addView(text("↑  Back to dock", 11, MUTED));
        drawerBody.addView(drawerHeader);

        scroll = new ScrollView(this);
        scroll.setId(View.generateViewId());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(0, dp(17), 0, dp(8));

        content = column();
        content.setPadding(dp(8), dp(5), dp(8), 0);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        drawerBody.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        drawer.addView(drawerBody, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams drawerLP = new FrameLayout.LayoutParams(-1, -1);
        drawerLP.setMargins(dp(48), dp(128), dp(48), dp(54));
        root.addView(drawer, drawerLP);

        // Hint bar at the bottom
        hint = text("↓  All apps     ·     Hold OK to edit your dock", 11, 0xffe2e4eb);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(dp(5), 0, 1, Color.BLACK);
        hint.setOnClickListener(v -> { if (drawerOpen) closeDrawer(lastDockIndex); else openDrawer(lastDockIndex); });
        FrameLayout.LayoutParams hintLP = new FrameLayout.LayoutParams(-1, dp(28), Gravity.BOTTOM);
        hintLP.bottomMargin = dp(14);
        root.addView(hint, hintLP);

        // Lock Screen Overlay (hidden by default)
        buildLockScreenOverlay();

        setContentView(root);
    }

    private void addIconButton(LinearLayout header, int iconRes, String tag, String hintLabel, Runnable action) {
        FrameLayout button = new FrameLayout(this);
        button.setId(View.generateViewId());
        button.setFocusable(true);
        button.setClickable(true);
        button.setContentDescription(tag);
        button.setTag("shortcut:" + tag);
        button.setBackground(circle(0x55384254, 1, 0x25ffffff));
        button.setClipChildren(false);
        button.setClipToPadding(false);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(1), dp(1), dp(1), dp(1));
        FrameLayout.LayoutParams iconLP = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        button.addView(icon, iconLP);

        button.setOnClickListener(v -> action.run());

        button.setOnFocusChangeListener((v, focused) -> {
            button.setBackground(circle(focused ? WHITE : 0x55384254, focused ? 0 : 1, 0x25ffffff));
            icon.setColorFilter(focused ? Color.rgb(18, 24, 38) : Color.WHITE);
            v.animate().scaleX(focused ? 1.10f : 1).scaleY(focused ? 1.10f : 1)
                    .translationZ(focused ? dp(8) : 0).setDuration(130).start();
            if (focused) {
                focusKey = "shortcut:" + tag;
                hint.setText(hintLabel);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(10);
        header.addView(button, lp);
        shortcuts.add(button);
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(this).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour < 12 ? R.string.greeting_morning
                : hour < 18 ? R.string.greeting_afternoon : R.string.greeting_evening);

        if (lockScreenActive && lockScreenClock != null) updateLockClock();
    }

    private void applyCurrentWallpaper() {
        if (WallpaperPicker.isCustomSelected(preferences)) {
            String path = WallpaperPicker.savedCustomPath(preferences);
            File f = new File(path);
            if (f.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inScaled = false;
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                if (bmp != null) {
                    wallpaper.setImageBitmap(bmp);
                    updateWallpaperBlur(bmp);
                    return;
                }
            }
        }
        // Fall back to preset
        int index = WallpaperPicker.savedIndex(preferences);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap presetBmp = BitmapFactory.decodeResource(
                getResources(), WallpaperPicker.resource(index), options);
        wallpaper.setImageBitmap(presetBmp);
        updateWallpaperBlur(presetBmp);
    }

    private void updateWallpaperBlur(Bitmap bmp) {
        if (bmp == null) return;
        blurExecutor.execute(() -> {
            try {
                Bitmap blurred = FastBlur.blur(bmp, 0.12f, 10);
                handler.post(() -> blurredWallpaperBitmap = blurred);
            } catch (Exception ignored) {}
        });
    }

    private void updateSleepBadge() {
        if (sleepBadge == null) return;
        if (sleepTimerMinutes <= 0 || sleepTimerEndTimeMs <= 0) {
            sleepBadge.setVisibility(View.GONE);
            return;
        }
        long remainingMs = sleepTimerEndTimeMs - SystemClock.elapsedRealtime();
        int remaining = (int) Math.max(1, (remainingMs + 59_999) / 60_000);
        if (remaining <= 0) {
            sleepBadge.setVisibility(View.GONE);
            return;
        }
        String text = "🌙 " + (remaining >= 60
                ? (remaining / 60 + "h" + (remaining % 60 > 0 ? " " + (remaining % 60) + "m" : ""))
                : remaining + "m");
        sleepBadge.setText(text);
        if (sleepBadge.getVisibility() != View.VISIBLE) {
            sleepBadge.setVisibility(View.VISIBLE);
            sleepBadge.setAlpha(0f);
            sleepBadge.animate().alpha(1f).setDuration(240).start();
        }
    }

    private void openControlCenter() {
        Bitmap blurSource = nowShowing.blurred() != null ? nowShowing.blurred() : blurredWallpaperBitmap;
        new ControlCenterDialog(this, blurSource, new ControlCenterDialog.Callback() {
            @Override public boolean isNowShowingEnabled() {
                return preferences.getBoolean(PREF_NOW_SHOWING, true);
            }

            @Override public boolean isNowShowingAvailable() {
                return TrendingManager.hasApiKey();
            }

            @Override public void onSetNowShowing(boolean enabled) {
                preferences.edit().putBoolean(PREF_NOW_SHOWING, enabled).apply();
                nowShowing.setEnabled(isNowShowingOn());
                updateNowShowingState();
            }

            @Override public void onOpenInputs() {
                openInputs();
            }

            @Override public void onOpenWallpaper() {
                openWallpaperPicker();
            }

            @Override public void onLockNow() {
                showLockScreen(false);
            }

            @Override public void onSetLockTimeout(int minutes) {
                lockTimeoutMinutes = minutes;
                preferences.edit().putInt("lock_timeout_minutes", minutes).apply();
                noteInteraction();
            }

            @Override public int getLockTimeoutMinutes() {
                return lockTimeoutMinutes;
            }

            @Override public void onSetStandbyMinutes(int minutes) {
                standbyMinutes = minutes;
                preferences.edit().putInt("standby_minutes", minutes).apply();
                scheduleStandby();
            }

            @Override public int getStandbyMinutes() {
                return standbyMinutes;
            }

            @Override public boolean isStandbyAvailable() {
                return StandbyAdminReceiver.isActive(MainActivity.this);
            }

            @Override public void onSetSleepTimer(int minutes) {
                handler.removeCallbacks(sleepRunnable);
                sleepTimerMinutes = Math.max(0, minutes);
                sleepTimerEndTimeMs = 0;
                if (minutes == -1) {
                    // Sleep Now: let the dialog close first
                    handler.postDelayed(MainActivity.this::goToStandby, 300);
                } else if (minutes > 0) {
                    sleepTimerEndTimeMs = SystemClock.elapsedRealtime() + minutes * 60_000L;
                    handler.postDelayed(sleepRunnable, minutes * 60_000L);
                }
                updateSleepBadge();
            }

            @Override public int getRemainingSleepMinutes() {
                if (sleepTimerMinutes <= 0 || sleepTimerEndTimeMs <= 0) return 0;
                long remainingMs = sleepTimerEndTimeMs - SystemClock.elapsedRealtime();
                return (int) Math.max(1, (remainingMs + 59_999) / 60_000);
            }

            @Override public ControlCenterDialog.MemoryStats getMemoryStats() {
                return calculateMemoryStats();
            }

            @Override public ControlCenterDialog.MemoryStats freeUpMemory() {
                return performMemoryClean();
            }
        }).show();
    }

    private boolean isNowShowingOn() {
        return TrendingManager.hasApiKey() && preferences.getBoolean(PREF_NOW_SHOWING, true);
    }

    /** Rotation only runs while Home is actually visible; the text yields to the drawer and media panel. */
    private void updateNowShowingState() {
        if (nowShowing == null) return;
        nowShowing.setInfoAllowed(!drawerOpen && mediaPanel.getVisibility() != View.VISIBLE);
        nowShowing.setRunning(resumed && !lockScreenActive && !drawerOpen && hasWindowFocus());
    }

    private ControlCenterDialog.MemoryStats calculateMemoryStats() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return null;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        ControlCenterDialog.MemoryStats stats = new ControlCenterDialog.MemoryStats();
        stats.totalBytes = info.totalMem;
        stats.availBytes = info.availMem;
        stats.usedBytes = info.totalMem - info.availMem;
        stats.percentUsed = (int) (stats.usedBytes * 100 / info.totalMem);
        return stats;
    }

    private ControlCenterDialog.MemoryStats performMemoryClean() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return null;

        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);

        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo proc : procs) {
                    if (!proc.processName.equals(getPackageName())) {
                        am.killBackgroundProcesses(proc.processName);
                    }
                }
            }
        } catch (SecurityException ignored) {}

        System.gc();
        Runtime.getRuntime().gc();

        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(after);

        ControlCenterDialog.MemoryStats stats = new ControlCenterDialog.MemoryStats();
        stats.totalBytes = after.totalMem;
        stats.availBytes = after.availMem;
        stats.usedBytes = after.totalMem - after.availMem;
        stats.percentUsed = (int) (stats.usedBytes * 100 / after.totalMem);
        stats.freedBytes = Math.max(0, after.availMem - before.availMem);
        return stats;
    }

    // ------------------------------------------------------------------ Idle, lock & standby timers

    /** Any remote interaction restarts the Auto-Lock countdown (or the Standby countdown when locked). */
    private void noteInteraction() {
        lastInteractionTimeMs = SystemClock.elapsedRealtime();
        if (lockScreenActive) scheduleStandby();
        else scheduleIdleLock();
    }

    private void scheduleIdleLock() {
        handler.removeCallbacks(idleLock);
        if (resumed && !lockScreenActive && lockTimeoutMinutes > 0) {
            handler.postDelayed(idleLock, lockTimeoutMinutes * 60_000L);
        }
        updateKeepScreenOn();
    }

    private void scheduleStandby() {
        handler.removeCallbacks(standbyRunnable);
        if (resumed && lockScreenActive && !lockBlackout && standbyMinutes > 0) {
            handler.postDelayed(standbyRunnable, standbyMinutes * 60_000L);
        }
    }

    /**
     * The native screensaver is disabled, so the launcher owns the screen timeout: it stays awake while
     * the lock screen is showing (until Standby) and while Auto-Lock is counting down on Home.
     */
    private void updateKeepScreenOn() {
        boolean keep = resumed && (lockScreenActive ? !lockBlackout : lockTimeoutMinutes > 0 && hasWindowFocus());
        if (keep) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Real standby through the force-lock device admin; falls back to a black screen. */
    private void goToStandby() {
        handler.removeCallbacks(standbyRunnable);
        if (resumed && !lockScreenActive) showLockScreen(false); // wake up to the lock screen
        if (StandbyAdminReceiver.standby(this)) return;
        if (resumed) {
            showLockScreen(true);
        } else {
            try {
                startActivity(new Intent(this, MainActivity.class).putExtra(EXTRA_BLACKOUT, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
        }
    }

    private void openWallpaperPicker() {
        new WallpaperPicker(this, preferences, new WallpaperPicker.OnWallpaperChosenListener() {
            @Override public void onPresetChosen(int index) {
                applyCurrentWallpaper();
            }

            @Override public void onCustomChosen(File file) {
                applyCurrentWallpaper();
            }

            @Override public void onOpenGooglePhotos() {
                openGooglePhotosPicker();
            }

            @Override public void onOpenWifiUpload() {
                showWifiUploadDialog();
            }
        }).show();
    }

    private void openGooglePhotosPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Choose Wallpaper Image"), REQUEST_CODE_PICK_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No photo picker available on this TV. Use 'Upload from Phone/PC'.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri imageUri = data.getData();
            try {
                InputStream is = getContentResolver().openInputStream(imageUri);
                File dir = new File(getFilesDir(), "custom_wallpapers");
                if (!dir.exists()) dir.mkdirs();

                File dest = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                if (bitmap != null) {
                    FileOutputStream fos = new FileOutputStream(dest);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                    fos.close();
                    bitmap.recycle();

                    preferences.edit()
                            .putBoolean("wallpaper_is_custom", true)
                            .putString("wallpaper_custom_path", dest.getAbsolutePath())
                            .apply();
                    applyCurrentWallpaper();
                    Toast.makeText(this, "✓ Wallpaper applied successfully!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed to load selected image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showWifiUploadDialog() {
        String ip = WallpaperServer.getIpAddress(this);
        String url = "http://" + ip + ":" + WallpaperServer.PORT;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(30), dp(26), dp(30), dp(24));
        layout.setBackground(round(0xf2131b28, 22, 1, 0x30ffffff));

        TextView title = new TextView(this);
        title.setText("Upload from Phone or PC");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        layout.addView(title);

        TextView sub = new TextView(this);
        sub.setText("1. Make sure your phone/PC is connected to the same Wi-Fi.\n2. Open this address in any web browser:");
        sub.setTextSize(13);
        sub.setTextColor(0xff9eabc0);
        sub.setPadding(0, dp(10), 0, dp(16));
        sub.setLineSpacing(dp(4), 1f);
        layout.addView(sub);

        // URL display box
        TextView urlBox = new TextView(this);
        urlBox.setText(url);
        urlBox.setTextSize(22);
        urlBox.setTextColor(0xff5cbcc9);
        urlBox.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        urlBox.setGravity(Gravity.CENTER);
        urlBox.setPadding(dp(20), dp(16), dp(20), dp(16));
        urlBox.setBackground(round(0x255cbcc9, 14, 1, 0x605cbcc9));
        layout.addView(urlBox);

        TextView liveStatus = new TextView(this);
        liveStatus.setText("● Server is active and waiting for your upload...");
        liveStatus.setTextSize(12);
        liveStatus.setTextColor(0xff75dfa3);
        liveStatus.setGravity(Gravity.CENTER);
        liveStatus.setPadding(0, dp(14), 0, dp(16));
        layout.addView(liveStatus);

        TextView btnDone = new TextView(this);
        btnDone.setText("Close");
        btnDone.setTextSize(13);
        btnDone.setTextColor(Color.WHITE);
        btnDone.setGravity(Gravity.CENTER);
        btnDone.setPadding(dp(18), dp(11), dp(18), dp(11));
        btnDone.setFocusable(true);
        btnDone.setClickable(true);
        btnDone.setBackground(round(0x35ffffff, 12, 0, 0));
        btnDone.setOnClickListener(v -> dialog.dismiss());
        btnDone.setOnFocusChangeListener((v, f) -> {
            btnDone.setBackground(round(f ? WHITE : 0x35ffffff, 12, 0, 0));
            btnDone.setTextColor(f ? Color.rgb(18, 24, 38) : Color.WHITE);
        });
        layout.addView(btnDone);

        dialog.setContentView(layout);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(dp(560), -2);
            dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        dialog.show();
        btnDone.requestFocus();
    }

    // ------------------------------------------------------------------ Lock screen

    private void buildLockScreenOverlay() {
        lockScreenOverlay = new FrameLayout(this);
        lockScreenOverlay.setVisibility(View.GONE);
        lockScreenOverlay.setClipChildren(false);
        lockScreenOverlay.setClipToPadding(false);

        lockScreenWallpaper = new ImageView(this);
        lockScreenWallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lockScreenWallpaper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        lockScreenOverlay.addView(lockScreenWallpaper, new FrameLayout.LayoutParams(-1, -1));

        // Light scrim: keeps the photo visible while the clock and dock stay readable
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x1f000000, 0x1f000000, 0x73000000}));
        lockScreenOverlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // Date above a large liquid glass clock, centered at the top
        LinearLayout clockBox = column();
        clockBox.setGravity(Gravity.CENTER_HORIZONTAL);

        lockScreenDate = text("", 16, 0xebffffff);
        lockScreenDate.setGravity(Gravity.CENTER);
        lockScreenDate.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lockScreenDate.setShadowLayer(dp(8), 0, dp(1), 0x59000000);
        clockBox.addView(lockScreenDate, new LinearLayout.LayoutParams(-1, -2));

        lockScreenClock = new GlassClockView(this);
        lockScreenClock.setTextSize(96);
        lockScreenClock.setGravity(Gravity.CENTER);
        lockScreenClock.setPadding(0, dp(2), 0, 0);
        clockBox.addView(lockScreenClock, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams clockLP = new FrameLayout.LayoutParams(-1, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clockLP.topMargin = dp(36);
        lockScreenOverlay.addView(clockBox, clockLP);

        // Transient hint above the dock
        lockScreenHint = text("Hold OK to edit the dock   ·   Back for Home", 12, 0xccffffff);
        lockScreenHint.setGravity(Gravity.CENTER);
        lockScreenHint.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        lockScreenHint.setAlpha(0f);
        FrameLayout.LayoutParams hintLP = new FrameLayout.LayoutParams(-1, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLP.bottomMargin = dp(120);
        lockScreenOverlay.addView(lockScreenHint, hintLP);

        // Glass app dock at the bottom
        lockDockRow = row();
        lockDockRow.setGravity(Gravity.CENTER_VERTICAL);
        lockDockRow.setPadding(dp(12), dp(11), dp(12), dp(11));
        lockDockRow.setBackground(round(0x5c4a505c, 26, 1, 0x33ffffff));
        FrameLayout.LayoutParams dockLP = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockLP.bottomMargin = dp(28);
        lockScreenOverlay.addView(lockDockRow, dockLP);

        // Fallback standby: black screen when the device admin is not active
        lockBlackoutView = new FrameLayout(this);
        lockBlackoutView.setBackgroundColor(Color.BLACK);
        lockBlackoutView.setVisibility(View.GONE);
        lockBlackoutHint = text("Press any button to wake", 13, 0x80ffffff);
        lockBlackoutView.addView(lockBlackoutHint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        lockScreenOverlay.addView(lockBlackoutView, new FrameLayout.LayoutParams(-1, -1));

        root.addView(lockScreenOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void updateLockClock() {
        Date now = new Date();
        String pattern = DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm";
        lockScreenClock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        lockScreenDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
    }

    private void showLockScreen(boolean blackout) {
        boolean wasActive = lockScreenActive;
        lockScreenActive = true;
        lockBlackout = blackout;
        lockSelectArmed = false;
        handler.removeCallbacks(idleLock);
        if (!wasActive) {
            Drawable current = wallpaper.getDrawable();
            lockScreenWallpaper.setImageDrawable(current != null && current.getConstantState() != null
                    ? current.getConstantState().newDrawable(getResources()) : current);
            updateLockClock();
            lockScreenOverlay.animate().cancel();
            lockScreenOverlay.setVisibility(View.VISIBLE);
            lockScreenOverlay.setAlpha(0f);
            lockScreenOverlay.animate().alpha(1f).setDuration(450).start();
            lockDockIndex = 0;
            focusLockDock(0);
        }
        if (blackout) {
            stopKenBurns();
            handler.removeCallbacks(hideLockHint);
            lockBlackoutView.animate().cancel();
            lockBlackoutView.setVisibility(View.VISIBLE);
            lockBlackoutView.setAlpha(wasActive ? 0f : 1f);
            lockBlackoutView.animate().alpha(1f).setStartDelay(0).setDuration(600).start();
            lockBlackoutHint.animate().cancel();
            lockBlackoutHint.setAlpha(1f);
            lockBlackoutHint.animate().alpha(0f).setStartDelay(4000).setDuration(800).start();
        } else {
            lockBlackoutView.setVisibility(View.GONE);
            startKenBurns();
            flashLockHint();
        }
        updateKeepScreenOn();
        scheduleStandby();
        updateNowShowingState();
    }

    private void wakeFromBlackout() {
        lockBlackout = false;
        lockBlackoutView.animate().cancel();
        lockBlackoutView.animate().alpha(0f).setStartDelay(0).setDuration(400)
                .withEndAction(() -> lockBlackoutView.setVisibility(View.GONE)).start();
        startKenBurns();
        focusLockDock(lockDockIndex);
        flashLockHint();
        updateKeepScreenOn();
        scheduleStandby();
    }

    private void dismissLockScreen(boolean animate) {
        if (!lockScreenActive) return;
        lockScreenActive = false;
        lockBlackout = false;
        handler.removeCallbacks(standbyRunnable);
        handler.removeCallbacks(hideLockHint);
        Runnable finish = () -> {
            lockScreenOverlay.setVisibility(View.GONE);
            lockBlackoutView.setVisibility(View.GONE);
            stopKenBurns();
        };
        lockScreenOverlay.animate().cancel();
        if (animate) lockScreenOverlay.animate().alpha(0f).setDuration(280).withEndAction(finish).start();
        else finish.run();
        focusDock(lastDockIndex);
        noteInteraction();
        updateNowShowingState();
    }

    private void flashLockHint() {
        handler.removeCallbacks(hideLockHint);
        lockScreenHint.animate().cancel();
        lockScreenHint.animate().alpha(1f).setDuration(400).start();
        handler.postDelayed(hideLockHint, 4500);
    }

    /** Slow pan/zoom to keep the static image from burning in. */
    private void startKenBurns() {
        if (kenBurns == null) {
            kenBurns = ObjectAnimator.ofPropertyValuesHolder(lockScreenWallpaper,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
            kenBurns.setDuration(40_000);
            kenBurns.setRepeatCount(ObjectAnimator.INFINITE);
            kenBurns.setRepeatMode(ObjectAnimator.REVERSE);
            kenBurns.setInterpolator(new LinearInterpolator());
        }
        if (!kenBurns.isStarted()) kenBurns.start();
    }

    private void stopKenBurns() {
        if (kenBurns != null) kenBurns.cancel();
        lockScreenWallpaper.setScaleX(1f);
        lockScreenWallpaper.setScaleY(1f);
    }

    /** Remote handling while locked: Left/Right move along the dock, OK opens, Back/Up unlocks. */
    private boolean dispatchLockKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (isPassThroughKey(code)) return super.dispatchKeyEvent(event);
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return select && lockSelectArmed && !lockBlackout ? super.dispatchKeyEvent(event) : true;
        }
        noteInteraction();
        if (lockBlackout) {
            lockSelectArmed = false;
            wakeFromBlackout();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_UP:
                dismissLockScreen(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                focusLockDock(lockDockIndex - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focusLockDock(lockDockIndex + 1);
                return true;
            default:
                if (!select) return true;
                if (event.getRepeatCount() == 0) lockSelectArmed = true;
                if (!lockDockViews.contains(getCurrentFocus())) focusLockDock(lockDockIndex);
                return super.dispatchKeyEvent(event);
        }
    }

    private static boolean isPassThroughKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ Lock screen dock

    private void loadLockDock(LinkedHashMap<String, App> found) {
        lockDock.clear();
        if (preferences.contains("lock_dock")) {
            try {
                JSONArray saved = new JSONArray(preferences.getString("lock_dock", "[]"));
                for (int i = 0; i < saved.length() && lockDock.size() < LOCK_DOCK_MAX; i++) {
                    String pkg = saved.getString(i);
                    if (found.containsKey(pkg) && !lockDock.contains(pkg)) lockDock.add(pkg);
                }
            } catch (JSONException ignored) { }
            return;
        }
        // First run: favorites, then popular media apps that are installed
        for (String pkg : favorites) if (lockDock.size() < 8 && !lockDock.contains(pkg)) lockDock.add(pkg);
        for (String pkg : Arrays.asList("com.disney.disneyplus", "com.disneyplus.ph", "com.soundcloud.android",
                "com.google.android.youtube.tvmusic", "com.spotify.tv.android", "com.crunchyroll.crunchyroid",
                "com.apple.atve.androidtv.appletv", "com.emby.androidtv")) {
            if (lockDock.size() < 8 && found.containsKey(pkg) && !lockDock.contains(pkg)) lockDock.add(pkg);
        }
        saveLockDock();
    }

    private void saveLockDock() {
        preferences.edit().putString("lock_dock", new JSONArray(lockDock).toString()).apply();
    }

    private void renderLockDock() {
        if (lockDockRow == null) return;
        lockDockRow.removeAllViews();
        lockDockViews.clear();
        int size = dp(50);
        for (String pkg : lockDock) {
            App app = find(pkg);
            if (app != null) addLockDockView(lockDockItem(app, size), size);
        }
        if (lockDockViews.isEmpty()) addLockDockView(lockDockAddItem(size), size);
        lockDockIndex = Math.max(0, Math.min(lockDockIndex, lockDockViews.size() - 1));
        if (lockScreenActive && !lockBlackout) focusLockDock(lockDockIndex);
    }

    private void addLockDockView(View item, int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        if (!lockDockViews.isEmpty()) lp.leftMargin = dp(12);
        lockDockRow.addView(item, lp);
        item.setNextFocusLeftId(item.getId());
        item.setNextFocusRightId(item.getId());
        item.setNextFocusUpId(item.getId());
        item.setNextFocusDownId(item.getId());
        lockDockViews.add(item);
    }

    private void focusLockDock(int index) {
        if (lockDockViews.isEmpty()) return;
        lockDockIndex = Math.max(0, Math.min(index, lockDockViews.size() - 1));
        View target = lockDockViews.get(lockDockIndex);
        target.requestFocus();
        if (!target.isFocused()) target.post(target::requestFocus);
    }

    private View lockDockItem(App app, int size) {
        FrameLayout tile = lockDockTile();
        tile.setTag("lock:" + app.pkg);
        tile.setContentDescription(app.name + ". Hold OK to edit the lock screen dock.");
        ImageView icon = new ImageView(this);
        Drawable drawable = dockIcon(app, size);
        icon.setImageDrawable(drawable);
        icon.setScaleType(drawable instanceof LayerDrawable ? ImageView.ScaleType.FIT_XY
                : ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        tile.setOnClickListener(v -> {
            dismissLockScreen(false);
            launch(app);
        });
        tile.setOnLongClickListener(v -> {
            showLockDockOptions(app);
            return true;
        });
        return tile;
    }

    private View lockDockAddItem(int size) {
        FrameLayout tile = lockDockTile();
        tile.setContentDescription("Add an app to the lock screen dock");
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setColorFilter(Color.WHITE);
        tile.addView(plus, new FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER));
        tile.setOnClickListener(v -> {
            List<App> candidates = new ArrayList<>();
            for (App candidate : apps) if (!lockDock.contains(candidate.pkg)) candidates.add(candidate);
            if (candidates.isEmpty()) {
                Toast.makeText(this, "All apps are already on the lock screen.", Toast.LENGTH_SHORT).show();
                return;
            }
            LockDockDialog.showAppPickerGrid(this, "Add to Lock Screen Dock", candidates, chosen -> {
                lockDock.add(chosen.pkg);
                saveLockDock();
                renderLockDock();
            });
        });
        return tile;
    }

    private FrameLayout lockDockTile() {
        FrameLayout tile = new FrameLayout(this);
        tile.setId(View.generateViewId());
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setBackground(round(0x33ffffff, 13, 0, 0));
        tile.setClipToOutline(true);
        tile.setElevation(dp(3));
        tile.setOnFocusChangeListener((v, focused) -> {
            tile.setForeground(focused ? round(0x1affffff, 13, 2, 0xf2ffffff) : null);
            tile.animate().scaleX(focused ? 1.14f : 1f).scaleY(focused ? 1.14f : 1f)
                    .translationZ(focused ? dp(10) : 0).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (focused) {
                int index = lockDockViews.indexOf(v);
                if (index >= 0) lockDockIndex = index;
            }
        });
        return tile;
    }

    /** Full-bleed rounded-square icon: adaptive icons are unmasked and cropped to their visible area. */
    private Drawable dockIcon(App app, int size) {
        Drawable icon = null;
        try { icon = app.info.loadIcon(getPackageManager()); }
        catch (RuntimeException ignored) { }
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
            List<Drawable> layers = new ArrayList<>();
            if (adaptive.getBackground() != null) layers.add(adaptive.getBackground());
            if (adaptive.getForeground() != null) layers.add(adaptive.getForeground());
            if (!layers.isEmpty()) {
                LayerDrawable layered = new LayerDrawable(layers.toArray(new Drawable[0]));
                int inset = -size / 4; // layers are 108dp with a 72dp visible area
                for (int i = 0; i < layers.size(); i++) layered.setLayerInset(i, inset, inset, inset, inset);
                return layered;
            }
        }
        return icon;
    }

    private interface PackageChoice { void choose(String pkg); }

    private void pickAppForLockDock(String title, PackageChoice choice) {
        List<App> candidates = new ArrayList<>();
        for (App app : apps) if (!lockDock.contains(app.pkg)) candidates.add(app);
        if (candidates.isEmpty()) {
            Toast.makeText(this, "All apps are already on the lock screen.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < names.length; i++) names[i] = candidates.get(i).name;
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(names, (d, which) -> choice.choose(candidates.get(which).pkg))
                .setNegativeButton("Cancel", null).show();
    }

    private void showLockDockOptions(App app) {
        int index = lockDock.indexOf(app.pkg);
        if (index < 0) return;
        Drawable icon = dockIcon(app, dp(50));
        LockDockDialog.showActionMenu(this, app, icon, index, lockDock.size(), LOCK_DOCK_MAX,
                new LockDockDialog.ActionCallback() {
                    @Override public void onMoveLeft() {
                        if (index > 0) {
                            lockDock.set(index, lockDock.get(index - 1));
                            lockDock.set(index - 1, app.pkg);
                            lockDockIndex = index - 1;
                            saveLockDock();
                            renderLockDock();
                        }
                    }

                    @Override public void onMoveRight() {
                        if (index < lockDock.size() - 1) {
                            lockDock.set(index, lockDock.get(index + 1));
                            lockDock.set(index + 1, app.pkg);
                            lockDockIndex = index + 1;
                            saveLockDock();
                            renderLockDock();
                        }
                    }

                    @Override public void onReplace() {
                        List<App> candidates = new ArrayList<>();
                        for (App candidate : apps) if (!lockDock.contains(candidate.pkg)) candidates.add(candidate);
                        LockDockDialog.showAppPickerGrid(MainActivity.this, "Replace " + app.name, candidates, chosen -> {
                            lockDock.set(index, chosen.pkg);
                            lockDockIndex = index;
                            saveLockDock();
                            renderLockDock();
                        });
                    }

                    @Override public void onAddAfter() {
                        List<App> candidates = new ArrayList<>();
                        for (App candidate : apps) if (!lockDock.contains(candidate.pkg)) candidates.add(candidate);
                        LockDockDialog.showAppPickerGrid(MainActivity.this, "Add After " + app.name, candidates, chosen -> {
                            lockDock.add(index + 1, chosen.pkg);
                            lockDockIndex = index + 1;
                            saveLockDock();
                            renderLockDock();
                        });
                    }

                    @Override public void onRemove() {
                        lockDock.remove(index);
                        lockDockIndex = Math.max(0, index - 1);
                        saveLockDock();
                        renderLockDock();
                    }
                });
    }

    private void refreshApps() {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, App> found = new LinkedHashMap<>();
        for (String category : Arrays.asList(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || pkg.equals("com.google.android.apps.tv.launcherx")
                        || pkg.equals("com.google.android.tvlauncher") || pkg.equals("fusion.android.tv.demo")
                        || pkg.equals("com.coocaa.demonow.lightining") || pkg.equals("com.mediatek.wwtv.setupwizard")
                        || !info.activityInfo.exported) continue;
                if (!found.containsKey(pkg)) found.put(pkg, new App(info, pm));
            }
        }
        List<App> discovered = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        discovered.sort((a, b) -> collator.compare(a.name, b.name));

        StringBuilder signature = new StringBuilder();
        for (App app : discovered) {
            signature.append(app.pkg).append('|').append(app.info.activityInfo.name).append('|')
                    .append(app.info.activityInfo.applicationInfo.sourceDir).append('|').append(app.name).append('\n');
        }
        if (signature.toString().equals(appSignature)) return;
        appSignature = signature.toString();
        apps.clear();
        apps.addAll(discovered);
        favorites.clear();
        if (preferences.contains("favorites")) {
            try {
                JSONArray saved = new JSONArray(preferences.getString("favorites", "[]"));
                for (int i = 0; i < saved.length() && favorites.size() < COLUMNS; i++) {
                    String pkg = saved.getString(i);
                    if (found.containsKey(pkg) && !favorites.contains(pkg)) favorites.add(pkg);
                }
            } catch (JSONException ignored) {}
        } else {
            for (String pkg : Arrays.asList("com.netflix.ninja", "com.google.android.youtube.tv",
                    "com.disneyplus.ph", "com.disney.disneyplus", "com.spotify.tv.android", "com.apple.atve.androidtv.appletv")) {
                if (found.containsKey(pkg) && favorites.size() < COLUMNS) favorites.add(pkg);
            }
            for (App app : apps) {
                if (favorites.size() == COLUMNS) break;
                if (!favorites.contains(app.pkg)) favorites.add(app.pkg);
            }
            saveFavorites();
        }
        loadLockDock(found);
        renderApps();
        renderLockDock();
    }

    private void renderApps() {
        String restoreKey = focusKey;
        content.removeAllViews();
        dockRow.removeAllViews();
        favoriteViews.clear();
        libraryViews.clear();
        int screen = getResources().getDisplayMetrics().widthPixels;
        tileWidth = (screen - dp(96 + 28 + 16 * (COLUMNS - 1))) / COLUMNS;
        for (String pkg : favorites) {
            App app = find(pkg);
            if (app == null) continue;
            View tile = appTile(app, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileWidth, -2);
            if (!favoriteViews.isEmpty()) lp.leftMargin = dp(16);
            dockRow.addView(tile, lp);
            favoriteViews.add(tile);
        }
        if (favoriteViews.isEmpty()) {
            TextView empty = text("Press ↓ to add your favorite apps", 16, WHITE);
            empty.setPadding(dp(16), dp(22), dp(16), dp(22));
            empty.setFocusable(true);
            empty.setOnKeyListener((v, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                    openDrawer(0); return true;
                }
                return false;
            });
            empty.setOnClickListener(v -> openDrawer(0));
            dockRow.addView(empty);
        }
        tileWidth = (screen - dp(96 + 46 + 16 + 20 * (COLUMNS - 1))) / COLUMNS;
        LinearLayout current = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) { current = row(); content.addView(current); }
            View tile = appTile(apps.get(i), false);
            addTile(current, tile, i % COLUMNS);
            libraryViews.add(tile);
        }
        if (apps.isEmpty()) content.addView(text("No launchable apps found. Open Settings above to install apps.", 14, MUTED));
        wireNavigation();
        View restore = root.findViewWithTag(restoreKey);
        if (restore == null || (!drawerOpen && libraryViews.contains(restore))) {
            restore = !favoriteViews.isEmpty() ? favoriteViews.get(Math.min(lastDockIndex, favoriteViews.size() - 1))
                    : dockRow.getChildCount() > 0 ? dockRow.getChildAt(0) : shortcuts.get(0);
        }
        if (drawerOpen && favoriteViews.contains(restore) && !libraryViews.isEmpty()) restore = libraryViews.get(0);
        View target = restore;
        target.post(() -> target.requestFocus());
        if (selectedName.getText().length() == 0 && !favorites.isEmpty() && find(favorites.get(0)) != null)
            selectedName.setText(find(favorites.get(0)).name);
    }

    private void addTile(LinearLayout row, View tile, int column) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileWidth, -2);
        if (column > 0) lp.leftMargin = dp(20);
        row.addView(tile, lp);
    }

    private View appTile(App app, boolean favorite) {
        LinearLayout tile = column();
        tile.setId(View.generateViewId());
        tile.setTag((favorite ? "favorite:" : "app:") + app.pkg);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipChildren(false);
        tile.setClipToPadding(false);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(0, 0, 0, favorite ? 0 : dp(12));
        FrameLayout artwork = new FrameLayout(this);
        int tone = tone(app.pkg);
        artwork.setBackground(round(tone, 11, 0, 0));
        artwork.setClipToOutline(true);
        artwork.setElevation(dp(4));
        ImageView picture = new ImageView(this);
        Drawable banner = null;
        try { banner = app.info.activityInfo.loadBanner(getPackageManager()); }
        catch (RuntimeException ignored) { }
        boolean hasBanner = banner != null;
        if (!hasBanner) {
            try { banner = app.info.loadIcon(getPackageManager()); }
            catch (RuntimeException ignored) { }
        }
        picture.setImageDrawable(banner);
        picture.setScaleType(hasBanner ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_INSIDE);
        if (!hasBanner) picture.setPadding(dp(31), dp(13), dp(31), dp(13));
        artwork.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        if (banner == null) {
            TextView initial = text(app.name.substring(0, 1).toUpperCase(Locale.getDefault()), 30, WHITE);
            initial.setGravity(Gravity.CENTER);
            artwork.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        }
        artwork.setForeground(round(Color.TRANSPARENT, 11, 1, Color.argb(30, 255, 255, 255)));
        tile.addView(artwork, new LinearLayout.LayoutParams(-1, Math.round(tileWidth * 9f / 16f)));
        TextView label = text(app.name, 12, Color.rgb(177, 184, 201));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(2), dp(10), dp(2), dp(2));
        if (!favorite) tile.addView(label, new LinearLayout.LayoutParams(-1, -2));
        tile.setContentDescription(app.name + (favorite ? ", favorite" : "") + ". Hold OK for options.");
        tile.setOnClickListener(v -> launch(app));
        tile.setOnLongClickListener(v -> { showOptions(app); return true; });
        tile.setOnFocusChangeListener((v, focused) -> {
            artwork.animate().scaleX(focused ? 1.07f : 1).scaleY(focused ? 1.07f : 1)
                    .translationZ(focused ? dp(12) : 0).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
            artwork.setForeground(round(Color.TRANSPARENT, 11, focused ? 2 : 1,
                    focused ? WHITE : Color.argb(30, 255, 255, 255)));
            label.setTextColor(focused ? WHITE : Color.rgb(177, 184, 201));
            if (focused) {
                focusKey = v.getTag().toString();
                if (favorite) {
                    lastDockIndex = favoriteViews.indexOf(v);
                    selectedName.setText(app.name);
                    hint.setText(R.string.dock_hint);
                } else hint.setText(getString(R.string.app_hint, app.name));
            }
        });
        return tile;
    }

    private void wireNavigation() {
        if (shortcuts.isEmpty()) return;
        List<View> first = drawerOpen ? libraryViews : !favoriteViews.isEmpty() ? favoriteViews
                : dockRow.getChildCount() > 0 ? Arrays.asList(dockRow.getChildAt(0)) : favoriteViews;

        for (int i = 0; i < shortcuts.size(); i++) {
            View v = shortcuts.get(i);
            v.setNextFocusUpId(v.getId());
            v.setNextFocusLeftId(shortcuts.get(Math.max(0, i - 1)).getId());
            v.setNextFocusRightId(shortcuts.get(Math.min(shortcuts.size() - 1, i + 1)).getId());
            v.setNextFocusDownId(first.isEmpty() ? v.getId() : first.get(Math.min(first.size() - 1, i + 1)).getId());
        }

        for (int i = 0; i < favoriteViews.size(); i++) {
            View v = favoriteViews.get(i);
            v.setNextFocusLeftId(favoriteViews.get(Math.max(0, i - 1)).getId());
            v.setNextFocusRightId(favoriteViews.get(Math.min(favoriteViews.size() - 1, i + 1)).getId());
            v.setNextFocusDownId(v.getId());
            v.setNextFocusUpId(shortcuts.get(0).getId());
        }

        for (int i = 0; i < libraryViews.size(); i++) {
            View v = libraryViews.get(i);
            int col = i % COLUMNS;
            v.setNextFocusLeftId(libraryViews.get(col == 0 ? i : i - 1).getId());
            v.setNextFocusRightId(libraryViews.get(col == COLUMNS - 1 || i == libraryViews.size() - 1 ? i : i + 1).getId());
            v.setNextFocusUpId(i >= COLUMNS ? libraryViews.get(i - COLUMNS).getId() : v.getId());
            int lastRow = (libraryViews.size() - 1) / COLUMNS;
            v.setNextFocusDownId(i / COLUMNS == lastRow ? v.getId()
                    : libraryViews.get(Math.min(libraryViews.size() - 1, i + COLUMNS)).getId());
        }
    }

    private void launch(App app) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(app.pkg, app.info.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Unable to open " + app.name + ". Try App info.", Toast.LENGTH_LONG).show();
        }
    }

    private void showOptions(App app) {
        List<String> options = new ArrayList<>();
        int index = favorites.indexOf(app.pkg);
        options.add(index >= 0 ? "Remove from favorites" : "Add to favorites");
        if (index > 0) options.add("Move favorite left");
        if (index >= 0 && index < favorites.size() - 1) options.add("Move favorite right");
        boolean onLock = lockDock.contains(app.pkg);
        options.add(onLock ? "Remove from lock screen" : "Add to lock screen");
        options.add("App info");
        new AlertDialog.Builder(this).setTitle(app.name)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String action = options.get(which);
                    if (action.equals("App info")) {
                        safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + app.pkg)));
                        return;
                    }
                    if (action.equals("Add to lock screen") || action.equals("Remove from lock screen")) {
                        if (onLock) lockDock.remove(app.pkg);
                        else if (lockDock.size() >= LOCK_DOCK_MAX) {
                            Toast.makeText(this, "The lock screen dock is full (" + LOCK_DOCK_MAX + " apps).",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        } else lockDock.add(app.pkg);
                        saveLockDock();
                        renderLockDock();
                        Toast.makeText(this, onLock ? app.name + " removed from the lock screen"
                                : app.name + " added to the lock screen", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (action.equals("Add to favorites")) {
                        if (favorites.size() == COLUMNS) {
                            List<String> names = new ArrayList<>();
                            for (String pkg : favorites) names.add(find(pkg).name);
                            new AlertDialog.Builder(this).setTitle("Replace a favorite")
                                     .setItems(names.toArray(new String[0]), (d, position) -> {
                                        favorites.set(position, app.pkg);
                                        saveAndRender("favorite:" + app.pkg);
                                    }).setNegativeButton("Cancel", null).show();
                            return;
                        }
                        favorites.add(app.pkg);
                    } else if (action.equals("Remove from favorites")) favorites.remove(app.pkg);
                    else {
                        int target = action.equals("Move favorite left") ? index - 1 : index + 1;
                        favorites.set(index, favorites.get(target));
                        favorites.set(target, app.pkg);
                    }
                    saveAndRender((favorites.contains(app.pkg) ? "favorite:" : "app:") + app.pkg);
                }).setNegativeButton("Cancel", null).show();
    }

    private void saveAndRender(String key) {
        saveFavorites();
        focusKey = key;
        if (key.startsWith("favorite:")) {
            drawerOpen = false;
            drawer.setVisibility(View.GONE);
            drawerShade.setVisibility(View.GONE);
            dockSection.setVisibility(View.VISIBLE);
            mediaPanel.setHomeVisible(true);
        }
        updateNowShowingState();
        renderApps();
    }

    private void saveFavorites() {
        preferences.edit().putString("favorites", new JSONArray(favorites).toString()).apply();
    }

    private App find(String pkg) {
        for (App app : apps) if (app.pkg.equals(pkg)) return app;
        return null;
    }

    public void openInputs() {
        TvInputManager manager = getSystemService(TvInputManager.class);
        List<TvInputInfo> inputs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (manager != null) {
            for (TvInputInfo input : manager.getTvInputList()) {
                if (!input.isPassthroughInput()
                        && !input.getServiceInfo().packageName.equals("com.mediatek.tvinput")) continue;
                if (input.getParentId() != null || input.isHidden(this)) continue;
                CharSequence custom = input.loadCustomLabel(this);
                CharSequence label = custom != null ? custom : input.loadLabel(this);
                inputs.add(input);
                labels.add(label == null ? "TV input" : label.toString());
            }
        }
        if (!inputs.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Inputs")
                    .setItems(labels.toArray(new String[0]), (dialog, position) -> {
                        TvInputInfo input = inputs.get(position);
                        Uri uri = input.isPassthroughInput()
                                ? TvContract.buildChannelUriForPassthroughInput(input.getId())
                                : TvContract.buildChannelsUriForInput(input.getId());
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                                .putExtra("android.media.tv.extra.INPUT_ID", input.getId());
                        App nativeTv = find("com.mediatek.wwtv.tvcenter");
                        if (nativeTv != null) intent.setComponent(new ComponentName(nativeTv.pkg,
                                nativeTv.info.activityInfo.name));
                        safeStart(intent);
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        for (String action : Arrays.asList("com.mediatek.tv.action.SELECT_SOURCE",
                "android.intent.action.TV_INPUT_SETTINGS", "android.media.tv.action.SETUP_INPUTS")) {
            try { startActivity(new Intent(action)); return; }
            catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        openSetting(Settings.ACTION_SETTINGS);
    }

    private void openSetting(String action) { safeStart(new Intent(action)); }

    private void safeStart(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            if (!Settings.ACTION_SETTINGS.equals(intent.getAction())) {
                safeStart(new Intent(Settings.ACTION_SETTINGS));
            } else Toast.makeText(this, "Settings is unavailable on this TV.", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setClipChildren(false);
        layout.setClipToPadding(false);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke > 0) drawable.setStroke(dp(stroke), strokeColor);
        return drawable;
    }

    private GradientDrawable circle(int color, int stroke, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (stroke > 0) drawable.setStroke(dp(stroke), strokeColor);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private int tone(String pkg) {
        int[] tones = {0xff192d48, 0xff242a45, 0xff30283d, 0xff203838, 0xff292c3a};
        return tones[Math.floorMod(pkg.hashCode(), tones.length)];
    }

    public static class App {
        final String pkg, name;
        final ResolveInfo info;
        App(ResolveInfo info, PackageManager pm) {
            this.info = info;
            pkg = info.activityInfo.packageName;
            name = info.loadLabel(pm).toString();
        }
    }
}
