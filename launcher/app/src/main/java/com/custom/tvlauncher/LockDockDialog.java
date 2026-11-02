package com.custom.tvlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Apple TV / tvOS-inspired Liquid Glass Dialog for customizing the Lock Screen Dock.
 * Displays app actions (Move, Replace, Add, Remove) and a visual app picker grid with icons.
 */
public class LockDockDialog extends Dialog {

    public interface ActionCallback {
        void onMoveLeft();
        void onMoveRight();
        void onReplace();
        void onAddAfter();
        void onRemove();
    }

    public interface AppSelectCallback {
        void onAppSelected(MainActivity.App app);
    }

    private final Activity activity;
    private final FrameLayout rootFrame;
    private final LinearLayout panel;

    public LockDockDialog(Activity activity) {
        super(activity);
        this.activity = activity;
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
            window.setWindowAnimations(0);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.55f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(attrs);
        }

        rootFrame = new FrameLayout(activity);
        rootFrame.setBackgroundColor(0x66000000);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(26), dp(22), dp(26), dp(22));
        panel.setBackground(glass(0xf0161a24, 24, 1, 0x33ffffff));

        FrameLayout.LayoutParams panelLP = new FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER);
        rootFrame.addView(panel, panelLP);
        setContentView(rootFrame);
    }

    public static void showActionMenu(Activity activity, MainActivity.App app, Drawable icon,
                                      int index, int total, int max, ActionCallback callback) {
        LockDockDialog dialog = new LockDockDialog(activity);
        LinearLayout panel = dialog.panel;

        // Header: App icon + Name + Subtitle
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView appIcon = new ImageView(activity);
        appIcon.setImageDrawable(icon);
        header.addView(appIcon, new LinearLayout.LayoutParams(dialog.dp(44), dialog.dp(44)));

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dialog.dp(14), 0, 0, 0);

        TextView title = new TextView(activity);
        title.setText(app.name);
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleCol.addView(title);

        TextView sub = new TextView(activity);
        sub.setText("Lock Screen Quick Launch App");
        sub.setTextSize(11);
        sub.setTextColor(0x99ffffff);
        sub.setPadding(0, dialog.dp(2), 0, 0);
        titleCol.addView(sub);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(header);

        // Divider
        View div = new View(activity);
        div.setBackgroundColor(0x22ffffff);
        LinearLayout.LayoutParams divLP = new LinearLayout.LayoutParams(-1, dialog.dp(1));
        divLP.topMargin = dialog.dp(16);
        divLP.bottomMargin = dialog.dp(14);
        panel.addView(div, divLP);

        // Options List
        List<View> optionViews = new ArrayList<>();

        if (index > 0) {
            optionViews.add(dialog.actionRow("⬅️  Move Left", () -> {
                dialog.dismiss();
                callback.onMoveLeft();
            }));
        }
        if (index < total - 1) {
            optionViews.add(dialog.actionRow("➡️  Move Right", () -> {
                dialog.dismiss();
                callback.onMoveRight();
            }));
        }
        optionViews.add(dialog.actionRow("🔄  Replace with Another App", () -> {
            dialog.dismiss();
            callback.onReplace();
        }));
        if (total < max) {
            optionViews.add(dialog.actionRow("➕  Add App After This", () -> {
                dialog.dismiss();
                callback.onAddAfter();
            }));
        }
        optionViews.add(dialog.actionRow("🗑️  Remove from Lock Screen", () -> {
            dialog.dismiss();
            callback.onRemove();
        }));

        for (int i = 0; i < optionViews.size(); i++) {
            View v = optionViews.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dialog.dp(42));
            if (i > 0) lp.topMargin = dialog.dp(8);
            panel.addView(v, lp);
        }

        // Wire vertical focus
        for (int i = 0; i < optionViews.size(); i++) {
            View v = optionViews.get(i);
            v.setNextFocusUpId(optionViews.get(Math.max(0, i - 1)).getId());
            v.setNextFocusDownId(optionViews.get(Math.min(optionViews.size() - 1, i + 1)).getId());
            v.setNextFocusLeftId(v.getId());
            v.setNextFocusRightId(v.getId());
        }

        TextView backHint = new TextView(activity);
        backHint.setText("Press Back to cancel");
        backHint.setTextSize(11);
        backHint.setTextColor(0x66ffffff);
        backHint.setGravity(Gravity.CENTER);
        backHint.setPadding(0, dialog.dp(14), 0, 0);
        panel.addView(backHint);

        dialog.show();
        if (!optionViews.isEmpty()) optionViews.get(0).requestFocus();
    }

    public static void showAppPickerGrid(Activity activity, String titleText, List<MainActivity.App> candidates,
                                         AppSelectCallback callback) {
        LockDockDialog dialog = new LockDockDialog(activity);
        LinearLayout panel = dialog.panel;
        FrameLayout.LayoutParams panelLP = (FrameLayout.LayoutParams) panel.getLayoutParams();
        panelLP.width = dialog.dp(680);
        panel.setLayoutParams(panelLP);

        TextView header = new TextView(activity);
        header.setText(titleText);
        header.setTextSize(20);
        header.setTextColor(Color.WHITE);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(header);

        TextView sub = new TextView(activity);
        sub.setText("Choose an app to add to the lock screen dock");
        sub.setTextSize(12);
        sub.setTextColor(0x99ffffff);
        sub.setPadding(0, dialog.dp(3), 0, dialog.dp(14));
        panel.addView(sub);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);

        final int cols = 4;
        List<List<View>> rows = new ArrayList<>();
        List<View> currentRow = null;

        for (int i = 0; i < candidates.size(); i++) {
            if (i % cols == 0) {
                currentRow = new ArrayList<>();
                rows.add(currentRow);
                LinearLayout rowLayout = new LinearLayout(activity);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setClipChildren(false);
                rowLayout.setClipToPadding(false);
                LinearLayout.LayoutParams rowLP = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rowLP.topMargin = dialog.dp(10);
                grid.addView(rowLayout, rowLP);
            }

            MainActivity.App app = candidates.get(i);
            View card = dialog.appGridCard(app, () -> {
                dialog.dismiss();
                callback.onAppSelected(app);
            });
            LinearLayout rowLayout = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
            LinearLayout.LayoutParams cardLP = new LinearLayout.LayoutParams(0, dialog.dp(70), 1);
            if (i % cols > 0) cardLP.leftMargin = dialog.dp(10);
            rowLayout.addView(card, cardLP);
            currentRow.add(card);
        }

        // Wire grid D-pad focus
        for (int r = 0; r < rows.size(); r++) {
            List<View> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                View v = row.get(c);
                v.setNextFocusLeftId(c > 0 ? row.get(c - 1).getId() : v.getId());
                v.setNextFocusRightId(c < row.size() - 1 ? row.get(c + 1).getId() : v.getId());
                if (r > 0) {
                    List<View> above = rows.get(r - 1);
                    v.setNextFocusUpId(above.get(Math.min(c, above.size() - 1)).getId());
                } else {
                    v.setNextFocusUpId(v.getId());
                }
                if (r < rows.size() - 1) {
                    List<View> below = rows.get(r + 1);
                    v.setNextFocusDownId(below.get(Math.min(c, below.size() - 1)).getId());
                } else {
                    v.setNextFocusDownId(v.getId());
                }
            }
        }

        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLP = new LinearLayout.LayoutParams(-1, dialog.dp(300));
        panel.addView(scroll, scrollLP);

        TextView cancelHint = new TextView(activity);
        cancelHint.setText("Press Back to cancel");
        cancelHint.setTextSize(11);
        cancelHint.setTextColor(0x66ffffff);
        cancelHint.setGravity(Gravity.CENTER);
        cancelHint.setPadding(0, dialog.dp(12), 0, 0);
        panel.addView(cancelHint);

        dialog.show();
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            rows.get(0).get(0).requestFocus();
        }
    }

    private View actionRow(String text, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setClickable(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackground(glass(0x22ffffff, 12, 1, 0x24ffffff));

        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(Color.WHITE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

        row.setOnClickListener(v -> onClick.run());
        row.setOnFocusChangeListener((v, focused) -> {
            row.setBackground(glass(focused ? 0xfff5f5f7 : 0x22ffffff, 12,
                    focused ? 0 : 1, focused ? 0 : 0x24ffffff));
            label.setTextColor(focused ? 0xff141720 : Color.WHITE);
            label.setTypeface(Typeface.create(focused ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            row.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f)
                    .translationZ(focused ? dp(6) : 0).setDuration(140).start();
        });
        return row;
    }

    private View appGridCard(MainActivity.App app, Runnable onClick) {
        LinearLayout card = new LinearLayout(activity);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(glass(0x24ffffff, 14, 1, 0x28ffffff));

        ImageView icon = new ImageView(activity);
        try {
            icon.setImageDrawable(app.info.loadIcon(activity.getPackageManager()));
        } catch (Exception ignored) {}
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView name = new TextView(activity);
        name.setText(app.name);
        name.setTextSize(12);
        name.setTextColor(Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(10), 0, 0, 0);
        card.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        card.setOnClickListener(v -> onClick.run());
        card.setOnFocusChangeListener((v, focused) -> {
            card.setBackground(glass(focused ? 0xfff5f5f7 : 0x24ffffff, 14,
                    focused ? 0 : 1, focused ? 0 : 0x28ffffff));
            name.setTextColor(focused ? 0xff141720 : Color.WHITE);
            name.setTypeface(Typeface.create(focused ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            card.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                    .translationZ(focused ? dp(8) : 0).setDuration(150).start();
        });
        return card;
    }

    private GradientDrawable glass(int color, int radius, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke > 0) d.setStroke(dp(stroke), strokeColor);
        return d;
    }

    private int dp(float val) {
        return Math.round(val * activity.getResources().getDisplayMetrics().density);
    }
}
