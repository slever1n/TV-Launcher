$adb = 'E:\Devin\tv\adb_cmd.bat'

Write-Host "1. Resetting to Home..."
& $adb shell input keyevent 3
Start-Sleep -Milliseconds 800

Write-Host "2. Opening Control Center..."
& $adb shell input keyevent 19
Start-Sleep -Milliseconds 400
& $adb shell input keyevent 23
Start-Sleep -Milliseconds 800

Write-Host "3. Selecting Wallpaper Manager..."
& $adb shell input keyevent 20
Start-Sleep -Milliseconds 200
& $adb shell input keyevent 20
Start-Sleep -Milliseconds 200
& $adb shell input keyevent 20
Start-Sleep -Milliseconds 200
& $adb shell input keyevent 23
Start-Sleep -Milliseconds 1200

Write-Host "4. Capturing Wallpaper Picker with custom wallpaper..."
& $adb shell screencap -p /sdcard/__shot.png
& $adb pull /sdcard/__shot.png 'E:\Devin\tv\shots\test_wp_custom_shown.png'

Write-Host "5. Moving DOWN from action buttons into custom wallpaper..."
& $adb shell input keyevent 20
Start-Sleep -Milliseconds 600
& $adb shell screencap -p /sdcard/__shot.png
& $adb pull /sdcard/__shot.png 'E:\Devin\tv\shots\test_wp_focus_custom.png'

Write-Host "6. Moving DOWN from custom wallpaper into presets..."
& $adb shell input keyevent 20
Start-Sleep -Milliseconds 600
& $adb shell screencap -p /sdcard/__shot.png
& $adb pull /sdcard/__shot.png 'E:\Devin\tv\shots\test_wp_focus_preset.png'

Write-Host "7. Moving UP from preset back into custom wallpaper..."
& $adb shell input keyevent 19
Start-Sleep -Milliseconds 600
& $adb shell screencap -p /sdcard/__shot.png
& $adb pull /sdcard/__shot.png 'E:\Devin\tv\shots\test_wp_focus_back_up.png'

Write-Host "8. Moving UP back into action buttons..."
& $adb shell input keyevent 19
Start-Sleep -Milliseconds 600
& $adb shell screencap -p /sdcard/__shot.png
& $adb pull /sdcard/__shot.png 'E:\Devin\tv\shots\test_wp_focus_action.png'

Write-Host "Navigation test complete!"
