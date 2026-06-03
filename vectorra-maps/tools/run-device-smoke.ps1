param(
    [string]$AndroidHome = "C:\Users\myg\AppData\Local\Android\Sdk",
    [string]$DeviceSerial = "",
    [string]$Apk = "vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk",
    [string]$OutputDirectory = "build/device-smoke",
    [int]$ActionDelaySeconds = 8
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $AndroidHome "platform-tools/adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb"
}

$apkPath = Join-Path $repoRoot $Apk
if (-not (Test-Path $apkPath)) {
    throw "APK not found: $Apk"
}

$devices = & $adb devices -l
$deviceLines = $devices | Where-Object { $_ -match '^\S+\s+\S+' -and $_ -notmatch '^List of devices' }
if ($DeviceSerial.Length -eq 0) {
    $online = @($deviceLines | Where-Object { $_ -match '^\S+\s+device(\s|$)' })
    if ($online.Count -ne 1) {
        throw "Expected exactly one online adb device, found $($online.Count). adb devices: $($devices -join ' | ')"
    }
    $DeviceSerial = ($online[0] -split '\s+')[0]
}

$selectedLine = $deviceLines | Where-Object { $_ -match "^$([regex]::Escape($DeviceSerial))\s+" } | Select-Object -First 1
if (-not $selectedLine) {
    throw "Device $DeviceSerial not found. adb devices: $($devices -join ' | ')"
}
if ($selectedLine -notmatch "^\S+\s+device(\s|$)") {
    throw "Device $DeviceSerial is not online: $selectedLine"
}

$out = Join-Path $repoRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb -s $DeviceSerial @Args
}

function Smoke-Action {
    param([string]$Action, [int]$DelaySeconds = $ActionDelaySeconds)
    Write-Host "Running sample action: $Action"
    Invoke-Adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action $Action | Out-Host
    Start-Sleep -Seconds $DelaySeconds
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$report = Join-Path $out "device-smoke-$stamp.txt"

Invoke-Adb install -r $apkPath | Tee-Object -FilePath $report

$props = [ordered]@{
    serial = $DeviceSerial
    model = (Invoke-Adb shell getprop ro.product.model) -join "`n"
    sdk = (Invoke-Adb shell getprop ro.build.version.sdk) -join "`n"
    abis = (Invoke-Adb shell getprop ro.product.cpu.abilist) -join "`n"
    gpu = (Invoke-Adb shell dumpsys SurfaceFlinger 2>$null | Select-String -Pattern "GLES|Vulkan|GPU" | Select-Object -First 10) -join "`n"
}
$props.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Tee-Object -FilePath $report -Append

$actions = @(
    "mvt",
    "pan-mvt",
    "hidden-mvt",
    "readd-mvt",
    "mvt-mbtiles",
    "geojson",
    "draw",
    "clear-draw",
    "location",
    "location-follow",
    "clear-location",
    "snapshot",
    "3dtiles",
    "zoom-3dtiles",
    "bad-3dtiles",
    "readd-3dtiles",
    "offline-prefetch",
    "cancel-prefetch"
)

Invoke-Adb logcat -c
foreach ($action in $actions) {
    Smoke-Action $action
}

Smoke-Action "snapshot" 4
$screenshotDevice = "/sdcard/vectorra-smoke-$stamp.png"
$screenshotHost = Join-Path $out "vectorra-smoke-$stamp.png"
Invoke-Adb shell screencap -p $screenshotDevice | Out-Null
Invoke-Adb pull $screenshotDevice $screenshotHost | Tee-Object -FilePath $report -Append
Invoke-Adb shell rm $screenshotDevice | Out-Null

Invoke-Adb logcat -d -s VectorraSample VectorraNative Vectorra | Tee-Object -FilePath (Join-Path $out "device-smoke-$stamp.log")

Write-Host "Device smoke completed. Report: $report"
