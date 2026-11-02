Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap('E:\Devin\tv\shots\test_lockscreen_centered.png')
$w = $bmp.Width
$h = $bmp.Height

# The date is between Y=60 and Y=90, X between 400 and 880
$minX_date = 9999; $maxX_date = 0
for ($y = 65; $y -lt 85; $y++) {
    for ($x = 450; $x -lt 830; $x++) {
        $c = $bmp.GetPixel($x, $y)
        if ($c.R -gt 220 -and $c.G -gt 220 -and $c.B -gt 220) {
            if ($x -lt $minX_date) { $minX_date = $x }
            if ($x -gt $maxX_date) { $maxX_date = $x }
        }
    }
}
$centerX_date = ($minX_date + $maxX_date) / 2
Write-Host "Date bounds: X=$minX_date to $maxX_date (width: $($maxX_date - $minX_date)). Center X = $centerX_date (offset from 640: $($centerX_date - 640))"

# The clock digits "3:01" are between Y=120 and Y=200, X between 450 and 800
$minX_clock = 9999; $maxX_clock = 0
for ($y = 120; $y -lt 210; $y++) {
    for ($x = 450; $x -lt 830; $x++) {
        $c = $bmp.GetPixel($x, $y)
        if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) {
            if ($x -lt $minX_clock) { $minX_clock = $x }
            if ($x -gt $maxX_clock) { $maxX_clock = $x }
        }
    }
}
$centerX_clock = ($minX_clock + $maxX_clock) / 2
Write-Host "Clock bounds: X=$minX_clock to $maxX_clock (width: $($maxX_clock - $minX_clock)). Center X = $centerX_clock (offset from 640: $($centerX_clock - 640))"

# Check where the colon ':' is in the clock
$minX_colon = 9999; $maxX_colon = 0
# The colon is between the '3' and '0' around Y=145-155, 175-185
for ($y = 145; $y -lt 155; $y++) {
    for ($x = 570; $x -lt 650; $x++) {
        $c = $bmp.GetPixel($x, $y)
        if ($c.R -gt 210 -and $c.G -gt 210 -and $c.B -gt 210) {
            if ($x -lt $minX_colon) { $minX_colon = $x }
            if ($x -gt $maxX_colon) { $maxX_colon = $x }
        }
    }
}
$centerX_colon = ($minX_colon + $maxX_colon) / 2
Write-Host "Colon bounds: X=$minX_colon to $maxX_colon. Center X = $centerX_colon (offset from 640: $($centerX_colon - 640))"

$bmp.Dispose()
