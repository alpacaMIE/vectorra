$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot "run-mbtiles-vector-instrumentation.ps1"
$acceptanceDoc = Join-Path $repoRoot "docs/beta/android-1.0-acceptance.md"
$releaseNotes = Join-Path $repoRoot "docs/beta/release-notes-0.8.0-beta.1.md"

foreach ($path in @($runner, $acceptanceDoc, $releaseNotes)) {
    if (-not (Test-Path $path)) {
        throw "Required MBTiles instrumentation runner contract file not found: $path"
    }
}

$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($runner, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors) {
    throw "PowerShell parser errors in ${runner}: $($errors | Out-String)"
}

$runnerText = Get-Content -Path $runner -Raw
$acceptanceText = Get-Content -Path $acceptanceDoc -Raw
$releaseNotesText = Get-Content -Path $releaseNotes -Raw

if ($runnerText -notmatch 'param\(' -or
    $runnerText -notmatch '\[string\]\$AndroidHome' -or
    $runnerText -notmatch '\[string\]\$DeviceSerial' -or
    $runnerText -notmatch '\[string\]\$GradleUserHome') {
    throw "MBTiles instrumentation runner missing required parameters"
}

if ($runnerText -notmatch 'adb devices -l' -or
    $runnerText -notmatch 'Expected exactly one online adb device' -or
    $runnerText -notmatch 'Device \$DeviceSerial is not online') {
    throw "MBTiles instrumentation runner missing adb online-device guards"
}

if ($runnerText -notmatch '\$env:ANDROID_SERIAL\s*=\s*\$DeviceSerial') {
    throw "MBTiles instrumentation runner must set ANDROID_SERIAL from the selected device"
}

if ($runnerText -notmatch ':vectorra-maps:connectedDebugAndroidTest' -or
    $runnerText -notmatch 'com\.vectorra\.maps\.offline\.VectorraMbTilesVectorSourceInstrumentedTest') {
    throw "MBTiles instrumentation runner missing targeted connectedDebugAndroidTest invocation"
}

if ($acceptanceText -notmatch '\.\\tools\\run-mbtiles-vector-instrumentation\.ps1 -DeviceSerial emulator-5554') {
    throw "Android 1.0 acceptance doc missing MBTiles instrumentation runner command"
}

if ($releaseNotesText -notmatch '\.\\tools\\run-mbtiles-vector-instrumentation\.ps1 -DeviceSerial emulator-5554') {
    throw "0.8.0 beta release notes missing MBTiles instrumentation runner command"
}

Write-Host "MBTiles vector instrumentation runner contract self-test passed."
