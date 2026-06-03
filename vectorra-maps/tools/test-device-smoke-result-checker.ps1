param(
    [string]$OutputDirectory = "build/device-smoke-checker-test"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$checker = Join-Path $PSScriptRoot "check-device-smoke-result.ps1"
if (-not (Test-Path $checker)) {
    throw "Smoke result checker not found: $checker"
}

$testRoot = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else {
    Join-Path $repoRoot $OutputDirectory
}
if (Test-Path $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

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

function New-SmokeFixture {
    param(
        [string]$Stamp,
        [string]$LogText = "VectorraSample smoke completed`nSnapshot 1080x1920 nonblank=true",
        [string]$OmitAction = "",
        [switch]$InvalidPng,
        [string]$OmitMetadata = ""
    )

    $reportPath = Join-Path $testRoot "device-smoke-$Stamp.txt"
    $lines = @()
    $metadataLines = @(
        "installApk=vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk",
        "serial=test-device",
        "installedApk=vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk",
        "model=Vectorra Test Device",
        "sdk=35",
        "abis=arm64-v8a,armeabi-v7a",
        "gpu=Vulkan 1.3 test renderer",
        "vulkan=Vulkan API 1.3.278"
    )
    foreach ($line in $metadataLines) {
        if ($line -notmatch "^$([regex]::Escape($OmitMetadata))=") {
            $lines += $line
        }
    }
    $lines += @(
        "logcatCleared=true",
        "forceStopBeforeColdStart=true",
        "startSample=cold-start delaySeconds=10",
        "startSampleEnd=cold-start",
        "lifecycleStart=pause-home delaySeconds=4",
        "lifecycleEnd=pause-home",
        "startSample=resume-after-home delaySeconds=10",
        "startSampleEnd=resume-after-home",
        "lifecycleStart=destroy-force-stop delaySeconds=4",
        "lifecycleEnd=destroy-force-stop",
        "startSample=recreate-after-force-stop delaySeconds=10",
        "startSampleEnd=recreate-after-force-stop"
    )
    foreach ($action in $requiredActions) {
        if ($action -ne $OmitAction) {
            $lines += "actionStart=$action delaySeconds=1"
            $lines += "actionEnd=$action"
        }
    }
    $lines += "screenshot=$testRoot\vectorra-smoke-$Stamp.png bytes=4"
    $lines += "uiDump=$testRoot\vectorra-smoke-$Stamp.xml bytes=64"
    $lines += "logcat=$testRoot\device-smoke-$Stamp.log bytes=32"
    $lines += "uiDumpContainsPackage=com.vectorra.sample"

    Set-Content -Path $reportPath -Value $lines -Encoding utf8
    $pngPath = Join-Path $testRoot "vectorra-smoke-$Stamp.png"
    if ($InvalidPng) {
        [System.IO.File]::WriteAllBytes($pngPath, [byte[]](1, 2, 3, 4))
    } else {
        [System.IO.File]::WriteAllBytes(
            $pngPath,
            [byte[]](
                0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d,
                0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00,
                0x90, 0x77, 0x53, 0xde,
                0x00, 0x00, 0x00, 0x0c,
                0x49, 0x44, 0x41, 0x54,
                0x08, 0xd7, 0x63, 0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00,
                0x18, 0xdd, 0x8d, 0xb0,
                0x00, 0x00, 0x00, 0x00,
                0x49, 0x45, 0x4e, 0x44,
                0xae, 0x42, 0x60, 0x82
            )
        )
    }
    Set-Content -Path (Join-Path $testRoot "vectorra-smoke-$Stamp.xml") -Value '<node package="com.vectorra.sample" />' -Encoding utf8
    Set-Content -Path (Join-Path $testRoot "device-smoke-$Stamp.log") -Value $LogText -Encoding utf8
    return $reportPath
}

function Invoke-CheckerSuccess {
    param([string]$Report)
    & $checker -SmokeDirectory $testRoot -Report $Report | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Checker was expected to pass but failed: $Report"
    }
}

function Invoke-CheckerFailure {
    param([string]$Report, [string]$Label)
    $failed = $false
    try {
        & $checker -SmokeDirectory $testRoot -Report $Report | Out-Host
    } catch {
        $failed = $true
        Write-Host "Expected failure for ${Label}: $($_.Exception.Message)"
    }
    if (-not $failed) {
        throw "Checker was expected to fail for $Label but passed: $Report"
    }
}

$validReport = New-SmokeFixture "20260604-000000"
Invoke-CheckerSuccess $validReport

$crashReport = New-SmokeFixture "20260604-000001" "VectorraSample smoke completed`nSnapshot 1080x1920 nonblank=true`nAndroidRuntime FATAL EXCEPTION"
Invoke-CheckerFailure $crashReport "crash log"

$missingActionReport = New-SmokeFixture "20260604-000002" "VectorraSample smoke completed" "cancel-prefetch"
Invoke-CheckerFailure $missingActionReport "missing action"

$invalidPngReport = New-SmokeFixture -Stamp "20260604-000003" -InvalidPng
Invoke-CheckerFailure $invalidPngReport "invalid png"

$missingMetadataReport = New-SmokeFixture -Stamp "20260604-000004" -OmitMetadata "gpu"
Invoke-CheckerFailure $missingMetadataReport "missing metadata"

$emptyMetadataReport = New-SmokeFixture -Stamp "20260604-000005"
(Get-Content -Path $emptyMetadataReport -Raw) -replace '(?m)^vulkan=.*$', 'vulkan=' |
    Set-Content -Path $emptyMetadataReport -Encoding utf8
Invoke-CheckerFailure $emptyMetadataReport "empty metadata"

$blankSnapshotReport = New-SmokeFixture -Stamp "20260604-000006" -LogText "VectorraSample smoke completed`nSnapshot 1080x1920 nonblank=false"
Invoke-CheckerFailure $blankSnapshotReport "blank snapshot"

Write-Host "Device smoke result checker self-test passed."
