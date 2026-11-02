package com.custom.tvlauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.ViewOutlineProvider;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Apple TV / tvOS-style Control Center:
 * Clean, modern frosted & liquid glass interface.
 * Features: Lock Screen with timeout controls, Sleep Timer, Standby,
 * Memory RAM Optimizer ("Free Up"), TV Inputs, and Wallpaper Manager.
 */
public class ControlCenterDialog extends Dialog {

    public interface Callback {
        void onOpenInputs();
        void onOpenWallpaper();
        void onLockNow();
        void onSetLockTimeout(int minutes);
        int getLockTimeoutMinutes();
        void onSetStandbyMinutes(int minutes);
        int getStandbyMinutes();
        boolean isStandbyAvailable();
        void onSetSleepTimer(int minutes);
        int getRemainingSleepMinutes();
        MemoryStats getMemoryStats();
        MemoryStats freeUpMemory();
        boolean isNowShowingEnabled();
        boolean isNowShowingAvailable();
        void onSetNowShowing(boolean enabled);
    }

    public static class MemoryStats {
        public long totalBytes;
        public long availBytes;
        public long usedBytes;
        public int percentUsed;
        public long freedBytes;

        public String getSummary() {
            return percentUsed + "% used  ·  " + formatSize(availBytes) + " free";
        }
    }

    // Apple TV / iOS frosted & liquid glass palette (no electric blue)
    private static final int LABEL_DARK = 0xff141518;
    private static final int LABEL_DARK_MUTED = 0x8c141518;
    private static final int LABEL_LIGHT_2 = 0xa6ffffff;

    // Smoky frosted glass tiles
    private static final int TILE_GLASS = 0x24ffffff;
    private static final int TILE_GLASS_RIM = 0x2affffff;
    private static final int TILE_GLASS_ACTIVE = 0x52ffffff;
    private static final int TILE_GLASS_ACTIVE_RIM = 0x66ffffff;
    private static final int TILE_FOCUSED = 0xfff5f5f7;

    private static final int[] SLEEP_VALUES = {0, 15, 30, 45, 60, 90, 120, -1};
    private static final String[] SLEEP_LABELS = {"Off", "15 minutes", "30 minutes", "45 minutes",
            "1 hour", "1.5 hours", "2 hours", "Sleep Now"};

    private static final int[] LOCK_VALUES = {-1, 0, 1, 2, 5, 10, 15, 30};
    private static final String[] LOCK_LABELS = {"Lock Now", "Off (Never)", "1 minute", "2 minutes",
            "5 minutes", "10 minutes", "15 minutes", "30 minutes"};

    private static final int[] STANDBY_VALUES = {0, 15, 30, 60, 120, 240};
    private static final String[] STANDBY_LABELS = {"Never", "15 minutes", "30 minutes",
            "1 hour", "2 hours", "4 hours"};

    private final Activity activity;
    private final Callback callback;
    private final FrameLayout pageHost;
    private final FrameLayout panelContainer;
    private final LinearLayout panel;
    private LinearLayout mainPage;
    private View subPage;
    private final List<List<View>> mainRows = new ArrayList<>();

    private Tile lockTile, sleepTile, standbyTile;
    private TextView memoryValue;
    private View memoryFill;
    private FrameLayout memoryTrack;
    private int memoryPercent;

    private boolean isDismissing = false;

    public ControlCenterDialog(Activity activity, Callback callback) {
        this(activity, null, callback);
    }

