# Runs adb against the TV using the project's private ADB key.
# Usage: .\tools\adb-tv.ps1 shell 'settings get secure screensaver_enabled'
#        .\tools\adb-tv.ps1 -Screenshot shots\name.png
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Screenshot,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$env:ANDROID_USER_HOME = Join-Path $root '.android'
$env:ADB_VENDOR_KEYS = Join-Path $root '.android\adbkey'
$adb = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$tv = if ($env:TV_IP) { "$($env:TV_IP):5555" } else { '192.168.100.70:5555' }

$devices = & $adb devices 2>$null | Out-String
if ($devices -notmatch [regex]::Escape($tv) + '\s+device') {
    & $adb connect $tv | Out-Null
    Start-Sleep -Milliseconds 500
}

if ($Screenshot) {
    $path = if ([IO.Path]::IsPathRooted($Screenshot)) { $Screenshot } else { Join-Path $root $Screenshot }
    & $adb -s $tv shell screencap -p /sdcard/__shot.png
    & $adb -s $tv pull /sdcard/__shot.png $path | Out-Null
    & $adb -s $tv shell rm /sdcard/__shot.png
    Write-Output "Saved $path"
}
if ($AdbArgs) { & $adb -s $tv @AdbArgs }
