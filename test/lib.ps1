# Helpers for driving the phone app and capturing the TV. Dot-source this file.
$script:TV = if ($env:TVMOUSE_TV) { $env:TVMOUSE_TV } else { 'emulator-5554' }
$script:PH = $env:TVMOUSE_PHONE   # adb serial of the phone, from `adb devices`
$script:Evidence = "$PSScriptRoot\..\evidence"
New-Item -ItemType Directory -Force $script:Evidence | Out-Null

# Returns @{ desc = @{x1;y1;x2;y2;cx;cy} } for every phone view with a content-desc.
function Get-PhoneViews {
    adb -s $PH shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" | Out-String | Set-Variable xml
    $views = @{}
    foreach ($m in [regex]::Matches($xml, 'content-desc="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $x1 = [int]$m.Groups[2].Value; $y1 = [int]$m.Groups[3].Value
        $x2 = [int]$m.Groups[4].Value; $y2 = [int]$m.Groups[5].Value
        $views[$m.Groups[1].Value] = @{ x1 = $x1; y1 = $y1; x2 = $x2; y2 = $y2; cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2) }
    }
    $script:PhoneXml = $xml
    $views
}

function Phone-Status {
    Get-PhoneViews | Out-Null
    $m = [regex]::Match($script:PhoneXml, 'text="([^"]*)"[^>]*content-desc="status"')
    $m.Groups[1].Value
}

function Phone-Tap($v) { adb -s $PH shell input tap $v.cx $v.cy }

# Drag one finger on the touchpad by (dx, dy) phone pixels, starting from its centre.
function Pad-Drag($pad, [int]$dx, [int]$dy, [int]$ms = 400) {
    adb -s $PH shell input swipe $pad.cx $pad.cy ($pad.cx + $dx) ($pad.cy + $dy) $ms
}

function TV-Shot($name) { $name = "$($script:Prefix)$name"
    adb -s $TV shell screencap -p /sdcard/s.png
    adb -s $TV pull /sdcard/s.png "$Evidence\$name.png" | Out-Null
    "$Evidence\$name.png"
}

function Phone-Shot($name) {
    adb -s $PH shell screencap -p /sdcard/tvmouse.png
    adb -s $PH pull /sdcard/tvmouse.png "$Evidence\$name.png" | Out-Null
    adb -s $PH shell rm /sdcard/tvmouse.png
    "$Evidence\$name.png"
}

function TV-Log([int]$n = 12) { adb -s $TV logcat -d -t 400 -s TvMouse | Select-Object -Last $n }
