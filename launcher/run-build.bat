@echo off
set "JAVA_HOME=C:\Program Files\Amazon Corretto\jdk17.0.20_12"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
echo JAVA_HOME: %JAVA_HOME%
echo Building APK...
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo Build failed with error code %ERRORLEVEL%
)
pause
