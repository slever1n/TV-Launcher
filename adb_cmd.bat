@echo off
set "ANDROID_USER_HOME=E:\Devin\tv\.android"
set "ADB_VENDOR_KEYS=E:\Devin\tv\.android\adbkey"
set "ADB=E:\Devin\tv\android-sdk\platform-tools\adb.exe"
"%ADB%" connect 192.168.100.70:5555
"%ADB%" -s 192.168.100.70:5555 %*
