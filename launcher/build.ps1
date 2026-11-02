$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk17.0.20_12"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptPath
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "Building APK..."
.\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Launcher build failed with exit code $LASTEXITCODE" }
Write-Host "Build complete."
