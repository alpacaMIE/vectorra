param(
    [string]$AndroidHome = "C:\Users\myg\AppData\Local\Android\Sdk",
    [string]$DeviceSerial = "",
    [string]$GradleUserHome = ".\.gradle-agent-home"
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

Push-Location $repoRoot
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_SDK_ROOT = $AndroidHome
    $env:ANDROID_SERIAL = $DeviceSerial

    $gradleArgs = @(
        "-g",
        $GradleUserHome,
        ":vectorra-maps:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.vectorra.maps.offline.VectorraMbTilesVectorSourceInstrumentedTest"
    )
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle connectedDebugAndroidTest failed with exit code $LASTEXITCODE"
    }

    Write-Host "MBTiles vector instrumentation test passed on $DeviceSerial."
}
finally {
    Pop-Location
}
