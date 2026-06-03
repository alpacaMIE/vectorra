$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot "run-emulator-smoke-gate.ps1"
$deviceRunner = Join-Path $PSScriptRoot "run-device-smoke.ps1"
$checker = Join-Path $PSScriptRoot "check-device-smoke-result.ps1"
$acceptanceScript = Join-Path $PSScriptRoot "check-android-acceptance.ps1"
$acceptanceDoc = Join-Path $repoRoot "docs/beta/android-1.0-acceptance.md"
$abiMatrix = Join-Path $repoRoot "docs/beta/abi-device-matrix.md"
$releaseVersioning = Join-Path $repoRoot "docs/beta/release-versioning.md"
$releaseNotes = Join-Path $repoRoot "docs/beta/release-notes-0.8.0-beta.1.md"

foreach ($path in @($runner, $deviceRunner, $checker, $acceptanceScript, $acceptanceDoc, $abiMatrix, $releaseVersioning, $releaseNotes)) {
    if (-not (Test-Path $path)) {
        throw "Required emulator smoke gate contract file not found: $path"
    }
}

foreach ($script in @($runner, $deviceRunner, $checker, $acceptanceScript)) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($script, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors) {
        throw "PowerShell parser errors in ${script}: $($errors | Out-String)"
    }
}

$runnerText = Get-Content -Path $runner -Raw
$acceptanceScriptText = Get-Content -Path $acceptanceScript -Raw
$acceptanceText = Get-Content -Path $acceptanceDoc -Raw
$abiMatrixText = Get-Content -Path $abiMatrix -Raw
$releaseVersioningText = Get-Content -Path $releaseVersioning -Raw
$releaseNotesText = Get-Content -Path $releaseNotes -Raw

if ($runnerText -notmatch 'param\(' -or
    $runnerText -notmatch '\[string\]\$AndroidHome' -or
    $runnerText -notmatch '\[string\]\$DeviceSerial' -or
    $runnerText -notmatch '\[string\]\$OutputDirectory' -or
    $runnerText -notmatch '\[int\]\$ActionDelaySeconds') {
    throw "Emulator smoke gate runner missing required parameters"
}

if ($runnerText -notmatch 'adb devices -l' -or
    $runnerText -notmatch 'Device \$DeviceSerial is not online' -or
    $runnerText -notmatch '\^emulator-') {
    throw "Emulator smoke gate runner missing adb online-device or emulator guards"
}

if ($runnerText -notmatch 'run-device-smoke\.ps1' -or
    $runnerText -notmatch 'check-device-smoke-result\.ps1' -or
    $runnerText -notmatch '-DeviceSerial \$DeviceSerial' -or
    $runnerText -notmatch '-ActionDelaySeconds \$ActionDelaySeconds' -or
    $runnerText -notmatch 'device-smoke-\*\.txt' -or
    $runnerText -notmatch 'Emulator smoke gate passed') {
    throw "Emulator smoke gate runner does not wrap smoke execution, latest report selection, and result checking"
}

if ($acceptanceScriptText -notmatch 'test-emulator-smoke-gate\.ps1') {
    throw "Android local acceptance script does not run the emulator smoke gate contract self-test"
}

if ($acceptanceText -notmatch '\.\\tools\\run-emulator-smoke-gate\.ps1 -DeviceSerial emulator-5554' -or
    $abiMatrixText -notmatch '\.\\tools\\run-emulator-smoke-gate\.ps1 -DeviceSerial emulator-5554') {
    throw "Acceptance docs missing emulator smoke gate command"
}

if ($releaseVersioningText -notmatch 'emulator smoke gate contract self-test' -or
    $releaseNotesText -notmatch 'emulator smoke gate contract self-test') {
    throw "Release docs missing emulator smoke gate contract self-test coverage"
}

Write-Host "Emulator smoke gate contract self-test passed."
