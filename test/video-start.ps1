# Measures how long a YouTube video takes to start in Brave on the TV: time from opening the
# watch page to Brave creating the hardware video decoder, and to the first decoded frame.
# Reuses one Brave tab across runs. Usage: .\video-start.ps1 [-Tv 192.168.31.49:5555] [-Id aqz-KE-bpKQ]
param([string]$Tv = '192.168.31.49:5555', [string]$Id = 'aqz-KE-bpKQ', [int]$WaitSec = 22)

$log = Join-Path $env:TEMP "tvmouse-video-$PID.log"
$pkg = 'com.brave.browser_nightly'
$job = Start-Job -ArgumentList $Tv, $log { param($t, $f) adb -s $t logcat -T 1 -v time > $f }
Start-Sleep 2
$start = (adb -s $Tv shell "date +%H:%M:%S.%N; am start -a android.intent.action.VIEW -d 'https://m.youtube.com/watch?v=$Id' --es com.android.browser.application_id $pkg -n $pkg/com.google.android.apps.chrome.Main >/dev/null 2>&1" | Select-Object -First 1).Trim().Substring(0, 12)
Start-Sleep $WaitSec
Stop-Job $job; Remove-Job $job
$t0 = [TimeSpan]::Parse($start)
function After($pattern) {
    foreach ($line in (Get-Content $log | Select-String $pattern)) {
        $ts = [TimeSpan]::Parse(($line.Line -split ' ')[1])
        if (($ts - $t0).TotalSeconds -gt 0.3) { return ($ts - $t0).TotalSeconds }
    }
    return $null
}
$intent = After 'cr_ActivityDispatcher|cr_IntentHandler'
$codec = After 'create MediaCodec video decoder'
$frame = if ($codec) { foreach ($line in (Get-Content $log | Select-String 'DipDetile_Run')) { $ts = [TimeSpan]::Parse(($line.Line -split ' ')[1]); if (($ts - $t0).TotalSeconds -gt $codec) { ($ts - $t0).TotalSeconds; break } } }
$kills = (Get-Content $log | Select-String 'lowmemorykiller.*Kill').Count
$mime = (Get-Content $log | Select-String 'create MediaCodec video decoder, mime (\S+),' | Select-Object -Last 1).Matches.Groups[1].Value
"opened=$start  brave_got_intent={0:N1}s  decoder_created={1:N1}s  first_frame={2:N1}s  codec=$mime  lowmem_kills=$kills" -f $intent, $codec, $frame
Remove-Item $log -ErrorAction SilentlyContinue
