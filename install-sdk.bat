@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Amazon Corretto\jdk17.0.20_12"
set "ANDROID_HOME=%CD%\android-sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%"

echo Installing Android SDK components...
sdkmanager.bat "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo.
echo SDK installation complete.
pause
