package com.custom.tvlauncher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Only observes local Android media sessions while the launcher is visible. */
public class MediaPanel extends FrameLayout {
    private final Activity activity;
    private final MediaSessionManager manager;
    private final ComponentName accessComponent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView artwork;
    private final TextView eyebrow, title, artist, idleAction;
    private final ProgressBar progress;
    private final LinearLayout details, controls;
    private final TransportButton previous, playPause, next;
    private MediaController controller;
    private boolean listening, started, access;
    private boolean homeVisible = true;
    private Runnable onHidden;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = sessions -> choose(sessions);
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { render(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (started) handler.postDelayed(this, 1000);
        }
    };

    public MediaPanel(android.content.Context context) {
        super(context);
        this.activity = (Activity) context;
        Activity activity = this.activity;
        manager = activity.getSystemService(MediaSessionManager.class);
        accessComponent = new ComponentName(activity, MediaAccessService.class);
        setBackground(glass(0x80313744, 18, 0x35ffffff));
        setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout body = new LinearLayout(activity);
        body.setGravity(Gravity.CENTER_VERTICAL);
        artwork = new ImageView(activity);
        artwork.setBackground(glass(0xff263146, 10, 0));
        artwork.setClipToOutline(true);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(artwork, new LinearLayout.LayoutParams(dp(76), dp(76)));

        details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(dp(13), dp(3), dp(8), dp(3));
        details.setFocusable(true);
        details.setId(View.generateViewId());
        details.setOnClickListener(v -> openSource());
        details.setOnFocusChangeListener((v, focused) -> details.setBackground(
                glass(focused ? 0x30ffffff : Color.TRANSPARENT, 8, focused ? 0x80ffffff : 0)));
        eyebrow = text("NOW PLAYING", 8, 0xffc3c9d5);
        eyebrow.setLetterSpacing(.17f);
        title = text("", 16, Color.WHITE);
        title.setPadding(0, dp(6), 0, 0);
        artist = text("", 11, 0xffd0d4df);
        artist.setPadding(0, dp(4), 0, dp(8));
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffedf0f7));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x45ffffff));
        details.addView(eyebrow);
        details.addView(title);
        details.addView(artist);
        details.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        body.addView(details, new LinearLayout.LayoutParams(0, -1, 1));

        controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        previous = new TransportButton(-1, "Previous track", () -> transport(-1));
        playPause = new TransportButton(0, "Play", () -> transport(0));
        next = new TransportButton(1, "Next track", () -> transport(1));
        for (TransportButton button : new TransportButton[]{previous, playPause, next}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(38));
            lp.leftMargin = dp(3);
            controls.addView(button, lp);
        }
        body.addView(controls);
        idleAction = text("Enable", 12, Color.WHITE);
        idleAction.setId(View.generateViewId());
        idleAction.setFocusable(true);
        idleAction.setGravity(Gravity.CENTER);
        idleAction.setPadding(dp(14), dp(12), dp(14), dp(12));
        idleAction.setBackground(glass(0x38ffffff, 22, 0x25ffffff));
        idleAction.setOnFocusChangeListener((v, focused) -> idleAction.setBackground(
                glass(focused ? 0x65ffffff : 0x38ffffff, 22, focused ? Color.WHITE : 0x25ffffff)));
        idleAction.setOnClickListener(v -> { if (access) openMusic(); else openAccessSettings(); });
        body.addView(idleAction);
        addView(body, new FrameLayout.LayoutParams(-1, -1));
        render();
    }

    public void start() {
        stop();
        started = true;
        access = hasAccess();
        if (access && manager != null) {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsChanged, accessComponent, handler);
                listening = true;
                refresh();
            } catch (SecurityException ignored) { access = false; render(); }
        } else render();
        tick.run();
    }

    public void stop() {
        started = false;
        handler.removeCallbacks(tick);
        if (manager != null && listening) manager.removeOnActiveSessionsChangedListener(sessionsChanged);
        listening = false;
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
    }

    private boolean hasAccess() {
        String enabled = Settings.Secure.getString(activity.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        for (String item : enabled.split(":")) {
            if (accessComponent.equals(ComponentName.unflattenFromString(item))) return true;
        }
        return false;
    }

    private void refresh() {
        if (!started || manager == null) return;
        try { choose(manager.getActiveSessions(accessComponent)); }
        catch (SecurityException ignored) {
            access = false;
            if (controller != null) controller.unregisterCallback(callback);
            controller = null;
            render();
        }
    }

    private void choose(List<MediaController> sessions) {
        MediaController best = null;
        int score = 0;
        if (sessions != null) for (MediaController candidate : sessions) {
            PlaybackState state = candidate.getPlaybackState();
            MediaMetadata metadata = candidate.getMetadata();
            if (state == null || metadata == null) continue;
            int kind = state.getState();
            int rank = kind == PlaybackState.STATE_PLAYING || kind == PlaybackState.STATE_BUFFERING
                    || kind == PlaybackState.STATE_CONNECTING ? 3 : kind == PlaybackState.STATE_PAUSED ? 2 : 0;
            if (rank > score) { score = rank; best = candidate; }
        }
        if (controller == null || best == null || !controller.getSessionToken().equals(best.getSessionToken())) {
            if (controller != null) controller.unregisterCallback(callback);
            controller = best;
            if (controller != null) controller.registerCallback(callback, handler);
        }
        render();
    }

    private void render() {
        View oldFocus = findFocus();
        boolean active = controller != null;
        updateVisibility();
        controls.setVisibility(active ? VISIBLE : GONE);
        idleAction.setVisibility(active ? GONE : VISIBLE);
        progress.setVisibility(active ? VISIBLE : GONE);
        details.setFocusable(active);
        if (!active) {
            eyebrow.setText(R.string.now_playing);
            title.setText(access ? "Nothing playing" : "Your music, right here");
            artist.setText(access ? "Play music to see it here" : "Enable local media information & controls");
            idleAction.setText(access ? "Open music" : "Enable");
            artwork.setImageResource(R.drawable.media_placeholder);
            artwork.setContentDescription(null);
            if (oldFocus != null && oldFocus != idleAction) idleAction.requestFocus();
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        boolean playing = state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING || state.getState() == PlaybackState.STATE_CONNECTING);
        String appName = controller.getPackageName();
        try { appName = activity.getPackageManager().getApplicationLabel(
                activity.getPackageManager().getApplicationInfo(appName, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        eyebrow.setText(activity.getString(playing ? R.string.playing_app : R.string.paused_app, appName));
        CharSequence track = metadata == null ? null : metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (TextUtils.isEmpty(track) && metadata != null) track = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        title.setText(TextUtils.isEmpty(track) ? appName : track);
        CharSequence subtitle = metadata == null ? null : metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(subtitle) && metadata != null) subtitle = metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        artist.setText(TextUtils.isEmpty(subtitle) ? "Open app for more" : subtitle);
        details.setContentDescription("Open " + appName);
        Bitmap cover = metadata == null ? null : metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null && metadata != null) cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (cover == null && metadata != null) cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (cover != null) artwork.setImageBitmap(cover);
        else {
            try { artwork.setImageDrawable(activity.getPackageManager().getApplicationIcon(controller.getPackageName())); }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { artwork.setImageResource(R.drawable.media_placeholder); }
        }
        long actions = state == null ? 0 : state.getActions();
        previous.setAvailable((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        next.setAvailable((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        long toggle = playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY;
        playPause.setAvailable((actions & (toggle | PlaybackState.ACTION_PLAY_PAUSE)) != 0);
        playPause.playing = playing;
        playPause.setContentDescription(playing ? "Pause" : "Play");
        playPause.invalidate();
        updateProgress();
        if (oldFocus != null && (!oldFocus.isShown() || !oldFocus.isEnabled())) focusPrimary();
    }

    private void updateProgress() {
        if (controller == null) return;
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        long duration = metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state == null ? 0 : state.getPosition();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        progress.setVisibility(duration > 0 ? VISIBLE : INVISIBLE);
        progress.setProgress(duration > 0 ? (int) Math.max(0, Math.min(1000, position * 1000 / duration)) : 0);
    }

    private void transport(int direction) {
        if (controller == null) return;
        try {
            MediaController.TransportControls transport = controller.getTransportControls();
            if (direction < 0) transport.skipToPrevious();
            else if (direction > 0) transport.skipToNext();
            else {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && (state.getState() == PlaybackState.STATE_PLAYING
                        || state.getState() == PlaybackState.STATE_BUFFERING)) transport.pause();
                else transport.play();
            }
        } catch (SecurityException ignored) { Toast.makeText(activity, "This app does not allow media controls.", Toast.LENGTH_SHORT).show(); }
    }

    private void openSource() {
        if (controller == null) return;
        PendingIntent sessionIntent = controller.getSessionActivity();
        if (sessionIntent != null) {
            try { sessionIntent.send(); return; } catch (PendingIntent.CanceledException ignored) { }
        }
        openPackage(controller.getPackageName());
    }

    private void openMusic() { openPackage("com.spotify.tv.android"); }

    private void openPackage(String pkg) {
        Intent intent = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) {
            try { activity.startActivity(intent); }
            catch (android.content.ActivityNotFoundException | SecurityException ignored) { }
        }
    }

    private void openAccessSettings() {
        try { activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (android.content.ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(activity, "Enable TV Launcher in Settings > Special app access > Notification access.", Toast.LENGTH_LONG).show();
        }
    }

    public boolean focusPrimary() {
        if (!homeVisible || controller == null || !isShown()) return false;
        return playPause.isEnabled() ? playPause.requestFocus() : details.requestFocus();
    }

    public void setHomeVisible(boolean visible) {
        homeVisible = visible;
        updateVisibility();
    }

    public void setOnHidden(Runnable action) { onHidden = action; }

    private Runnable onVisibilityChanged;
    /** Fires whenever the panel is shown or hidden on Home. */
    public void setOnVisibilityChanged(Runnable action) { onVisibilityChanged = action; }

    private void updateVisibility() {
        boolean focused = hasFocus();
        boolean show = homeVisible && controller != null;
        boolean changed = (getVisibility() == VISIBLE) != show;
        setVisibility(show ? VISIBLE : GONE);
        if (changed && onVisibilityChanged != null) onVisibilityChanged.run();
        if (!show && focused && onHidden != null) onHidden.run();
    }

    public boolean containsFocus(View view) {
        for (View current = view; current != null;) {
            if (current == this) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END); view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable glass(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private class TransportButton extends View {
        final int direction;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        boolean playing;
        TransportButton(int direction, String label, Runnable click) {
            super(activity); this.direction = direction;
            setId(View.generateViewId()); setFocusable(true); setClickable(true); setContentDescription(label);
            setOnClickListener(v -> click.run());
            setOnFocusChangeListener((v, focused) -> {
                setBackground(glass(focused ? 0x65ffffff : Color.TRANSPARENT, 18, focused ? 0xafffffff : 0));
            });
        }
        void setAvailable(boolean available) { setEnabled(available); setFocusable(available); setAlpha(available ? 1 : .25f); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(Color.WHITE);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(6);
            if (direction == 0 && playing) {
                canvas.drawRoundRect(cx - r, cy - r, cx - dp(2), cy + r, dp(1), dp(1), paint);
                canvas.drawRoundRect(cx + dp(2), cy - r, cx + r, cy + r, dp(1), dp(1), paint);
            } else {
                float sign = direction < 0 ? -1 : 1;
                path.reset();
                path.moveTo(cx - sign * r / 2, cy - r);
                path.lineTo(cx + sign * r, cy);
                path.lineTo(cx - sign * r / 2, cy + r); path.close();
                canvas.drawPath(path, paint);
                if (direction != 0) canvas.drawRect(cx + sign * r + (sign < 0 ? -dp(3) : 0), cy - r,
                        cx + sign * r + (sign > 0 ? dp(3) : 0), cy + r, paint);
            }
        }
    }
}
