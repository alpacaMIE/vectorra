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

function Assert-ReportValue {
    param([string]$Text, [string]$Key)
    if ($Text -notmatch "(?m)^$([regex]::Escape($Key))=\S") {
        throw "$Key has no value in smoke report"
    }
}

function Assert-ArtifactReportLine {
    param([string]$Text, [string]$Key)
    $pattern = "(?m)^$([regex]::Escape($Key))=.+\sbytes=([1-9][0-9]*)\r?$"
    if ($Text -notmatch $pattern) {
        throw "$Key artifact line with positive byte count missing from smoke report"
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

function Read-BigEndianUInt32 {
    param([byte[]]$Bytes, [int]$Offset)
    return (
        ([int64]$Bytes[$Offset] -shl 24) -bor
        ([int64]$Bytes[$Offset + 1] -shl 16) -bor
        ([int64]$Bytes[$Offset + 2] -shl 8) -bor
        [int64]$Bytes[$Offset + 3]
    )
}

function Assert-PngDimensions {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $pngSignature = [byte[]](0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    if ($bytes.Length -lt 24) {
        throw "screenshot is too small to be a PNG: $Path"
    }
    for ($index = 0; $index -lt $pngSignature.Length; $index++) {
        if ($bytes[$index] -ne $pngSignature[$index]) {
            throw "screenshot is not a PNG: $Path"
        }
    }
    $chunkType = [System.Text.Encoding]::ASCII.GetString($bytes, 12, 4)
    if ($chunkType -ne "IHDR") {
        throw "screenshot PNG missing IHDR chunk: $Path"
    }
    $width = Read-BigEndianUInt32 $bytes 16
    $height = Read-BigEndianUInt32 $bytes 20
    if ($width -le 0 -or $height -le 0) {
        throw "screenshot PNG has invalid dimensions: ${width}x${height}"
    }
    Write-Host "screenshot PNG dimensions: ${width}x${height}"
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

$requiredMetadataKeys = @(
    'serial',
    'installedApk',
    'model',
    'sdk',
    'abis',
    'gpu',
    'vulkan'
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
foreach ($key in $requiredMetadataKeys) {
    Assert-ReportValue $reportText $key
}
foreach ($key in @('screenshot', 'uiDump', 'logcat')) {
    Assert-ArtifactReportLine $reportText $key
}
foreach ($action in $requiredActions) {
    Assert-Contains $reportText "actionStart=$([regex]::Escape($action))" "actionStart $action"
    Assert-Contains $reportText "actionEnd=$([regex]::Escape($action))" "actionEnd $action"
}

Assert-NonEmptyFile $screenshot "screenshot"
Assert-NonEmptyFile $uiDump "ui dump"
Assert-NonEmptyFile $logcat "logcat"
Assert-PngDimensions $screenshot

$uiText = Get-Content -Path $uiDump -Raw
if ($uiText -notmatch 'com\.vectorra\.sample') {
    throw "UI dump does not contain com.vectorra.sample: $uiDump"
}

$logText = Get-Content -Path $logcat -Raw
$requiredLogPatterns = @(
    'Snapshot\s+\d+x\d+\s+nonblank=true',
    '3D Tiles zoom snapshot\s+\d+x\d+\s+nonblank=true'
)
foreach ($pattern in $requiredLogPatterns) {
    if ($logText -notmatch $pattern) {
        throw "Required logcat pattern missing: $pattern"
    }
}

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
