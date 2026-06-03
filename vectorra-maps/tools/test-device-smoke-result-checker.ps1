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

$validLogText = @(
    "VectorraSample smoke completed",
    "Snapshot 1080x1920 nonblank=true",
    "Post-recreate snapshot 1080x1920 nonblank=true",
    "3D Tiles zoom snapshot 1080x1920 nonblank=true",
    "MVT MBTiles smoke: requested file=Vectorra Sample MVT",
    "registered MVT render tile handle=sample-mvt-transportation:12/655/1583 source=sample-mvt-mbtiles style=sample-mvt-transportation features=1 coordinates=3 visible=1",
    "applied MVT render tile handle=sample-mvt-transportation:12/655/1583 entities=1",
    "MVT MBTiles center query: Click: 1 feature(s) layer=sample-mvt-transportation source=sample-mvt-mbtiles source-layer=transportation name=Offline MBTiles",
    "Offline prefetch smoke: cache before entries=0 bytes=0 proxy=0/0 resources=0/0",
    "Offline prefetch progress state=RUNNING finished=1/1 failed=0 bytes=256",
    "Offline prefetch result status=SUCCESS completed=1 failed=0 bytes=256 state=COMPLETED",
    "Offline prefetch smoke: cache after entries=1 bytes=256 proxy=0/0 resources=1/256",
    "Offline prefetch smoke: cache cleared entries=0 bytes=0 proxy=0/0 resources=0/0",
    "Offline prefetch smoke: cancel requested=true",
    "Offline prefetch result status=SUCCESS completed=1 failed=0 bytes=256 state=CANCELED",
    "Offline prefetch smoke: cache after entries=1 bytes=256 proxy=0/0 resources=1/256",
    "Offline prefetch smoke: cache cleared entries=0 bytes=0 proxy=0/0 resources=0/0",
    "GeoJSON smoke: source=sample-geojson features=2",
    "GeoJSON center query: Click: 1 feature(s) layer=sample-geojson-layer source=sample-geojson name=GeoJSON point",
    "Draw smoke: point line polygon requested",
    "Draw center query: Click: 1 feature(s) layer=draw-point",
    "Draw smoke: cleared",
    "Location smoke: indicator requested",
    "Location smoke: follow heading requested",
    "Location smoke: cleared"
) -join "`n"

