# Builds one of the two apps without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: .\build-app.ps1 tv-cursor   or   .\build-app.ps1 phone-mouse
param([Parameter(Mandatory = $true)][ValidateSet('tv-cursor', 'phone-mouse')][string]$App)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\35.0.0"
$androidJar = "$sdk\platforms\android-35\android.jar"
$src = "$root\$App"
$build = "$root\build\$App"
$outDir = "$root\out"
$keystore = "$HOME\.android\debug.keystore"

function Run($exe, [string[]]$a) {
    & $exe @a
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force "$build\gen", "$build\classes", "$build\dex", $outDir | Out-Null

Run "$bt\aapt2.exe" @('compile', '--dir', "$src\res", '-o', "$build\res.zip")
Run "$bt\aapt2.exe" @('link', '-o', "$build\base.apk", '-I', $androidJar,
    '--manifest', "$src\AndroidManifest.xml", '--java', "$build\gen",
    '--min-sdk-version', '24', '--target-sdk-version', '34', "$build\res.zip")

$sources = @(Get-ChildItem -Recurse -Filter *.java "$src\src", "$build\gen" | ForEach-Object FullName)
Run 'javac' (@('-nowarn', '-Xlint:-options', '--release', '8', '-encoding', 'UTF-8',
        '-classpath', $androidJar, '-d', "$build\classes") + $sources)

$classes = @(Get-ChildItem -Recurse -Filter *.class "$build\classes" | ForEach-Object FullName)
Run 'java' (@('-cp', "$bt\lib\d8.jar", 'com.android.tools.r8.D8', '--min-api', '24',
        '--lib', $androidJar, '--output', "$build\dex") + $classes)

Copy-Item "$build\base.apk" "$build\unaligned.apk"
Push-Location "$build\dex"
try { Run 'jar' @('uf', "$build\unaligned.apk", 'classes.dex') } finally { Pop-Location }

$apk = "$outDir\$App.apk"
if (Test-Path $apk) { Remove-Item $apk }
Run "$bt\zipalign.exe" @('-p', '4', "$build\unaligned.apk", $apk)
Run 'java' @('-jar', "$bt\lib\apksigner.jar", 'sign', '--ks', $keystore, '--ks-pass', 'pass:android',
    '--ks-key-alias', 'androiddebugkey', '--key-pass', 'pass:android', $apk)
Run 'java' @('-jar', "$bt\lib\apksigner.jar", 'verify', $apk)
"Built $apk ($((Get-Item $apk).Length) bytes)"
