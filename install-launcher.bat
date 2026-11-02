@echo off
setlocal
cd /d "%~dp0"
set "TV_IP=192.168.100.70"
if not "%~1"=="" set "TV_IP=%~1"
set "ADB_PATH=%~dp0android-sdk\platform-tools\adb.exe"
if not exist "%ADB_PATH%" (
    echo ADB was not found at "%ADB_PATH%".
    exit /b 1
)
set "ANDROID_USER_HOME=%~dp0.android"
set "ADB_VENDOR_KEYS=%~dp0.android\adbkey"

if exist "E:\Devin\jdk-17\jdk17.0.20_12" (
    set "JAVA_HOME=E:\Devin\jdk-17\jdk17.0.20_12"
) else if exist "C:\Program Files\Amazon Corretto\jdk17.0.20_12" (
    set "JAVA_HOME=C:\Program Files\Amazon Corretto\jdk17.0.20_12"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
pushd "%~dp0launcher"
call gradlew.bat assembleDebug --console=plain
if errorlevel 1 (
    popd
    echo Launcher build failed.
    exit /b 1
)
popd
"%ADB_PATH%" connect %TV_IP%:5555
if errorlevel 1 exit /b 1
"%ADB_PATH%" -s %TV_IP%:5555 install -r "%~dp0launcher\app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 exit /b 1
"%ADB_PATH%" -s %TV_IP%:5555 shell cmd package set-home-activity com.custom.tvlauncher/.MainActivity
if errorlevel 1 exit /b 1
"%ADB_PATH%" -s %TV_IP%:5555 shell input keyevent KEYCODE_HOME
if errorlevel 1 exit /b 1
echo Launcher installed.
exit /b 0
