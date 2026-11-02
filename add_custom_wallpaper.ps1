$bytes = [System.IO.File]::ReadAllBytes('E:\Devin\tv\shots\test_unlocked.png')
$b64 = [Convert]::ToBase64String($bytes)
[System.IO.File]::WriteAllText('E:\Devin\tv\shots\img.b64', $b64)

& 'E:\Devin\tv\adb_cmd.bat' push 'E:\Devin\tv\shots\img.b64' /data/local/tmp/img.b64
& 'E:\Devin\tv\adb_cmd.bat' shell chmod 666 /data/local/tmp/img.b64
& 'E:\Devin\tv\adb_cmd.bat' shell "run-as com.custom.tvlauncher rm -f files/custom_wallpapers/test.txt files/custom_wallpapers/custom_1.jpg"
& 'E:\Devin\tv\adb_cmd.bat' shell "run-as com.custom.tvlauncher sh -c 'base64 -d /data/local/tmp/img.b64 > files/custom_wallpapers/beach_paradise.png'"
& 'E:\Devin\tv\adb_cmd.bat' shell "run-as com.custom.tvlauncher ls -lh files/custom_wallpapers"
