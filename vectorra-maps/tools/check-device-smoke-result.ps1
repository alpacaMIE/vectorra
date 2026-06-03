param(
    [string]$SmokeDirectory = "build/device-smoke",
    [string]$Report = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$smokeRoot = if ([System.IO.Path]::IsPathRooted($SmokeDirectory)) {
    $SmokeDirectory
} else {
    Join-Path $repoRoot $SmokeDirectory
}
if (-not (Test-Path $smokeRoot)) {
    throw "Smoke directory not found: $SmokeDirectory"
}

if ($Report.Length -eq 0) {
    $latest = Get-ChildItem -Path $smokeRoot -Filter "device-smoke-*.txt" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latest) {
        throw "No device-smoke report found under $SmokeDirectory"
    }
    $reportPath = $latest.FullName
} else {
    $reportPath = if ([System.IO.Path]::IsPathRooted($Report)) {
        $Report
    } else {
        Join-Path $repoRoot $Report
    }
}

if (-not (Test-Path $reportPath)) {
    throw "Smoke report not found: $reportPath"
}

$reportText = Get-Content -Path $reportPath -Raw
$stamp = [System.IO.Path]::GetFileNameWithoutExtension($reportPath) -replace '^device-smoke-', ''
$screenshot = Join-Path $smokeRoot "vectorra-smoke-$stamp.png"
$uiDump = Join-Path $smokeRoot "vectorra-smoke-$stamp.xml"
$logcat = Join-Path $smokeRoot "device-smoke-$stamp.log"

function Assert-Contains {
    param([string]$Text, [string]$Pattern, [string]$Label)
    if ($Text -notmatch $Pattern) {
        throw "$Label missing from smoke report: $Pattern"
    }
}

function Assert-NonEmptyFile {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path $Path)) {
        throw "$Label not found: $Path"
    }
    if ((Get-Item $Path).Length -le 0) {
        throw "$Label is empty: $Path"
    }
}

$requiredReportPatterns = @(
    'installApk=',
    'logcatCleared=true',
    'forceStopBeforeColdStart=true',
    'startSample=cold-start',
    'startSampleEnd=cold-start',
    'lifecycleStart=pause-home',
    'lifecycleEnd=pause-home',
    'startSample=resume-after-home',
    'startSampleEnd=resume-after-home',
    'lifecycleStart=destroy-force-stop',
    'lifecycleEnd=destroy-force-stop',
    'startSample=recreate-after-force-stop',
    'startSampleEnd=recreate-after-force-stop',
    'uiDumpContainsPackage=com\.vectorra\.sample'
)

$requiredActions = @(
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

foreach ($pattern in $requiredReportPatterns) {
    Assert-Contains $reportText $pattern $pattern
}
foreach ($action in $requiredActions) {
    Assert-Contains $reportText "actionStart=$([regex]::Escape($action))" "actionStart $action"
    Assert-Contains $reportText "actionEnd=$([regex]::Escape($action))" "actionEnd $action"
}

Assert-NonEmptyFile $screenshot "screenshot"
Assert-NonEmptyFile $uiDump "ui dump"
Assert-NonEmptyFile $logcat "logcat"

$uiText = Get-Content -Path $uiDump -Raw
if ($uiText -notmatch 'com\.vectorra\.sample') {
    throw "UI dump does not contain com.vectorra.sample: $uiDump"
}

$logText = Get-Content -Path $logcat -Raw
$failurePatterns = @(
    'FATAL EXCEPTION',
    'AndroidRuntime',
    'SIGSEGV',
    'SIGABRT',
    'ANR in com\.vectorra\.sample'
)
foreach ($pattern in $failurePatterns) {
    if ($logText -match $pattern) {
        throw "Failure pattern found in logcat: $pattern"
    }
}

Write-Host "Device smoke result check passed: $reportPath"
