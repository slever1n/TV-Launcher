# Android TV Debloat & System Package Management Guide

This guide documents the system debloating, package removal, restore scripts, and hardware configuration used for the **Skyworth SWTV-22SA-FHD** (Android 14 / SDK 34).

---

## Target Device Specifications

- **Model**: Skyworth SWTV-22SA-FHD
- **Android Version**: 14 (SDK 34)
- **Resolution**: 1280x720 (Framebuffer density ~1.33x - 1.5x)
- **Build**: `UKNV.260211.001`
- **Launcher Package**: `com.custom.tvlauncher`

---

## Debloat Overview

Smart TVs often come preloaded with heavy OEM background services, telemetry, ad engines, and sluggish stock launchers that consume valuable RAM and CPU cycles. Debloating frees up ~300-400 MB of system RAM, prevents background stutter, and ensures instantaneous launcher navigation.

### Disabled Packages

The following packages were safely disabled without bricking core Android TV functions:

#### 1. Telemetry & Analytics
```text
com.google.android.partnersetup
com.google.android.feedback
com.android.federatedcompute.services
com.android.ondevicepersonalization.services
com.google.android.ext.shared
```

#### 2. Manufacturer Bloatware, Content Recommendation & Ads
```text
com.smartdevice.recordactivation
com.smartdevice.dailymanage
com.smartdevice.recommendation
com.smartdevice.myapp
com.smartdevice.dailyshortcut
com.smartdevice.aftersale
com.Smartdevice.emanual
```

#### 3. Unnecessary Background Services & Factory Menus
```text
com.google.android.play.games
mediatek.factorymenu.ui
com.smartdevice.factory
com.spocky.projengmenu
com.android.dreams.basic           # Stock screensaver (replaced by Custom Lock Screen)
com.android.htmlviewer
com.android.backupconfirm
com.android.managedprovisioning
```

#### 4. Stock Launchers
```text
com.google.android.apps.tv.launcherx     # Google TV launcher
com.google.android.tungsten.setupwraith  # Initial TV setup wizard
```

---

## Retained Essential Packages

The following packages were retained to ensure full streaming, casting, and system operation:

- **Streaming Apps**: Netflix, YouTube, Spotify, Disney+, Emby, Stremio, Apple TV, beIN Sports, Setanta, Moonlight.
- **Connectivity & Input**: Chromecast built-in, iCasting (DLNA/AirPlay), Skyworth global service (Bluetooth remote audio & pairing), TV inputs & HDMI switcher.

---

## Restore Scripts

If you ever need to restore all stock packages and revert back to the factory Google TV launcher, use the included restore scripts:

### On Windows
```bat
restore.bat
```

### On macOS / Linux
```bash
chmod +x restore.sh
./restore.sh
```

These scripts re-enable all disabled system packages via ADB:
```bash
adb shell pm enable <package-name>
```

---

## Developer Gotchas

1. **Keystore Signing**:
   - If updating an existing pre-installed version on the device, the APK must be signed with the same keystore to avoid `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
   - If you want to install cleanly with your own keys, uninstall the existing package first:
     ```bash
     adb uninstall com.custom.tvlauncher
     ```
2. **Screensaver vs. Custom Lock Screen**:
   - The launcher includes an integrated Apple TV-style lock screen with dimming and active dock. To prevent the native Android screensaver from interfering, the launcher sets:
     ```bash
     adb shell settings put secure screensaver_enabled 0
     ```