function New-SmokeFixture {
    param(
        [string]$Stamp,
        [string]$LogText = $validLogText,
        [string]$OmitAction = "",
        [switch]$InvalidPng,
        [string]$OmitMetadata = "",
        [string]$OmitArtifact = "",
        [string]$MismatchedArtifact = "",
        [string]$InstalledApk = "vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk",
        [string]$Abis = "arm64-v8a,armeabi-v7a",
        [switch]$OmitPostRecreateSnapshot
    )

    $reportPath = Join-Path $testRoot "device-smoke-$Stamp.txt"
    $lines = @()
    $metadataLines = @(
        "installApk=vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk",
        "serial=test-device",
        "installedApk=$InstalledApk",
        "model=Vectorra Test Device",
        "sdk=35",
        "abis=$Abis",
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
        "startSampleEnd=cold-start"
    )
    foreach ($action in $requiredActions) {
        if ($action -ne $OmitAction) {
            $lines += "actionStart=$action delaySeconds=1"
            $lines += "actionEnd=$action"
        }
    }
    $lines += @(
        "lifecycleStart=pause-home delaySeconds=4",
        "lifecycleEnd=pause-home",
        "startSample=resume-after-home delaySeconds=10",
        "startSampleEnd=resume-after-home",
        "lifecycleStart=destroy-force-stop delaySeconds=4",
        "lifecycleEnd=destroy-force-stop",
        "startSample=recreate-after-force-stop delaySeconds=10",
        "startSampleEnd=recreate-after-force-stop"
    )
    if (-not $OmitPostRecreateSnapshot) {
        $lines += @(
            "actionStart=post-recreate-snapshot delaySeconds=1",
            "actionEnd=post-recreate-snapshot"
        )
    }
    if ($OmitArtifact -ne "screenshot") {
        $path = if ($MismatchedArtifact -eq "screenshot") {
            Join-Path $testRoot "wrong-vectorra-smoke-$Stamp.png"
        } else {
            Join-Path $testRoot "vectorra-smoke-$Stamp.png"
        }
        $lines += "screenshot=$path bytes=4"
    }
    if ($OmitArtifact -ne "uiDump") {
        $path = if ($MismatchedArtifact -eq "uiDump") {
            Join-Path $testRoot "wrong-vectorra-smoke-$Stamp.xml"
        } else {
            Join-Path $testRoot "vectorra-smoke-$Stamp.xml"
        }
        $lines += "uiDump=$path bytes=64"
    }
    if ($OmitArtifact -ne "logcat") {
        $path = if ($MismatchedArtifact -eq "logcat") {
            Join-Path $testRoot "wrong-device-smoke-$Stamp.log"
        } else {
            Join-Path $testRoot "device-smoke-$Stamp.log"
        }
        $lines += "logcat=$path bytes=32"
    }
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

$crashReport = New-SmokeFixture "20260604-000001" "$validLogText`nAndroidRuntime FATAL EXCEPTION"
Invoke-CheckerFailure $crashReport "crash log"

$missingActionReport = New-SmokeFixture "20260604-000002" $validLogText "cancel-prefetch"
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

$missing3DTilesZoomSnapshotReport = New-SmokeFixture -Stamp "20260604-000007" -LogText "VectorraSample smoke completed`nSnapshot 1080x1920 nonblank=true`nPost-recreate snapshot 1080x1920 nonblank=true"
Invoke-CheckerFailure $missing3DTilesZoomSnapshotReport "missing 3D Tiles zoom snapshot"

$missingMvtMbTilesQueryReport = New-SmokeFixture -Stamp "20260604-000008" -LogText "VectorraSample smoke completed`nSnapshot 1080x1920 nonblank=true`nPost-recreate snapshot 1080x1920 nonblank=true`n3D Tiles zoom snapshot 1080x1920 nonblank=true`nMVT MBTiles smoke: requested file=Vectorra Sample MVT`nregistered MVT render tile handle=sample-mvt-transportation:12/655/1583 source=sample-mvt-mbtiles style=sample-mvt-transportation features=1 coordinates=3 visible=1`napplied MVT render tile handle=sample-mvt-transportation:12/655/1583 entities=1"
Invoke-CheckerFailure $missingMvtMbTilesQueryReport "missing MVT MBTiles query"

$missingOfflineCleanupReport = New-SmokeFixture -Stamp "20260604-000009" -LogText ($validLogText -replace 'Offline prefetch smoke: cache cleared entries=0 bytes=0 proxy=0/0 resources=0/0', 'Offline prefetch smoke: cache cleared entries=1 bytes=256 proxy=0/0 resources=1/256')
Invoke-CheckerFailure $missingOfflineCleanupReport "missing offline prefetch cleanup"

$missingSampleInteractionReport = New-SmokeFixture -Stamp "20260604-000010" -LogText ($validLogText -replace 'Draw center query: Click: 1 feature\(s\) layer=draw-point', 'Draw center query: Click: no features')
Invoke-CheckerFailure $missingSampleInteractionReport "missing sample interaction query"

$missingArtifactReport = New-SmokeFixture -Stamp "20260604-000011" -OmitArtifact "logcat"
Invoke-CheckerFailure $missingArtifactReport "missing artifact report line"

$mismatchedArtifactReport = New-SmokeFixture -Stamp "20260604-000012" -MismatchedArtifact "screenshot"
Invoke-CheckerFailure $mismatchedArtifactReport "mismatched artifact report path"

$mismatchedInstalledApkReport = New-SmokeFixture `
    -Stamp "20260604-000013" `
    -InstalledApk "vectorra-sample/build/outputs/apk/debug/vectorra-sample-x86_64-debug.apk"
Invoke-CheckerFailure $mismatchedInstalledApkReport "mismatched installed APK"

$incompatibleApkAbiReport = New-SmokeFixture `
    -Stamp "20260604-000014" `
    -InstalledApk "vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk" `
    -Abis "x86_64"
Invoke-CheckerFailure $incompatibleApkAbiReport "incompatible APK ABI"

$outOfOrderReport = New-SmokeFixture -Stamp "20260604-000015"
(Get-Content -Path $outOfOrderReport -Raw) `
    -replace 'actionStart=mvt delaySeconds=1\r?\nactionEnd=mvt', "actionEnd=mvt`nactionStart=mvt delaySeconds=1" |
    Set-Content -Path $outOfOrderReport -Encoding utf8
Invoke-CheckerFailure $outOfOrderReport "out-of-order action markers"

$missingPostRecreateSnapshotReport = New-SmokeFixture -Stamp "20260604-000016" -OmitPostRecreateSnapshot
Invoke-CheckerFailure $missingPostRecreateSnapshotReport "missing post-recreate snapshot"

$missingPostRecreateSnapshotLogReport = New-SmokeFixture -Stamp "20260604-000017" -LogText "VectorraSample smoke completed`nSnapshot 1080x1920 nonblank=true`n3D Tiles zoom snapshot 1080x1920 nonblank=true"
Invoke-CheckerFailure $missingPostRecreateSnapshotLogReport "missing post-recreate snapshot log"

Write-Host "Device smoke result checker self-test passed."
