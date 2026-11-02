package com.custom.tvlauncher;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * Minimal device admin (force-lock only) so the launcher can put the TV into
 * real standby via {@link DevicePolicyManager#lockNow()}.
 * Activate once over ADB:
 * adb shell dpm set-active-admin --user 0 com.custom.tvlauncher/.StandbyAdminReceiver
 */
public class StandbyAdminReceiver extends DeviceAdminReceiver {

    public static ComponentName component(Context context) {
        return new ComponentName(context, StandbyAdminReceiver.class);
    }

    public static boolean isActive(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(component(context));
    }

    /** Turns the screen off (TV standby). Returns false if the admin is not active. */
    public static boolean standby(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm == null || !dpm.isAdminActive(component(context))) return false;
        try {
            dpm.lockNow();
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
