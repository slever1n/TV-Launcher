@echo off
setlocal
call "%~dp0adb_cmd.bat" connect 192.168.100.70:5555
set "DEV=-s 192.168.100.70:5555"

echo 1. Going home...
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 3
timeout /t 2 /nobreak >nul

echo 2. Moving up to header...
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 19
timeout /t 1 /nobreak >nul

echo 3. Moving right to Wi-Fi...
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 22
timeout /t 1 /nobreak >nul
call "%~dp0adb_cmd.bat" %DEV% shell screencap -p /sdcard/__shot.png
call "%~dp0adb_cmd.bat" %DEV% pull /sdcard/__shot.png "%~dp0shots\test_header_wifi.png"

echo 4. Moving right to Settings...
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 22
timeout /t 1 /nobreak >nul
call "%~dp0adb_cmd.bat" %DEV% shell screencap -p /sdcard/__shot.png
call "%~dp0adb_cmd.bat" %DEV% pull /sdcard/__shot.png "%~dp0shots\test_header_settings.png"

echo 5. Moving back to Control Center and opening it...
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 21
timeout /t 1 /nobreak >nul
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 21
timeout /t 1 /nobreak >nul
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 23
timeout /t 2 /nobreak >nul
call "%~dp0adb_cmd.bat" %DEV% shell screencap -p /sdcard/__shot.png
call "%~dp0adb_cmd.bat" %DEV% pull /sdcard/__shot.png "%~dp0shots\test_control_center.png"

echo 6. Clicking on Lock Screen tile...
call "%~dp0adb_cmd.bat" %DEV% shell input keyevent 23
timeout /t 2 /nobreak >nul
call "%~dp0adb_cmd.bat" %DEV% shell screencap -p /sdcard/__shot.png
call "%~dp0adb_cmd.bat" %DEV% pull /sdcard/__shot.png "%~dp0shots\test_lockscreen_options.png"

echo Done.
