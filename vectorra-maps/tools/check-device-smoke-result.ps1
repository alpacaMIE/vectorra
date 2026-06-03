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
$zoom3dTilesScreenshot = Join-Path $smokeRoot "vectorra-smoke-zoom-3dtiles-$stamp.png"
$uiDump = Join-Path $smokeRoot "vectorra-smoke-$stamp.xml"
$logcat = Join-Path $smokeRoot "device-smoke-$stamp.log"

function Assert-Contains {
    param([string]$Text, [string]$Pattern, [string]$Label)
    if ($Text -notmatch $Pattern) {
        throw "$Label missing from smoke report: $Pattern"
    }
}

function Assert-OrderedReportPatterns {
    param([string]$Text, [string[]]$Patterns)
    $lines = $Text -split "`r?`n"
    $startIndex = 0
    foreach ($pattern in $Patterns) {
        $foundIndex = -1
        for ($index = $startIndex; $index -lt $lines.Count; $index++) {
            if ($lines[$index] -match $pattern) {
                $foundIndex = $index
                break
            }
        }
        if ($foundIndex -lt 0) {
            throw "Ordered smoke report marker missing after line ${startIndex}: $pattern"
        }
        $startIndex = $foundIndex + 1
    }
}

function Assert-ReportValue {
    param([string]$Text, [string]$Key)
    $prefix = "$Key="
    $line = ($Text -split "`r?`n") |
        Where-Object { $_ -match "(^|\s)$([regex]::Escape($prefix))" } |
        Select-Object -First 1
    if (-not $line) {
        throw "$Key has no value in smoke report"
    }
    $index = $line.IndexOf($prefix)
    if ($line.Substring($index + $prefix.Length).Trim().Length -eq 0) {
        throw "$Key has no value in smoke report"
    }
}

function Get-ReportValue {
    param([string]$Text, [string]$Key)
    $prefix = "$Key="
    $line = ($Text -split "`r?`n") |
        Where-Object { $_ -match "(^|\s)$([regex]::Escape($prefix))" } |
        Select-Object -First 1
    if (-not $line) {
        throw "$Key missing from smoke report"
    }
    $index = $line.IndexOf($prefix)
    $value = $line.Substring($index + $prefix.Length).Trim()
    if ($value.Length -eq 0) {
        throw "$Key has no value in smoke report"
    }
    return $value
}

function Assert-InstalledApkConsistency {
    param([string]$Text)
    $installApk = Get-ReportValue $Text 'installApk'
    $installedApk = Get-ReportValue $Text 'installedApk'
    if ($installApk -ne $installedApk) {
        throw "installedApk does not match installApk. installApk=$installApk installedApk=$installedApk"
    }

    $abis = Get-ReportValue $Text 'abis'
    $apkName = [System.IO.Path]::GetFileName($installedApk)
    if ($apkName -match 'arm64-v8a' -and $abis -notmatch '(^|,)arm64-v8a(,|$)') {
        throw "installed arm64-v8a APK is not compatible with reported ABIs: $abis"
    }
    if ($apkName -match 'x86_64' -and $abis -notmatch '(^|,)x86_64(,|$)') {
        throw "installed x86_64 APK is not compatible with reported ABIs: $abis"
    }
}