    public ControlCenterDialog(Activity activity, Bitmap blurSource, Callback callback) {
        super(activity);
        this.activity = activity;
        this.callback = callback;
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        boolean blur = blurSupported();

        // Configure window layout params IMMEDIATELY before show()
        // This ensures the window is anchored at top-right from frame 0 and never pops up in the middle
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(dp(416), ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.TOP | Gravity.END);
            window.setWindowAnimations(0);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.x = dp(22);
            attrs.y = dp(18);
            attrs.dimAmount = 0.45f;
            attrs.windowAnimations = 0;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (blur && Build.VERSION.SDK_INT >= 31) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                attrs.setBlurBehindRadius(dp(20));
            }
            window.setAttributes(attrs);
        }

        panelContainer = new FrameLayout(activity);
        panelContainer.setClipChildren(false);
        panelContainer.setClipToPadding(false);
        panelContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
        panelContainer.setClipToOutline(true);

        // Dynamic wallpaper frosted glass underlay
        if (blurSource != null && !blurSource.isRecycled()) {
            ImageView blurBackdrop = new ImageView(activity);
            blurBackdrop.setImageBitmap(blurSource);
            blurBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            blurBackdrop.setAlpha(0.60f);
            panelContainer.addView(blurBackdrop, new FrameLayout.LayoutParams(-1, -1));
        }

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(22));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        // Frosted liquid glass backdrop with specular edge
        panel.setBackground(shape(blurSource != null ? 0xcc141720 : (blur ? 0xd016181f : 0xf2181a21),
                28, 1, 0x30ffffff));

        pageHost = new FrameLayout(activity);
        pageHost.setClipChildren(false);
        pageHost.setClipToPadding(false);
        panel.addView(pageHost, new LinearLayout.LayoutParams(-1, -2));

        buildMainPage();
        pageHost.addView(mainPage, new FrameLayout.LayoutParams(-1, -2));
        panelContainer.addView(panel, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout frame = new FrameLayout(activity);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        frame.setPadding(dp(8), dp(8), dp(8), dp(8));
        frame.addView(panelContainer, new FrameLayout.LayoutParams(-1, -2));
        setContentView(frame);

        // Initial entrance state: start up and to the right near the header control center button
        panelContainer.setPivotX(dp(416) - dp(40));
        panelContainer.setPivotY(0);
        panelContainer.setAlpha(0f);
        panelContainer.setTranslationY(-dp(140));
        panelContainer.setTranslationX(dp(28));
        panelContainer.setScaleX(0.88f);
        panelContainer.setScaleY(0.88f);

        setOnShowListener(d -> {
            isDismissing = false;
            Window win = getWindow();
            if (win != null) {
                win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            // Smoothly slide down from the top right button into resting place
            panelContainer.animate()
                    .alpha(1f)
                    .translationY(0)
                    .translationX(0)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(2.0f))
                    .start();
            if (!mainRows.isEmpty()) mainRows.get(0).get(0).requestFocus();
        });
    }

    // ------------------------------------------------------------------ main page

    private void buildMainPage() {
        mainPage = column();
        mainPage.setClipChildren(false);
        mainPage.setClipToPadding(false);

        // Header: time + date on the left, frosted profile chip on the right (tvOS style)
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout timeCol = column();
        Date now = new Date();
        TextView time = label(DateFormat.getTimeFormat(activity).format(now), 30, Color.WHITE, true);
        timeCol.addView(time);
        TextView date = label(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now),
                12, LABEL_LIGHT_2, false);
        date.setPadding(0, dp(2), 0, 0);
        timeCol.addView(date);
        header.addView(timeCol, new LinearLayout.LayoutParams(0, -2, 1));

        // Frosted profile chip
        LinearLayout profile = new LinearLayout(activity);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(5), dp(5), dp(12), dp(5));
        profile.setBackground(shape(0x24ffffff, 22, 1, 0x2affffff));
        TextView avatar = label("A", 14, Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x55ffffff, 0x22ffffff});
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setStroke(dp(1), 0x38ffffff);
        avatar.setBackground(avatarBg);
        profile.addView(avatar, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView name = label("A Team", 12, Color.WHITE, true);
        name.setPadding(dp(8), 0, 0, 0);
        profile.addView(name);
        header.addView(profile);
        mainPage.addView(header);

        // Row 1: Lock Screen + Sleep Timer
        lockTile = new Tile(R.drawable.ic_lock_screen, "Lock Screen", "", false);
        lockTile.view.setOnClickListener(v -> openOptions("Lock Screen",
                "Lock the TV now, or choose an idle timeout for the lock screen.",
                LOCK_LABELS, LOCK_VALUES, callback.getLockTimeoutMinutes(), value -> {
                    if (value == -1) {
                        dismiss();
                        callback.onLockNow();
                    } else {
                        callback.onSetLockTimeout(value);
                    }
                }, lockTile.view));
        sleepTile = new Tile(R.drawable.ic_sleep, "Sleep Timer", "", false);
        sleepTile.view.setOnClickListener(v -> openOptions("Sleep Timer",
                "Turn the TV off after the selected time.",
                SLEEP_LABELS, SLEEP_VALUES, Math.max(0, closestSleepOption()), value -> {
                    callback.onSetSleepTimer(value);
                    if (value == -1) dismiss();
                }, sleepTile.view));
        addRow(dp(16), dp(84), lockTile.view, sleepTile.view);

        // Row 2: Standby + TV Inputs
        standbyTile = new Tile(R.drawable.ic_standby, "Standby", "", false);
        standbyTile.view.setOnClickListener(v -> openOptions("Standby",
                callback.isStandbyAvailable()
                        ? "Turn the TV off after the lock screen has been idle for this long."
                        : "Turn the TV off after the lock screen has been idle for this long.\n"
                        + "Screen will fade to sleep mode.",
                STANDBY_LABELS, STANDBY_VALUES, callback.getStandbyMinutes(),
                callback::onSetStandbyMinutes, standbyTile.view));

        View inputs = compactTile(R.drawable.ic_inputs, "Inputs", () -> {
            dismiss();
            callback.onOpenInputs();
        });
        addRow(dp(10), dp(84), standbyTile.view, inputs);

        // Row 3: Memory RAM Optimizer (wide tile)
        addRow(dp(10), dp(72), buildMemoryTile());

        // Row 4: Now Showing hero toggle (wide tile, falls back to static wallpaper when off)
        addRow(dp(10), dp(48), buildNowShowingToggle());

        // Row 5: Wallpaper Manager (wide tile)
        View wallpaper = compactTileWide(R.drawable.ic_wallpaper, "Wallpaper Manager", "Choose presets or upload", () -> {
            dismiss();
            callback.onOpenWallpaper();
        });
        addRow(dp(10), dp(54), wallpaper);

        refreshValues();
        wireRows(mainRows);
    }

    private void addRow(int topMargin, int height, View... tiles) {
        LinearLayout row = new LinearLayout(activity);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        List<View> list = new ArrayList<>();
        for (int i = 0; i < tiles.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(tiles[i], lp);
            list.add(tiles[i]);
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = topMargin;
        mainPage.addView(row, rowLp);
        mainRows.add(list);
    }

    private void refreshValues() {
        int lock = callback.getLockTimeoutMinutes();
        lockTile.setValue(lock > 0 ? "After " + formatMinutes(lock) : "Off", lock > 0);

        int sleep = callback.getRemainingSleepMinutes();
        sleepTile.setValue(sleep > 0 ? formatMinutes(sleep) + " left" : "Off", sleep > 0);

        int standby = callback.getStandbyMinutes();
        standbyTile.setValue(standby > 0 ? "After " + formatMinutes(standby) : "Never", standby > 0);
    }

    private int closestSleepOption() {
        int remaining = callback.getRemainingSleepMinutes();
        if (remaining <= 0) return 0;
        int best = 15;
        for (int v : SLEEP_VALUES) if (v > 0 && Math.abs(v - remaining) < Math.abs(best - remaining)) best = v;
        return best;
    }

    private View buildMemoryTile() {
        LinearLayout tile = baseTile();
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(14), dp(10), dp(14), dp(10));

        FrameLayout iconBg = iconCircle(R.drawable.ic_memory);
        ImageView icon = (ImageView) iconBg.getChildAt(0);
        tile.addView(iconBg, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout col = column();
        col.setPadding(dp(12), 0, dp(12), 0);
        TextView title = label("Memory", 13, Color.WHITE, true);
        col.addView(title);
        memoryValue = label("", 11, LABEL_LIGHT_2, false);
        memoryValue.setPadding(0, dp(2), 0, dp(7));
        col.addView(memoryValue);
        memoryTrack = new FrameLayout(activity);
        memoryTrack.setBackground(shape(0x28ffffff, 3, 0, 0));
        memoryFill = new View(activity);
        memoryTrack.addView(memoryFill, new FrameLayout.LayoutParams(0, -1));
        col.addView(memoryTrack, new LinearLayout.LayoutParams(-1, dp(5)));
        tile.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        TextView action = label("Free Up", 11, Color.WHITE, true);
        action.setPadding(dp(12), dp(6), dp(12), dp(6));
        action.setBackground(shape(0x2affffff, 14, 1, 0x33ffffff));
        tile.addView(action);

        MemoryStats stats = callback.getMemoryStats();
        if (stats != null) {
            memoryPercent = stats.percentUsed;
            memoryValue.setText(stats.getSummary());
        } else {
            memoryValue.setText("Unavailable");
        }
        memoryTrack.post(() -> setMemoryFill(memoryPercent, false));

        tile.setOnClickListener(v -> {
            MemoryStats freed = callback.freeUpMemory();
            if (freed == null) return;
            long mb = freed.freedBytes / (1024 * 1024);
            memoryValue.setText(mb > 0 ? "Freed " + formatSize(freed.freedBytes) + "  ·  "
                    + formatSize(freed.availBytes) + " free"
                    : "Optimized  ·  " + formatSize(freed.availBytes) + " free");
            memoryPercent = freed.percentUsed;
            setMemoryFill(memoryPercent, true);
        });

        tile.setOnFocusChangeListener((v, focused) -> {
            applyTileFocus(tile, focused);
            title.setTextColor(focused ? LABEL_DARK : Color.WHITE);
            memoryValue.setTextColor(focused ? LABEL_DARK_MUTED : LABEL_LIGHT_2);
            icon.setColorFilter(focused ? LABEL_DARK : Color.WHITE);
            iconBg.setBackground(oval(focused ? 0x14000000 : 0x24ffffff));
            memoryTrack.setBackground(shape(focused ? 0x1a000000 : 0x28ffffff, 3, 0, 0));
            action.setTextColor(focused ? Color.WHITE : Color.WHITE);
            action.setBackground(shape(focused ? LABEL_DARK : 0x2affffff, 14, 0, 0));
        });
        return tile;
    }

    private void setMemoryFill(int percent, boolean animate) {
        int color = percent < 60 ? 0xff30d158 : percent < 85 ? 0xffff9f0a : 0xffff453a;
        memoryFill.setBackground(shape(color, 3, 0, 0));
        int target = Math.round(memoryTrack.getWidth() * Math.max(2, Math.min(100, percent)) / 100f);
        ViewGroup.LayoutParams lp = memoryFill.getLayoutParams();
        if (!animate) {
            lp.width = target;
            memoryFill.setLayoutParams(lp);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofInt(lp.width, target);
        anim.setDuration(500);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            lp.width = (int) a.getAnimatedValue();
            memoryFill.setLayoutParams(lp);
        });
        anim.start();
    }

    private View compactTile(int iconRes, String text, Runnable onClick) {
        LinearLayout tile = baseTile();
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(12), dp(14), dp(11));
        FrameLayout iconBg = iconCircle(iconRes);
        ImageView icon = (ImageView) iconBg.getChildAt(0);
        tile.addView(iconBg, new LinearLayout.LayoutParams(dp(32), dp(32)));

        View spacer = new View(activity);
        tile.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        TextView title = label(text, 13, Color.WHITE, true);
        tile.addView(title);
        TextView sub = label("Switch sources", 11, LABEL_LIGHT_2, false);
        sub.setPadding(0, dp(2), 0, 0);
        tile.addView(sub);

        tile.setOnClickListener(v -> onClick.run());
        tile.setOnFocusChangeListener((v, focused) -> {
            applyTileFocus(tile, focused);
            title.setTextColor(focused ? LABEL_DARK : Color.WHITE);
            sub.setTextColor(focused ? LABEL_DARK_MUTED : LABEL_LIGHT_2);
            icon.setColorFilter(focused ? LABEL_DARK : Color.WHITE);
            iconBg.setBackground(oval(focused ? 0x14000000 : 0x24ffffff));
        });
        return tile;
    }

    private View compactTileWide(int iconRes, String text, String subText, Runnable onClick) {
        LinearLayout tile = baseTile();
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(14), 0, dp(14), 0);

        FrameLayout iconBg = iconCircle(iconRes);
        ImageView icon = (ImageView) iconBg.getChildAt(0);
        tile.addView(iconBg, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout words = column();
        words.setPadding(dp(12), 0, 0, 0);
        TextView title = label(text, 13, Color.WHITE, true);
        words.addView(title);
        TextView sub = label(subText, 11, LABEL_LIGHT_2, false);
        sub.setPadding(0, dp(1), 0, 0);
        words.addView(sub);
        tile.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView chevron = new ImageView(activity);
        chevron.setImageResource(R.drawable.ic_chevron_left);
        chevron.setRotation(180f);
        chevron.setColorFilter(LABEL_LIGHT_2);
        tile.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

        tile.setOnClickListener(v -> onClick.run());
        tile.setOnFocusChangeListener((v, focused) -> {
            applyTileFocus(tile, focused);
            title.setTextColor(focused ? LABEL_DARK : Color.WHITE);
            sub.setTextColor(focused ? LABEL_DARK_MUTED : LABEL_LIGHT_2);
            icon.setColorFilter(focused ? LABEL_DARK : Color.WHITE);
            chevron.setColorFilter(focused ? LABEL_DARK : LABEL_LIGHT_2);
            iconBg.setBackground(oval(focused ? 0x14000000 : 0x24ffffff));
        });
        return tile;
    }

    /** Wide toggle tile with an iOS-style switch. OK flips it instantly; no sub page. */
    private View buildNowShowingToggle() {
        LinearLayout tile = baseTile();
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(14), 0, dp(14), 0);

        FrameLayout iconBg = iconCircle(R.drawable.ic_wallpaper);
        ImageView icon = (ImageView) iconBg.getChildAt(0);
        tile.addView(iconBg, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout words = column();
        words.setPadding(dp(12), 0, 0, 0);
        TextView title = label("Now Showing", 13, Color.WHITE, true);
        words.addView(title);
        TextView sub = label("", 11, LABEL_LIGHT_2, false);
        sub.setPadding(0, dp(1), 0, 0);
        words.addView(sub);
        tile.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        FrameLayout track = new FrameLayout(activity);
        View knob = new View(activity);
        track.addView(knob, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER_VERTICAL));
        tile.addView(track, new LinearLayout.LayoutParams(dp(40), dp(22)));

        boolean available = callback.isNowShowingAvailable();
        final boolean[] on = {available && callback.isNowShowingEnabled()};
        final boolean[] focused = {false};
        Runnable render = () -> {
            boolean f = focused[0];
            sub.setText(!available ? "Add a TMDB API key to enable"
                    : on[0] ? "Trending on TMDB" : "Off · Static wallpaper");
            title.setTextColor(f ? LABEL_DARK : Color.WHITE);
            sub.setTextColor(f ? LABEL_DARK_MUTED : LABEL_LIGHT_2);
            icon.setColorFilter(f ? LABEL_DARK : Color.WHITE);
            iconBg.setBackground(oval(f ? 0x14000000 : on[0] ? 0x55ffffff : 0x24ffffff));
            int trackColor = on[0] ? 0xff30d158 : (f ? 0x33000000 : 0x38ffffff);
            track.setBackground(shape(trackColor, 11, 0, 0));
            knob.setBackground(oval(Color.WHITE));
            knob.animate().translationX(on[0] ? dp(20) : dp(2)).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (!f) tile.setBackground(shape(on[0] ? TILE_GLASS_ACTIVE : TILE_GLASS, 18, 1,
                    on[0] ? TILE_GLASS_ACTIVE_RIM : TILE_GLASS_RIM));
        };
        knob.setTranslationX(on[0] ? dp(20) : dp(2));
        render.run();

        tile.setOnClickListener(v -> {
            if (!available) return;
            on[0] = !on[0];
            callback.onSetNowShowing(on[0]);
            render.run();
        });
        tile.setOnFocusChangeListener((v, f) -> {
            focused[0] = f;
            applyTileFocus(tile, f);
            render.run();
        });
        return tile;
    }

    /** Tile supporting active state and focused liquid glass. */
    private class Tile {
        final LinearLayout view;
        final FrameLayout iconBg;
        final ImageView icon;
        final TextView title, value;
        boolean active, focused;

        Tile(int iconRes, String titleText, String valueText, boolean active) {
            this.active = active;
            view = baseTile();
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(14), dp(12), dp(14), dp(11));
            iconBg = iconCircle(iconRes);
            icon = (ImageView) iconBg.getChildAt(0);
            view.addView(iconBg, new LinearLayout.LayoutParams(dp(32), dp(32)));
            View spacer = new View(activity);
            view.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
            title = label(titleText, 13, Color.WHITE, true);
            view.addView(title);
            value = label(valueText, 11, LABEL_LIGHT_2, false);
            value.setPadding(0, dp(2), 0, 0);
            view.addView(value);
            view.setOnFocusChangeListener((v, f) -> {
                focused = f;
                applyTileFocus(view, f);
                render();
            });
            render();
        }

        void setValue(String text, boolean isActive) {
            value.setText(text);
            active = isActive;
            render();
        }

        void render() {
            title.setTextColor(focused ? LABEL_DARK : Color.WHITE);
            value.setTextColor(focused ? LABEL_DARK_MUTED : LABEL_LIGHT_2);
            // Frosted white indicator when active, soft dark on focus
            iconBg.setBackground(oval(focused ? 0x14000000 : active ? 0x55ffffff : 0x24ffffff));
            icon.setColorFilter(focused ? LABEL_DARK : Color.WHITE);
            if (!focused) {
                view.setBackground(shape(active ? TILE_GLASS_ACTIVE : TILE_GLASS, 18, 1,
                        active ? TILE_GLASS_ACTIVE_RIM : TILE_GLASS_RIM));
            }
        }
    }

    private LinearLayout baseTile() {
        LinearLayout tile = new LinearLayout(activity);
        tile.setId(View.generateViewId());
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setBackground(shape(TILE_GLASS, 18, 1, TILE_GLASS_RIM));
        return tile;
    }

    private void applyTileFocus(View tile, boolean focused) {
        tile.setBackground(shape(focused ? TILE_FOCUSED : TILE_GLASS, 18, focused ? 0 : 1,
                focused ? 0 : TILE_GLASS_RIM));
        tile.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                .translationZ(focused ? dp(8) : 0).setDuration(150)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private FrameLayout iconCircle(int iconRes) {
        FrameLayout bg = new FrameLayout(activity);
        bg.setBackground(oval(0x24ffffff));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        bg.addView(icon, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        return bg;
    }

    // ------------------------------------------------------------------ sub pages

    interface OnPickOption {
        void pick(int value);
    }

    private void openOptions(String titleText, String description, String[] labels, int[] values,
                             int current, OnPickOption onPick, View returnTo) {
        LinearLayout page = column();
        page.setClipChildren(false);
        page.setClipToPadding(false);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(activity);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(LABEL_LIGHT_2);
        header.addView(back, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView title = label(titleText, 20, Color.WHITE, true);
        title.setPadding(dp(6), 0, 0, 0);
        header.addView(title);
        page.addView(header);

        TextView desc = label(description, 12, LABEL_LIGHT_2, false);
        desc.setPadding(dp(2), dp(6), 0, dp(12));
        desc.setLineSpacing(dp(2), 1f);
        page.addView(desc);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout list = column();
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(dp(4), dp(2), dp(4), dp(4));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        List<View> rows = new ArrayList<>();
        View selected = null;
        for (int i = 0; i < labels.length; i++) {
            int value = values[i];
            boolean isSelected = value == current;
            View row = optionRow(labels[i], isSelected, () -> {
                onPick.pick(value);
                if (isShowing()) {
                    refreshValues();
                    showMainPage(returnTo);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
            if (i > 0) lp.topMargin = dp(6);
            list.addView(row, lp);
            rows.add(row);
            if (isSelected) selected = row;
        }
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = label("Press Back to return", 11, 0x66ffffff, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);
        page.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        View focusTarget = selected != null ? selected : rows.get(0);
        swapPage(page, true);
        focusTarget.post(focusTarget::requestFocus);
    }

    private View optionRow(String text, boolean selected, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setClickable(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(14), 0);
        row.setBackground(shape(0x20ffffff, 12, 1, 0x22ffffff));
        TextView label = label(text, 14, Color.WHITE, false);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView check = new ImageView(activity);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(Color.WHITE);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.setOnClickListener(v -> onClick.run());
        row.setOnFocusChangeListener((v, focused) -> {
            row.setBackground(shape(focused ? TILE_FOCUSED : 0x20ffffff, 12, focused ? 0 : 1,
                    focused ? 0 : 0x22ffffff));
            label.setTextColor(focused ? LABEL_DARK : Color.WHITE);
            label.setTypeface(Typeface.create(focused ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            check.setColorFilter(focused ? LABEL_DARK : Color.WHITE);
            row.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f)
                    .translationZ(focused ? dp(6) : 0).setDuration(140).start();
        });
        return row;
    }

    private void showMainPage(View focusTarget) {
        swapPage(mainPage, false);
        View target = focusTarget != null ? focusTarget : mainRows.get(0).get(0);
        target.post(target::requestFocus);
    }

    private void swapPage(View incoming, boolean forward) {
        View outgoing = subPage != null && subPage.getParent() != null ? subPage : mainPage;
        if (incoming == outgoing) return;
        if (incoming != mainPage && incoming.getParent() == null) {
            pageHost.addView(incoming, new FrameLayout.LayoutParams(-1, -2));
        }
        int shift = dp(28) * (forward ? 1 : -1);
        incoming.setVisibility(View.VISIBLE);
        incoming.setAlpha(0f);
        incoming.setTranslationX(shift);
        incoming.animate().alpha(1f).translationX(0).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
        outgoing.animate().cancel();
        outgoing.setAlpha(1f);
        outgoing.setVisibility(View.GONE);
        if (outgoing != mainPage) pageHost.removeView(outgoing);
        subPage = incoming == mainPage ? null : incoming;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (subPage != null) {
                if (event.getAction() == KeyEvent.ACTION_UP) showMainPage(null);
                return true;
            } else {
                if (event.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        dismiss();
    }

    @Override
    public void dismiss() {
        if (isShowing() && !isDismissing && !activity.isFinishing()) {
            dismissAnimated();
        } else {
            super.dismiss();
        }
    }

    public void dismissAnimated() {
        if (isDismissing) return;
        isDismissing = true;
        // Slide smoothly back up into the header button
        panelContainer.animate()
                .alpha(0f)
                .translationY(-dp(120))
                .translationX(dp(28))
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(220)
                .setInterpolator(new AccelerateInterpolator(1.8f))
                .withEndAction(ControlCenterDialog.super::dismiss)
                .start();
    }

    // ------------------------------------------------------------------ helpers

    /** D-pad wiring across rows with different column counts. */
    static void wireRows(List<List<View>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            List<View> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                View v = row.get(c);
                v.setNextFocusLeftId(row.get(Math.max(0, c - 1)).getId());
                v.setNextFocusRightId(row.get(Math.min(row.size() - 1, c + 1)).getId());
                v.setNextFocusUpId(r == 0 ? v.getId() : target(rows.get(r - 1), c, row.size()).getId());
                v.setNextFocusDownId(r == rows.size() - 1 ? v.getId()
                        : target(rows.get(r + 1), c, row.size()).getId());
            }
        }
    }

    private static View target(List<View> row, int column, int sourceCount) {
        int capacity = Math.max(row.size(), 1);
        int index = sourceCount <= 1 ? 0 : column * capacity / sourceCount;
        return row.get(Math.min(index, row.size() - 1));
    }

    static String formatMinutes(int minutes) {
        if (minutes < 60) return minutes + " min";
        if (minutes % 60 == 0) return (minutes / 60) + " hr";
        return (minutes / 60) + " hr " + (minutes % 60) + " min";
    }

    static String formatSize(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        return Math.round(mb) + " MB";
    }

    private boolean blurSupported() {
        if (Build.VERSION.SDK_INT < 31) return false;
        WindowManager wm = activity.getSystemService(WindowManager.class);
        return wm != null && wm.isCrossWindowBlurEnabled();
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView label(String text, int size, int color, boolean medium) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setFontFeatureSettings("tnum");
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeWidth > 0) d.setStroke(dp(strokeWidth), strokeColor);
        return d;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
}
