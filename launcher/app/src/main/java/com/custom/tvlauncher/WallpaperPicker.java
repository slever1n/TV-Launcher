package com.custom.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Modern wallpaper picker supporting 12 built-in presets,
 * Google Photos import, and local phone/PC Wi-Fi uploading.
 * Features Apple TV frosted liquid glass styling and rock-solid D-pad navigation.
 */
public class WallpaperPicker extends Dialog {
    public static final String[] PRESET_IDS = {
            "venice", "aurora_silk", "ember_flow",
            "alpine_dawn", "coastal_light", "desert_dusk",
            "cosmic_nebula", "emerald_forest", "tokyo_nights",
            "ocean_azure", "golden_canyon", "midnight_minimal"
    };

    public static final String[] PRESET_NAMES = {
            "Venice Sunset", "Aurora Silk", "Ember Flow",
            "Alpine Dawn", "Coastal Light", "Desert Dusk",
            "Cosmic Nebula", "Emerald Forest", "Tokyo Nights",
            "Ocean Azure", "Golden Canyon", "Midnight Minimal"
    };

    public static final int[] PRESET_RESOURCES = {
            R.drawable.wallpaper_venice, R.drawable.wallpaper_aurora_silk, R.drawable.wallpaper_ember_flow,
            R.drawable.wallpaper_alpine_dawn, R.drawable.wallpaper_coastal_light, R.drawable.wallpaper_desert_dusk,
            R.drawable.wallpaper_cosmic_nebula, R.drawable.wallpaper_emerald_forest, R.drawable.wallpaper_tokyo_nights,
            R.drawable.wallpaper_ocean_azure, R.drawable.wallpaper_golden_canyon, R.drawable.wallpaper_midnight_minimal
    };

    public interface OnWallpaperChosenListener {
        void onPresetChosen(int index);
        void onCustomChosen(File file);
        void onOpenGooglePhotos();
        void onOpenWifiUpload();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final OnWallpaperChosenListener listener;
    private final List<View> focusableItems = new ArrayList<>();
    /** Visual rows (action buttons, custom rows, preset rows) used for D-pad wiring. */
    private final List<List<View>> rows = new ArrayList<>();
    private View initialFocus;
    private final int dialogWidth;
    private final int dialogHeight;
    private final int columns = 4;
    private final boolean isCustomSaved;
    private final String currentSavedPath;
    private final int currentSavedPreset;
    private ScrollView scroll;

    public static int savedIndex(SharedPreferences preferences) {
        String value = preferences.getString("wallpaper_id", "venice");
        for (int i = 0; i < PRESET_IDS.length; i++) {
            if (PRESET_IDS[i].equals(value)) return i;
        }
        return 0;
    }

    public static String savedCustomPath(SharedPreferences preferences) {
        return preferences.getString("wallpaper_custom_path", "");
    }

    public static boolean isCustomSelected(SharedPreferences preferences) {
        return preferences.getBoolean("wallpaper_is_custom", false) && !savedCustomPath(preferences).isEmpty();
    }

    public static int resource(int index) {
        return PRESET_RESOURCES[Math.max(0, Math.min(index, PRESET_RESOURCES.length - 1))];
    }

    public WallpaperPicker(Activity activity, SharedPreferences preferences, OnWallpaperChosenListener listener) {
        super(activity);
        this.activity = activity;
        this.preferences = preferences;
        this.listener = listener;

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        isCustomSaved = isCustomSelected(preferences);
        currentSavedPath = savedCustomPath(preferences);
        currentSavedPreset = savedIndex(preferences);

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        dialogWidth = Math.min(dp(940), screenWidth - dp(48));
        dialogHeight = Math.min(dp(540), screenHeight - dp(32));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26), dp(20), dp(26), dp(16));
        // Frosted liquid glass background with specular border
        root.setBackground(glass(0xec141720, 24, 1, 0x33ffffff));

