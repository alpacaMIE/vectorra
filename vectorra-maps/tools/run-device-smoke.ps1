param(
    [string]$AndroidHome = "C:\Users\myg\AppData\Local\Android\Sdk",
    [string]$DeviceSerial = "",
    [string]$Apk = "",
    [string]$OutputDirectory = "build/device-smoke",
    [int]$ActionDelaySeconds = 8
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $AndroidHome "platform-tools/adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb"
}

$devices = & $adb devices -l
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed with exit code ${LASTEXITCODE}: $($devices -join ' | ')"
}
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

if ($Apk.Length -eq 0) {
    $deviceAbis = (& $adb -s $DeviceSerial shell getprop ro.product.cpu.abilist) -join ""
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed with exit code $LASTEXITCODE on ${DeviceSerial}: shell getprop ro.product.cpu.abilist"
    }
    if ($deviceAbis -match "(^|,)arm64-v8a(,|$)") {
        $Apk = "vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk"
    } elseif ($deviceAbis -match "(^|,)x86_64(,|$)") {
        $Apk = "vectorra-sample/build/outputs/apk/debug/vectorra-sample-x86_64-debug.apk"
    } else {
        $Apk = "vectorra-sample/build/outputs/apk/debug/vectorra-sample-universal-debug.apk"
    }
    Write-Host "Selected APK for ABI list '$deviceAbis': $Apk"
}

$apkPath = Join-Path $repoRoot $Apk
if (-not (Test-Path $apkPath)) {
    throw "APK not found: $Apk"
}

$out = Join-Path $repoRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb -s $DeviceSerial @Args
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed with exit code $LASTEXITCODE on ${DeviceSerial}: $($Args -join ' ')"
    }
}

function Write-Report {
    param([string]$Message)
    $line = "$(Get-Date -Format o) $Message"
    $line | Tee-Object -FilePath $report -Append
}

function Smoke-Action {
    param([string]$Action, [int]$DelaySeconds = $ActionDelaySeconds)
    Write-Report "actionStart=$Action delaySeconds=$DelaySeconds"
    Write-Host "Running sample action: $Action"
    Invoke-Adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action $Action | Out-Host
    Start-Sleep -Seconds $DelaySeconds
    Write-Report "actionEnd=$Action"
}

function Start-Sample {
    param([string]$Label, [int]$DelaySeconds = $ActionDelaySeconds)
    Write-Report "startSample=$Label delaySeconds=$DelaySeconds"
    Write-Host "Starting sample: $Label"
    Invoke-Adb shell am start -n com.vectorra.sample/.MainActivity | Out-Host
    Start-Sleep -Seconds $DelaySeconds
    Write-Report "startSampleEnd=$Label"
}

function Lifecycle-Step {
    param([string]$Label, [scriptblock]$Command, [int]$DelaySeconds = $ActionDelaySeconds)
    Write-Report "lifecycleStart=$Label delaySeconds=$DelaySeconds"
    Write-Host "Running lifecycle step: $Label"
    & $Command | Out-Host
    Start-Sleep -Seconds $DelaySeconds
    Write-Report "lifecycleEnd=$Label"
}

function Assert-NonEmptyFile {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path $Path)) {
        throw "$Label was not created: $Path"
    }
    $item = Get-Item $Path
    if ($item.Length -le 0) {
        throw "$Label is empty: $Path"
    }
    "$Label=$Path bytes=$($item.Length)" | Tee-Object -FilePath $report -Append
}

