@echo off
setlocal enabledelayedexpansion
set "JAVA_HOME=C:\Program Files\Amazon Corretto\jdk17.0.20_12"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME=%JAVA_HOME%
echo Building APK...
call gradlew.bat assembleDebug
echo Build complete.