        // Header Title
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Choose Wallpaper");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView countBadge = new TextView(activity);
        countBadge.setText(PRESET_IDS.length + " PRESETS");
        countBadge.setTextSize(10);
        countBadge.setTextColor(0xd9ffffff);
        countBadge.setLetterSpacing(0.12f);
        countBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        countBadge.setBackground(glass(0x28ffffff, 12, 1, 0x33ffffff));
        header.addView(countBadge);

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(activity);
        subtitle.setText("Select to apply  ·  Your choice persists across TV reboots");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0x99ffffff);
        subtitle.setPadding(0, dp(3), 0, dp(12));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        // Scrollable container for action buttons and wallpapers
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout contentContainer = new LinearLayout(activity);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setClipChildren(false);
        contentContainer.setClipToPadding(false);
        contentContainer.setPadding(dp(4), dp(4), dp(4), dp(4));

        // Action Buttons Row: [Google Photos / Pick Image]  [Upload from Phone or PC]
        LinearLayout actionsRow = new LinearLayout(activity);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setClipChildren(false);
        actionsRow.setClipToPadding(false);
        actionsRow.setPadding(0, 0, 0, dp(14));

        View btnPhotos = createActionButton(R.drawable.ic_photos, "Google Photos / Gallery", () -> {
            dismiss();
            listener.onOpenGooglePhotos();
        });
        actionsRow.addView(btnPhotos, new LinearLayout.LayoutParams(0, dp(38), 1));

        View spacer = new View(activity);
        actionsRow.addView(spacer, new LinearLayout.LayoutParams(dp(12), dp(38)));

        View btnUpload = createActionButton(R.drawable.ic_upload, "Upload from Phone or PC", () -> {
            dismiss();
            listener.onOpenWifiUpload();
        });
        actionsRow.addView(btnUpload, new LinearLayout.LayoutParams(0, dp(38), 1));
        rows.add(java.util.Arrays.asList(btnPhotos, btnUpload));

        contentContainer.addView(actionsRow, new LinearLayout.LayoutParams(-1, -2));

        // Custom wallpapers first if any exist
        File customDir = new File(activity.getFilesDir(), "custom_wallpapers");
        List<File> customFiles = new ArrayList<>();
        if (customDir.exists()) {
            File[] files = customDir.listFiles((dir, name) -> {
                String l = name.toLowerCase();
                return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp");
            });
            if (files != null) {
                for (File f : files) customFiles.add(f);
                customFiles.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
        }

        if (!customFiles.isEmpty()) {
            TextView customSection = new TextView(activity);
            customSection.setText("CUSTOM UPLOADS  (Hold OK to delete)");
            customSection.setTextSize(10);
            customSection.setTextColor(0xd9ffffff);
            customSection.setLetterSpacing(0.12f);
            customSection.setPadding(dp(2), dp(4), 0, dp(8));
            contentContainer.addView(customSection, new LinearLayout.LayoutParams(-1, -2));

            buildCustomGrid(contentContainer, customFiles);

            TextView presetSection = new TextView(activity);
            presetSection.setText("PRESET WALLPAPERS");
            presetSection.setTextSize(10);
            presetSection.setTextColor(0x99ffffff);
            presetSection.setLetterSpacing(0.12f);
            presetSection.setPadding(dp(2), dp(12), 0, dp(8));
            contentContainer.addView(presetSection, new LinearLayout.LayoutParams(-1, -2));
        }

        buildPresetGrid(contentContainer);

        scroll.addView(contentContainer, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView backHint = new TextView(activity);
        backHint.setText("Press Back to cancel");
        backHint.setTextSize(11);
        backHint.setTextColor(0x80ffffff);
        backHint.setGravity(Gravity.CENTER);
        backHint.setPadding(0, dp(8), 0, 0);
        root.addView(backHint, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);

        // Robust D-Pad navigation
        wireGridNavigation();

        setOnShowListener(d -> {
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(dialogWidth, dialogHeight);
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            View target = initialFocus != null ? initialFocus
                    : rows.size() > 1 && !rows.get(1).isEmpty() ? rows.get(1).get(0)
                    : !focusableItems.isEmpty() ? focusableItems.get(0) : null;
            if (target != null) target.requestFocus();
        });
    }

    private View createActionButton(int iconRes, String text, Runnable onClick) {
        LinearLayout btn = new LinearLayout(activity);
        btn.setId(View.generateViewId());
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), 0, dp(14), 0);
        btn.setBackground(glass(0x28ffffff, 12, 1, 0x2affffff));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        btn.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(Color.WHITE);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(8), 0, 0, 0);
        btn.addView(label);

        btn.setOnClickListener(v -> onClick.run());

        btn.setOnFocusChangeListener((v, focused) -> {
            btn.setBackground(glass(focused ? Color.WHITE : 0x28ffffff, 12, 1,
                    focused ? Color.WHITE : 0x2affffff));
            icon.setColorFilter(focused ? Color.rgb(20, 24, 32) : Color.WHITE);
            label.setTextColor(focused ? Color.rgb(20, 24, 32) : Color.WHITE);
            v.animate().scaleX(focused ? 1.03f : 1).scaleY(focused ? 1.03f : 1).setDuration(120).start();
            if (focused) scrollIntoView(v);
        });

        focusableItems.add(btn);
        return btn;
    }

    private int cardWidth() {
        return (dialogWidth - dp(64) - dp(12) * (columns - 1)) / columns;
    }

    private void buildPresetGrid(LinearLayout container) {
        int cardWidth = cardWidth();
        int totalRows = (int) Math.ceil((double) PRESET_IDS.length / columns);

        for (int r = 0; r < totalRows; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            List<View> rowItems = new ArrayList<>();

            for (int c = 0; c < columns; c++) {
                int index = r * columns + c;
                if (index < PRESET_IDS.length) {
                    View card = createPresetCard(index, cardWidth);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, -2);
                    if (c > 0) lp.leftMargin = dp(12);
                    lp.bottomMargin = dp(10);
                    row.addView(card, lp);
                    focusableItems.add(card);
                    rowItems.add(card);
                } else {
                    View placeholder = new View(activity);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, dp(1));
                    if (c > 0) lp.leftMargin = dp(12);
                    row.addView(placeholder, lp);
                }
            }
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (!rowItems.isEmpty()) rows.add(rowItems);
        }
    }

    private View createPresetCard(int index, int cardWidth) {
        boolean selected = !isCustomSaved && currentSavedPreset == index;

        LinearLayout card = new LinearLayout(activity);
        card.setId(View.generateViewId());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(glass(selected ? 0x40ffffff : Color.TRANSPARENT, 14,
                selected ? 2 : 1, selected ? 0xffffffff : 0x1affffff));
        if (selected) initialFocus = card;

        FrameLayout thumbContainer = new FrameLayout(activity);
        thumbContainer.setBackground(glass(0xff181a24, 10, 0, 0));
        thumbContainer.setClipToOutline(true);

        ImageView preview = new ImageView(activity);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 4;
        preview.setImageBitmap(BitmapFactory.decodeResource(activity.getResources(), PRESET_RESOURCES[index], opts));
        thumbContainer.addView(preview, new FrameLayout.LayoutParams(-1, Math.round((cardWidth - dp(8)) * 9f / 16f)));

        card.addView(thumbContainer, new LinearLayout.LayoutParams(-1, -2));

        TextView label = new TextView(activity);
        label.setText(PRESET_NAMES[index]);
        label.setTextSize(11);
        label.setTextColor(selected ? Color.WHITE : 0xccffffff);
        label.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(7), 0, dp(3));
        card.addView(label, new LinearLayout.LayoutParams(-1, -2));

        card.setOnFocusChangeListener((v, focused) -> {
            card.setBackground(glass(focused ? 0x55ffffff : (selected ? 0x40ffffff : Color.TRANSPARENT),
                    14, focused ? 2 : (selected ? 2 : 1),
                    focused ? Color.WHITE : (selected ? 0xffffffff : 0x1affffff)));
            card.animate().scaleX(focused ? 1.04f : 1).scaleY(focused ? 1.04f : 1).setDuration(120).start();
            label.setTextColor(focused || selected ? Color.WHITE : 0xccffffff);
            if (focused) scrollIntoView(v);
        });

        card.setOnClickListener(v -> {
            preferences.edit()
                    .putString("wallpaper_id", PRESET_IDS[index])
                    .putBoolean("wallpaper_is_custom", false)
                    .remove("wallpaper_custom_path")
                    .apply();
            listener.onPresetChosen(index);
            dismiss();
        });

        return card;
    }

    private void buildCustomGrid(LinearLayout container, List<File> files) {
        int cardWidth = cardWidth();
        int totalRows = (int) Math.ceil((double) files.size() / columns);

        for (int r = 0; r < totalRows; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            List<View> rowItems = new ArrayList<>();

            for (int c = 0; c < columns; c++) {
                int index = r * columns + c;
                if (index < files.size()) {
                    File file = files.get(index);
                    View card = createCustomCard(file, cardWidth);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, -2);
                    if (c > 0) lp.leftMargin = dp(12);
                    lp.bottomMargin = dp(10);
                    row.addView(card, lp);
                    focusableItems.add(card);
                    rowItems.add(card);
                } else {
                    View placeholder = new View(activity);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, dp(1));
                    if (c > 0) lp.leftMargin = dp(12);
                    row.addView(placeholder, lp);
                }
            }
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (!rowItems.isEmpty()) rows.add(rowItems);
        }
    }

    private View createCustomCard(File file, int cardWidth) {
        boolean selected = isCustomSaved && file.getAbsolutePath().equals(currentSavedPath);

        LinearLayout card = new LinearLayout(activity);
        card.setId(View.generateViewId());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(glass(selected ? 0x40ffffff : Color.TRANSPARENT, 14,
                selected ? 2 : 1, selected ? 0xffffffff : 0x1affffff));
        if (selected) initialFocus = card;

        FrameLayout thumbContainer = new FrameLayout(activity);
        thumbContainer.setBackground(glass(0xff181a24, 10, 0, 0));
        thumbContainer.setClipToOutline(true);

        ImageView preview = new ImageView(activity);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 4;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bmp != null) preview.setImageBitmap(bmp);
        thumbContainer.addView(preview, new FrameLayout.LayoutParams(-1, Math.round((cardWidth - dp(8)) * 9f / 16f)));

        TextView badge = new TextView(activity);
        badge.setText("CUSTOM");
        badge.setTextSize(8);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        badge.setBackground(glass(0x80000000, 6, 1, 0x33ffffff));
        FrameLayout.LayoutParams badgeLP = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        badgeLP.setMargins(0, dp(4), dp(4), 0);
        thumbContainer.addView(badge, badgeLP);

        card.addView(thumbContainer, new LinearLayout.LayoutParams(-1, -2));

        TextView label = new TextView(activity);
        String name = file.getName();
        if (name.length() > 14) name = name.substring(0, 11) + "...";
        label.setText(name);
        label.setTextSize(11);
        label.setTextColor(selected ? Color.WHITE : 0xccffffff);
        label.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(7), 0, dp(3));
        card.addView(label, new LinearLayout.LayoutParams(-1, -2));

        card.setOnFocusChangeListener((v, focused) -> {
            card.setBackground(glass(focused ? 0x55ffffff : (selected ? 0x40ffffff : Color.TRANSPARENT),
                    14, focused ? 2 : (selected ? 2 : 1),
                    focused ? Color.WHITE : (selected ? 0xffffffff : 0x1affffff)));
            card.animate().scaleX(focused ? 1.04f : 1).scaleY(focused ? 1.04f : 1).setDuration(120).start();
            label.setTextColor(focused || selected ? Color.WHITE : 0xccffffff);
            if (focused) scrollIntoView(v);
        });

        card.setOnClickListener(v -> {
            preferences.edit()
                    .putBoolean("wallpaper_is_custom", true)
                    .putString("wallpaper_custom_path", file.getAbsolutePath())
                    .apply();
            listener.onCustomChosen(file);
            dismiss();
        });

        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(activity)
                    .setTitle("Delete Custom Wallpaper")
                    .setMessage("Do you want to remove this wallpaper from your TV?")
                    .setPositiveButton("Delete", (d, w) -> {
                        file.delete();
                        if (file.getAbsolutePath().equals(savedCustomPath(preferences))) {
                            preferences.edit().putBoolean("wallpaper_is_custom", false).apply();
                        }
                        Toast.makeText(activity, "Wallpaper removed", Toast.LENGTH_SHORT).show();
                        dismiss();
                        new WallpaperPicker(activity, preferences, listener).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        return card;
    }

    private void scrollIntoView(View v) {
        if (scroll != null && v != null) {
            v.post(() -> {
                Rect rect = new Rect();
                v.getDrawingRect(rect);
                scroll.requestChildRectangleOnScreen(v, rect, false);
            });
        }
    }

    /**
     * Rock-solid D-pad navigation that never traps focus:
     * - Horizontal moves naturally between items in the same row.
     * - Vertical moves proportionately between rows of different column counts.
     */
    private void wireGridNavigation() {
        for (int r = 0; r < rows.size(); r++) {
            List<View> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                View v = row.get(c);

                // Horizontal (only wire when neighbor exists; never wire to self)
                if (c > 0) {
                    v.setNextFocusLeftId(row.get(c - 1).getId());
                }
                if (c < row.size() - 1) {
                    v.setNextFocusRightId(row.get(c + 1).getId());
                }

                // Up navigation
                if (r > 0) {
                    List<View> prevRow = rows.get(r - 1);
                    if (!prevRow.isEmpty()) {
                        int targetCol = Math.min((c * prevRow.size()) / row.size(), prevRow.size() - 1);
                        v.setNextFocusUpId(prevRow.get(targetCol).getId());
                    }
                }

                // Down navigation
                if (r < rows.size() - 1) {
                    List<View> nextRow = rows.get(r + 1);
                    if (!nextRow.isEmpty()) {
                        int targetCol = Math.min((c * nextRow.size()) / row.size(), nextRow.size() - 1);
                        v.setNextFocusDownId(nextRow.get(targetCol).getId());
                    }
                }
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable glass(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeWidth > 0) d.setStroke(dp(strokeWidth), strokeColor);
        return d;
    }
}
