# Repackages a stock Brave APK for Android TV: manifest patch, zipalign, debug sign.
# Usage: .\build-brave-tv.ps1 [-Stock brave\stock\BraveMonox86-1.102.3.apk] [-Name brave-tv-x86]
param(
    [string]$Stock = "$PSScriptRoot\brave\stock\BraveMonox86-1.102.3.apk",
    [string]$Name = 'brave-tv-x86'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\35.0.0"
$signer = 'D:\Downloads\reflutter\uber-apk-signer-1.3.0.jar'
$build = "$root\build\brave"
New-Item -ItemType Directory -Force $build, "$root\out" | Out-Null

python "$root\brave\patch_tv_manifest.py" $Stock "$build\$Name-unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'manifest patch failed' }

$aligned = "$build\$Name-aligned.apk"
if (Test-Path $aligned) { Remove-Item $aligned }
& "$bt\zipalign.exe" -P 16 4 "$build\$Name-unsigned.apk" $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

# uber-apk-signer's built-in debug key (same key as earlier Brave TV builds, so they update in place)
java -jar $signer --apks $aligned --skipZipAlign --allowResign --out "$build\signed" | Select-Object -Last 6
if ($LASTEXITCODE -ne 0) { throw 'signing failed' }
$signed = Get-ChildItem "$build\signed\*.apk" | Sort-Object LastWriteTime | Select-Object -Last 1
Copy-Item $signed.FullName "$root\out\$Name.apk" -Force
& "$bt\aapt2.exe" dump badging "$root\out\$Name.apk" 2>$null | Select-String 'leanback|touchscreen|^package'
"Built $root\out\$Name.apk ($((Get-Item "$root\out\$Name.apk").Length) bytes)"
