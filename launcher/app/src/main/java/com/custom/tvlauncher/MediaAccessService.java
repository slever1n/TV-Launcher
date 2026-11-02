package com.custom.tvlauncher;

import android.service.notification.NotificationListenerService;

/** Android uses this opt-in listener grant to authorize MediaSessionManager access.
 * No notification contents are read, stored, or forwarded. */
public class MediaAccessService extends NotificationListenerService { }
