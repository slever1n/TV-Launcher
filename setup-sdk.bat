@echo off
setlocal
set JAVA_HOME=C:\Program Files\Amazon Corretto\jdk17.0.20_12
set PATH=%JAVA_HOME%\bin;%PATH%
set ANDROID_SDK_ROOT=%CD%\android-sdk
set PATH=%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin;%PATH%

echo Installing Android SDK components...
sdkmanager.bat "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo.
echo SDK setup complete.
