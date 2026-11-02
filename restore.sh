#!/bin/bash
# Restore script for Skyworth SWTV-22SA-FHD debloat
# This script re-enables all packages that were disabled

TV_IP="192.168.100.70"
ADB_PATH="C:/Users/Yul/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe"

# Telemetry and analytics packages
echo "Restoring telemetry and analytics packages..."
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.google.android.partnersetup
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.google.android.feedback
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.android.federatedcompute.services
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.android.ondevicepersonalization.services
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.google.android.ext.shared

# Manufacturer content and ad services
echo "Restoring manufacturer content and ad services..."
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.smartdevice.recordactivation
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.smartdevice.dailymanage
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.smartdevice.recommendation
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.smartdevice.myapp
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.smartdevice.dailyshortcut
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.smartdevice.aftersale
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.Smartdevice.emanual

# Background services
echo "Restoring background services..."
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.google.android.play.games
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 mediatek.factorymenu.ui
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.smartdevice.factory
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.spocky.projengmenu
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.android.dreams.basic
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.android.htmlviewer
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.android.backupconfirm
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.android.managedprovisioning

# Restore stock launcher
echo "Restoring stock launcher..."
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.google.android.apps.tv.launcherx
"$ADB_PATH" -s $TV_IP:5555 shell pm enable --user 0 com.google.android.tungsten.setupwraith
"$ADB_PATH" -s $TV_IP:5555 shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity

echo "Restore complete. Please reboot the TV for changes to take effect."