function First-MatchingLines {
    param([string]$Text, [string]$Pattern, [int]$Count = 10)
    return (($Text -split "`r?`n") | Select-String -Pattern $Pattern | Select-Object -First $Count) -join "`n"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$report = Join-Path $out "device-smoke-$stamp.txt"

Invoke-Adb install -r $apkPath | Tee-Object -FilePath $report
Write-Report "installApk=$Apk"

$surfaceFlinger = (Invoke-Adb shell dumpsys SurfaceFlinger 2>$null) -join "`n"
$gpuInfo = First-MatchingLines $surfaceFlinger "GLES|Vulkan|GPU" 10
$vulkanInfo = First-MatchingLines $surfaceFlinger "Vulkan" 10
if ($vulkanInfo.Length -eq 0) {
    $vulkanInfo = (Invoke-Adb shell getprop ro.hardware.vulkan) -join "`n"
}

$props = [ordered]@{
    serial = $DeviceSerial
    installedApk = $Apk
    model = (Invoke-Adb shell getprop ro.product.model) -join "`n"
    sdk = (Invoke-Adb shell getprop ro.build.version.sdk) -join "`n"
    abis = (Invoke-Adb shell getprop ro.product.cpu.abilist) -join "`n"
    gpu = $gpuInfo
    vulkan = $vulkanInfo
}
$props.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Tee-Object -FilePath $report -Append

$actions = @(
    @{ name = "mvt"; delay = $ActionDelaySeconds },
    @{ name = "pan-mvt"; delay = 10 },
    @{ name = "hidden-mvt"; delay = $ActionDelaySeconds },
    @{ name = "readd-mvt"; delay = 10 },
    @{ name = "mvt-mbtiles"; delay = $ActionDelaySeconds },
    @{ name = "geojson"; delay = $ActionDelaySeconds },
    @{ name = "draw"; delay = $ActionDelaySeconds },
    @{ name = "clear-draw"; delay = 4 },
    @{ name = "location"; delay = $ActionDelaySeconds },
    @{ name = "location-follow"; delay = $ActionDelaySeconds },
    @{ name = "clear-location"; delay = 4 },
    @{ name = "snapshot"; delay = 4 },
    @{ name = "3dtiles"; delay = 14 },
    @{ name = "zoom-3dtiles"; delay = 24 },
    @{ name = "bad-3dtiles"; delay = $ActionDelaySeconds },
    @{ name = "readd-3dtiles"; delay = 24 },
    @{ name = "offline-prefetch"; delay = 16 },
    @{ name = "cancel-prefetch"; delay = 10 }
)

Invoke-Adb logcat -c
Write-Report "logcatCleared=true"
Invoke-Adb shell am force-stop com.vectorra.sample | Out-Null
Write-Report "forceStopBeforeColdStart=true"
Start-Sample "cold-start" 10
foreach ($action in $actions) {
    Smoke-Action $action.name $action.delay
}

Lifecycle-Step "pause-home" { Invoke-Adb shell input keyevent KEYCODE_HOME } 4
Start-Sample "resume-after-home" 10
Lifecycle-Step "destroy-force-stop" { Invoke-Adb shell am force-stop com.vectorra.sample } 4
Start-Sample "recreate-after-force-stop" 10
Smoke-Action "post-recreate-snapshot" 4
$screenshotDevice = "/sdcard/vectorra-smoke-$stamp.png"
$screenshotHost = Join-Path $out "vectorra-smoke-$stamp.png"
Invoke-Adb shell screencap '-p' $screenshotDevice | Out-Null
Invoke-Adb pull $screenshotDevice $screenshotHost | Tee-Object -FilePath $report -Append
Invoke-Adb shell rm $screenshotDevice | Out-Null

$uiDevice = "/sdcard/vectorra-smoke-$stamp.xml"
$uiHost = Join-Path $out "vectorra-smoke-$stamp.xml"
Invoke-Adb shell uiautomator dump $uiDevice | Tee-Object -FilePath $report -Append
Invoke-Adb pull $uiDevice $uiHost | Tee-Object -FilePath $report -Append
Invoke-Adb shell rm $uiDevice | Out-Null

$logHost = Join-Path $out "device-smoke-$stamp.log"
Invoke-Adb logcat '-d' '-s' VectorraSample VectorraMapView VectorraMapEngine vectorra_jni AndroidRuntime libc DEBUG ActivityManager:E ActivityTaskManager:E |
    Set-Content -Path $logHost

Assert-NonEmptyFile $screenshotHost "screenshot"
Assert-NonEmptyFile $uiHost "uiDump"
Assert-NonEmptyFile $logHost "logcat"

$uiText = Get-Content -Path $uiHost -Raw
if ($uiText -notmatch "com\.vectorra\.sample") {
    throw "UI dump does not contain com.vectorra.sample: $uiHost"
}
"uiDumpContainsPackage=com.vectorra.sample" | Tee-Object -FilePath $report -Append

Write-Host "Device smoke completed. Report: $report"
