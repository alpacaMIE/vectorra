$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot "run-device-smoke.ps1"
$checker = Join-Path $PSScriptRoot "check-device-smoke-result.ps1"
$checkerTest = Join-Path $PSScriptRoot "test-device-smoke-result-checker.ps1"
$sample = Join-Path $repoRoot "vectorra-sample/src/main/java/com/vectorra/sample/MainActivity.kt"
$abiMatrixDoc = Join-Path $repoRoot "docs/beta/abi-device-matrix.md"

foreach ($path in @($runner, $checker, $checkerTest, $sample, $abiMatrixDoc)) {
    if (-not (Test-Path $path)) {
        throw "Required smoke contract file not found: $path"
    }
}

function Get-NamedArrayStrings {
    param(
        [string]$Text,
        [string]$VariableName
    )

    $pattern = '(?s)\$' + [regex]::Escape($VariableName) + '\s*=\s*@\((.*?)\)'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        throw "Could not find array `$${VariableName}"
    }
    return @([regex]::Matches($match.Groups[1].Value, '"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
}

function Assert-SameSequence {
    param(
        [string[]]$Expected,
        [string[]]$Actual,
        [string]$Label
    )

    if ($Expected.Count -ne $Actual.Count) {
        throw "$Label count mismatch. Expected $($Expected.Count), actual $($Actual.Count). Expected=$($Expected -join ',') Actual=$($Actual -join ',')"
    }
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        if ($Expected[$index] -ne $Actual[$index]) {
            throw "$Label mismatch at index $index. Expected=$($Expected[$index]) Actual=$($Actual[$index])"
        }
    }
}

$runnerText = Get-Content -Path $runner -Raw
$checkerText = Get-Content -Path $checker -Raw
$checkerTestText = Get-Content -Path $checkerTest -Raw
$sampleText = Get-Content -Path $sample -Raw
$abiMatrixText = Get-Content -Path $abiMatrixDoc -Raw

$runnerActionsBlock = [regex]::Match($runnerText, '(?s)\$actions\s*=\s*@\((.*?)\)')
if (-not $runnerActionsBlock.Success) {
    throw "Could not find runner `$actions block"
}
$runnerActions = @([regex]::Matches($runnerActionsBlock.Groups[1].Value, '@\{\s*name\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
$checkerActions = Get-NamedArrayStrings $checkerText "requiredActions"
$checkerTestActions = Get-NamedArrayStrings $checkerTestText "requiredActions"

Assert-SameSequence $runnerActions $checkerActions "runner/checker required action sequence"
Assert-SameSequence $runnerActions $checkerTestActions "runner/checker-test required action sequence"

$sampleActions = @([regex]::Matches($sampleText, 'const\s+val\s+SAMPLE_ACTION_[A-Z0-9_]+\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
foreach ($action in $runnerActions + @("post-recreate-snapshot")) {
    if ($sampleActions -notcontains $action) {
        throw "Sample MainActivity missing smoke action constant: $action"
    }
}

if ($runnerActions -contains "post-recreate-snapshot") {
    throw "post-recreate-snapshot must remain a post-lifecycle action, not part of the main runner action array"
}
if ($runnerText -notmatch 'Smoke-Action\s+"post-recreate-snapshot"\s+4') {
    throw "run-device-smoke.ps1 missing post-recreate-snapshot smoke action after recreate"
}
if ($checkerText -notmatch 'actionStart=post-recreate-snapshot' -or $checkerText -notmatch 'actionEnd=post-recreate-snapshot') {
    throw "check-device-smoke-result.ps1 missing post-recreate-snapshot ordered markers"
}
if ($checkerText -notmatch 'Post-recreate snapshot\\s\+\\d\+x\\d\+\\s\+nonblank=true') {
    throw "check-device-smoke-result.ps1 missing post-recreate snapshot log requirement"
}
if ($checkerText -notmatch 'tiles3d sample-3d-tiles-layer loaded' -or
    $checkerText -notmatch 'registered 3D Tiles renderer content id=sample-3d-tiles-layer:' -or
    $checkerText -notmatch 'tiles3d sample-3d-tiles-layer-bad failed:') {
    throw "check-device-smoke-result.ps1 missing 3D Tiles runtime log requirements"
}
if ($checkerTestText -notmatch 'missing 3D Tiles readd loaded evidence') {
    throw "test-device-smoke-result-checker.ps1 missing 3D Tiles readd failure fixture"
}
if ($checkerText -notmatch 'MVT MBTiles smoke: requested file=Vectorra Sample MVT' -or
    $checkerText -notmatch 'source=sample-mvt-mbtiles' -or
    $checkerText -notmatch 'MVT MBTiles center query: Click:') {
    throw "check-device-smoke-result.ps1 missing MVT MBTiles render/query log requirements"
}
if ($checkerTestText -notmatch 'missing MVT MBTiles query') {
    throw "test-device-smoke-result-checker.ps1 missing MVT MBTiles failure fixture"
}
if ($checkerText -notmatch 'MVT pan center query: Click:' -or
    $checkerText -notmatch 'MVT hidden center query: Click: no features' -or
    $checkerText -notmatch 'MVT readd center query: Click:') {
    throw "check-device-smoke-result.ps1 missing MVT pan/hidden/readd log requirements"
}
if ($checkerTestText -notmatch 'missing MVT hidden no-features evidence') {
    throw "test-device-smoke-result-checker.ps1 missing MVT hidden failure fixture"
}
if ($checkerText -notmatch 'Offline prefetch result status=SUCCESS' -or
    $checkerText -notmatch 'state=CANCELED' -or
    $checkerText -notmatch 'cache cleared entries=0') {
    throw "check-device-smoke-result.ps1 missing offline prefetch result/cancel/cleanup log requirements"
}
if ($checkerTestText -notmatch 'missing offline prefetch cleanup') {
    throw "test-device-smoke-result-checker.ps1 missing offline prefetch cleanup failure fixture"
}
if ($checkerText -notmatch 'GeoJSON center query: Click:' -or
    $checkerText -notmatch 'Draw center query: Click:' -or
    $checkerText -notmatch 'Location smoke: follow heading requested') {
    throw "check-device-smoke-result.ps1 missing GeoJSON/Draw/Location interaction log requirements"
}
if ($checkerTestText -notmatch 'missing sample interaction query') {
    throw "test-device-smoke-result-checker.ps1 missing sample interaction failure fixture"
}
if (-not $abiMatrixText.Contains(".\tools\test-device-smoke-contract.ps1")) {
    throw "ABI/device matrix missing test-device-smoke-contract.ps1 runtime gate command"
}

Write-Host "Device smoke script contract self-test passed."