function Assert-ArtifactReportLine {
    param([string]$Text, [string]$Key, [string]$ExpectedPath)
    $pattern = "(?m)^$([regex]::Escape($Key))=(.+)\sbytes=([1-9][0-9]*)\r?$"
    if ($Text -notmatch $pattern) {
        throw "$Key artifact line with positive byte count missing from smoke report"
    }
    $reportedPath = $Matches[1].Trim()
    $reportedFullPath = [System.IO.Path]::GetFullPath($reportedPath)
    $expectedFullPath = [System.IO.Path]::GetFullPath($ExpectedPath)
    if ($reportedFullPath -ne $expectedFullPath) {
        throw "$Key artifact path mismatch. Reported: $reportedPath Expected: $ExpectedPath"
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

function Assert-PngVisibleContent {
    param(
        [string]$Path,
        [string]$Label,
        [double]$StartYFraction = 0.45,
        [double]$EndYFraction = 0.92,
        [int]$MinimumVisibleSamples = 50
    )

    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::FromFile($Path)
    try {
        $width = $bitmap.Width
        $height = $bitmap.Height
        if ($width -le 0 -or $height -le 0) {
            throw "$Label has invalid bitmap dimensions: ${width}x${height}"
        }

        $startY = [int][Math]::Floor($height * $StartYFraction)
        $endY = [int][Math]::Ceiling($height * $EndYFraction)
        if ($startY -ge $endY -or $height -lt 16) {
            $startY = 0
            $endY = $height
        }

        $stepX = [Math]::Max(1, [int][Math]::Floor($width / 100))
        $stepY = [Math]::Max(1, [int][Math]::Floor(($endY - $startY) / 100))
        $visibleSamples = 0
        $totalSamples = 0
        for ($y = $startY; $y -lt $endY; $y += $stepY) {
            for ($x = 0; $x -lt $width; $x += $stepX) {
                $totalSamples++
                $pixel = $bitmap.GetPixel($x, $y)
                if (($pixel.R + $pixel.G + $pixel.B) -gt 96) {
                    $visibleSamples++
                }
            }
        }

        $effectiveMinimum = [Math]::Min($MinimumVisibleSamples, [Math]::Max(1, $totalSamples))
        if ($visibleSamples -lt $effectiveMinimum) {
            throw "$Label does not contain enough visible non-black pixels in the render area: samples=$visibleSamples minimum=$effectiveMinimum total=$totalSamples"
        }
        Write-Host "$Label visible samples: $visibleSamples"
    }
    finally {
        $bitmap.Dispose()
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
Assert-InstalledApkConsistency $reportText
Assert-ArtifactReportLine $reportText 'screenshot' $screenshot
Assert-ArtifactReportLine $reportText 'zoom3dTilesScreenshot' $zoom3dTilesScreenshot
Assert-ArtifactReportLine $reportText 'uiDump' $uiDump
Assert-ArtifactReportLine $reportText 'logcat' $logcat
foreach ($action in $requiredActions) {
    Assert-Contains $reportText "actionStart=$([regex]::Escape($action))" "actionStart $action"
    Assert-Contains $reportText "actionEnd=$([regex]::Escape($action))" "actionEnd $action"
}

$orderedReportPatterns = @(
    'logcatCleared=true',
    'forceStopBeforeColdStart=true',
    'startSample=cold-start(\s|$)',
    'startSampleEnd=cold-start(\s|$)'
)
foreach ($action in $requiredActions) {
    $orderedReportPatterns += "actionStart=$([regex]::Escape($action))(\s|$)"
    $orderedReportPatterns += "actionEnd=$([regex]::Escape($action))(\s|$)"
}
$orderedReportPatterns += @(
    'lifecycleStart=pause-home(\s|$)',
    'lifecycleEnd=pause-home(\s|$)',
    'startSample=resume-after-home(\s|$)',
    'startSampleEnd=resume-after-home(\s|$)',
    'lifecycleStart=destroy-force-stop(\s|$)',
    'lifecycleEnd=destroy-force-stop(\s|$)',
    'startSample=recreate-after-force-stop(\s|$)',
    'startSampleEnd=recreate-after-force-stop(\s|$)',
    'actionStart=post-recreate-snapshot(\s|$)',
    'actionEnd=post-recreate-snapshot(\s|$)'
)
Assert-OrderedReportPatterns $reportText $orderedReportPatterns

Assert-NonEmptyFile $screenshot "screenshot"
Assert-NonEmptyFile $zoom3dTilesScreenshot "3D Tiles close-zoom screenshot"
Assert-NonEmptyFile $uiDump "ui dump"
Assert-NonEmptyFile $logcat "logcat"
Assert-PngDimensions $screenshot
Assert-PngDimensions $zoom3dTilesScreenshot
Assert-PngVisibleContent $zoom3dTilesScreenshot "3D Tiles close-zoom screenshot"

$uiText = Get-Content -Path $uiDump -Raw
if ($uiText -notmatch 'com\.vectorra\.sample') {
    throw "UI dump does not contain com.vectorra.sample: $uiDump"
}

$logText = Get-Content -Path $logcat -Raw
$snapshotPattern = 'Snapshot\s+\d+x\d+\s+nonblank=true'
if ($logText -notmatch $snapshotPattern) {
    throw "Required logcat pattern missing: $snapshotPattern"
}

$postRecreateSnapshotPattern = 'Post-recreate snapshot\s+\d+x\d+\s+nonblank=true'
if ($logText -notmatch $postRecreateSnapshotPattern) {
    throw "Required logcat pattern missing: $postRecreateSnapshotPattern"
}

$tilesZoomSnapshotPattern = '3D Tiles zoom snapshot\s+\d+x\d+\s+nonblank=true'
if ($logText -notmatch $tilesZoomSnapshotPattern) {
    throw "Required logcat pattern missing: $tilesZoomSnapshotPattern"
}

$baseMapPatterns = @(
    'raster sample-base-imagery loaded'
)
foreach ($pattern in $baseMapPatterns) {
    if ($logText -notmatch $pattern) {
        throw "Required logcat pattern missing: $pattern"
    }
}

$tiles3dRuntimePatterns = @(
    'tiles3d sample-3d-tiles-layer loaded',
    'registered 3D Tiles renderer content id=sample-3d-tiles-layer:',
    'applied native 3D Tiles content id=sample-3d-tiles-layer:',
    'registered 3D Tiles renderer content id=sample-3d-tiles-layer:.*dragon_high\.b3dm\.glb',
    'applied native 3D Tiles content id=sample-3d-tiles-layer:.*dragon_high\.b3dm\.glb',
    '3D Tiles model loaded id=sample-3d-tiles-layer:',
    'removed 3D Tiles renderer content id=sample-3d-tiles-layer:',
    'tiles3d sample-3d-tiles-layer removed',
    'tiles3d sample-3d-tiles-layer-bad failed:'
)
foreach ($pattern in $tiles3dRuntimePatterns) {
    if ($logText -notmatch $pattern) {
        throw "Required logcat pattern missing: $pattern"
    }
}
$tiles3dLoadedPattern = 'tiles3d sample-3d-tiles-layer loaded'
if ([regex]::Matches($logText, $tiles3dLoadedPattern).Count -lt 2) {
    throw "Required logcat pattern missing at least twice: $tiles3dLoadedPattern"
}

$mvtMbTilesPatterns = @(
    'MVT MBTiles smoke: requested file=Vectorra Sample MVT',
    'registered MVT render tile handle=.*source=sample-mvt-mbtiles.*features=[1-9]\d*.*visible=1',
    'applied MVT render tile handle=.*sample-mvt-transportation.*entities=[1-9]\d*'
)
foreach ($pattern in $mvtMbTilesPatterns) {
    if ($logText -notmatch $pattern) {
        throw "Required logcat pattern missing: $pattern"
    }
}

$mvtRuntimePatterns = @(
    'registered MVT render tile handle=.*source=sample-mvt.*features=[1-9]\d*.*visible=1',
    'applied MVT render tile handle=.*sample-mvt-transportation.*entities=[1-9]\d*',
    'MVT smoke: camera pan lon=-122\.3',
    'MVT pan center query: Click: [1-9]\d* feature\(s\).*layer=sample-mvt-transportation.*source=sample-mvt.*source-layer=transportation',
    'MVT hidden center query: Click: no features',
    'MVT smoke: removed layer',
    'vector sample-mvt-transportation removed',
    'MVT removed center query: Click: no features',
    'MVT readd center query: Click: [1-9]\d* feature\(s\).*layer=sample-mvt-transportation.*source=sample-mvt.*source-layer=transportation',
    'vector sample-mvt-transportation loaded'
)
foreach ($pattern in $mvtRuntimePatterns) {
    if ($logText -notmatch $pattern) {
        throw "Required logcat pattern missing: $pattern"
    }
}

$offlinePrefetchPatterns = @(
    'Offline prefetch smoke: cache before entries=0 bytes=0 proxy=0/0 resources=0/0',
    'Offline prefetch progress state=RUNNING finished=[1-9]\d*/[1-9]\d* failed=0 bytes=[1-9]\d*',
    'Offline prefetch result status=SUCCESS completed=[1-9]\d* failed=0 bytes=[1-9]\d* state=COMPLETED',
    'Offline prefetch smoke: cache after entries=[1-9]\d* bytes=[1-9]\d* proxy=\d+/\d+ resources=[1-9]\d*/[1-9]\d*',
    'Offline prefetch smoke: cancel requested=true',
    'Offline prefetch result status=SUCCESS completed=[1-9]\d* failed=0 bytes=[1-9]\d* state=CANCELED'
)
foreach ($pattern in $offlinePrefetchPatterns) {
    if ($logText -notmatch $pattern) {
        throw "Required logcat pattern missing: $pattern"
    }
}
$cacheClearedPattern = 'Offline prefetch smoke: cache cleared entries=0 bytes=0 proxy=0/0 resources=0/0'
if ([regex]::Matches($logText, $cacheClearedPattern).Count -lt 2) {
    throw "Required logcat pattern missing at least twice: $cacheClearedPattern"
}

$sampleInteractionPatterns = @(
    'GeoJSON smoke: source=sample-geojson features=2',
    'GeoJSON center query: Click: [1-9]\d* feature\(s\).*layer=sample-geojson-layer.*source=sample-geojson.*name=GeoJSON point',
    'Draw smoke: point line polygon requested',
    'Draw center query: Click: [1-9]\d* feature\(s\).*layer=draw-point',
    'Draw smoke: cleared',
    'Location smoke: indicator requested',
    'Location smoke: follow heading requested',
    'Location smoke: cleared'
)
foreach ($pattern in $sampleInteractionPatterns) {
    if ($logText -notmatch $pattern) {
        throw "Required logcat pattern missing: $pattern"
    }
}

$failurePatterns = @(
    'FATAL EXCEPTION',
    'SIGSEGV',
    'SIGABRT',
    'failed to start rocky renderer',
    'Vectorra renderer startup failed',
    'ANR in com\.vectorra\.sample'
)
foreach ($pattern in $failurePatterns) {
    if ($logText -match $pattern) {
        throw "Failure pattern found in logcat: $pattern"
    }
}

Write-Host "Device smoke result check passed: $reportPath"
