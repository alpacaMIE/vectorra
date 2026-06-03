param(
    [string]$AndroidHome = "C:\Users\myg\AppData\Local\Android\Sdk",
    [string]$DeviceSerial = "emulator-5554",
    [string]$OutputDirectory = "build/device-smoke",
    [int]$ActionDelaySeconds = 8
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $AndroidHome "platform-tools/adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb"
}

if ($DeviceSerial -notmatch '^emulator-') {
    throw "Emulator smoke gate requires an emulator serial such as emulator-5554. Use run-device-smoke.ps1 for physical devices."
}

$devices = & $adb devices -l
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed with exit code ${LASTEXITCODE}: $($devices -join ' | ')"
}

$deviceLines = $devices | Where-Object { $_ -match '^\S+\s+\S+' -and $_ -notmatch '^List of devices' }
$selectedLine = $deviceLines | Where-Object { $_ -match "^$([regex]::Escape($DeviceSerial))\s+" } | Select-Object -First 1
if (-not $selectedLine) {
    throw "Device $DeviceSerial not found. adb devices: $($devices -join ' | ')"
}
if ($selectedLine -notmatch "^\S+\s+device(\s|$)") {
    throw "Device $DeviceSerial is not online: $selectedLine"
}

$smokeRoot = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else {
    Join-Path $repoRoot $OutputDirectory
}

Push-Location $repoRoot
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_SDK_ROOT = $AndroidHome

    & (Join-Path $PSScriptRoot "run-device-smoke.ps1") `
        -AndroidHome $AndroidHome `
        -DeviceSerial $DeviceSerial `
        -OutputDirectory $OutputDirectory `
        -ActionDelaySeconds $ActionDelaySeconds

    $latest = Get-ChildItem -Path $smokeRoot -Filter "device-smoke-*.txt" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latest) {
        throw "No device smoke report found under $smokeRoot"
    }

    & (Join-Path $PSScriptRoot "check-device-smoke-result.ps1") `
        -SmokeDirectory $OutputDirectory `
        -Report $latest.FullName

    Write-Host "Emulator smoke gate passed on $DeviceSerial. Report: $($latest.FullName)"
}
finally {
    Pop-Location
}
