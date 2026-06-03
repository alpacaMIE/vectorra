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
        [string]$LogText = "VectorraSample smoke completed",
        [string]$OmitAction = ""
    )

    $reportPath = Join-Path $testRoot "device-smoke-$Stamp.txt"
    $lines = @(
        "installApk=vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk",
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
    [System.IO.File]::WriteAllBytes((Join-Path $testRoot "vectorra-smoke-$Stamp.png"), [byte[]](1, 2, 3, 4))
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

$crashReport = New-SmokeFixture "20260604-000001" "AndroidRuntime FATAL EXCEPTION"
Invoke-CheckerFailure $crashReport "crash log"

$missingActionReport = New-SmokeFixture "20260604-000002" "VectorraSample smoke completed" "cancel-prefetch"
Invoke-CheckerFailure $missingActionReport "missing action"

Write-Host "Device smoke result checker self-test passed."
