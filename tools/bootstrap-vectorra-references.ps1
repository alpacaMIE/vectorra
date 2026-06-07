param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot "vectorra-references.manifest.json")
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

function Get-RepoDirName {
    param([string]$Url)

    $leaf = [System.IO.Path]::GetFileName($Url.TrimEnd('/'))
    return [System.IO.Path]::GetFileNameWithoutExtension($leaf)
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$refsRoot = Join-Path $repoRoot "vectorra-references"

if (-not (Test-Path $ManifestPath)) {
    throw "Manifest not found: $ManifestPath"
}

$manifest = Get-Content -Raw -Path $ManifestPath | ConvertFrom-Json
if (-not $manifest.repos) {
    throw "Manifest has no repos: $ManifestPath"
}

New-Item -ItemType Directory -Force -Path $refsRoot | Out-Null

Write-Host "Cloning $($manifest.repos.Count) reference repos into $refsRoot"
Write-Host ""

Push-Location $refsRoot
try {
    foreach ($repo in $manifest.repos) {
        $dir = if ($repo.dir) { $repo.dir } else { Get-RepoDirName $repo.url }
        if (Test-Path $dir) {
            Write-Host "Removing existing $dir"
            Remove-Item -Path $dir -Recurse -Force
        }

        $cloneArgs = @("clone", "-b", $repo.ref)
        if ($repo.depth) {
            $cloneArgs += @("--depth", [string]$repo.depth)
        }
        $cloneArgs += @($repo.url, $dir)

        Write-Host "Cloning $dir ($($repo.ref)) ..."
        Invoke-Checked "git" $cloneArgs
        Write-Host ""
    }
}
finally {
    Pop-Location
}

Write-Host "Verification:"
Write-Host ("{0,-40} {1,-50} {2,-24} {3}" -f "DIR", "REMOTE", "REF", "HEAD")
Write-Host ("-" * 120)

$rows = @()
foreach ($repo in $manifest.repos) {
    $dir = if ($repo.dir) { $repo.dir } else { Get-RepoDirName $repo.url }
    $repoPath = Join-Path $refsRoot $dir

    if (-not (Test-Path (Join-Path $repoPath ".git"))) {
        throw "Missing git repo: $repoPath"
    }

    Push-Location $repoPath
    try {
        $remote = (git remote get-url origin 2>$null)
        $branch = (git branch --show-current 2>$null)
        $tag = (git describe --tags --exact-match 2>$null)
        $head = (git rev-parse --short HEAD 2>$null)
        $refLabel = if ($tag) { $tag } elseif ($branch) { $branch } else { "(detached)" }
        $rows += [PSCustomObject]@{
            Dir    = $dir
            Remote = $remote
            Ref    = $refLabel
            Head   = $head
        }
        Write-Host ("{0,-40} {1,-50} {2,-24} {3}" -f $dir, $remote, $refLabel, $head)
    }
    finally {
        Pop-Location
    }
}

$legacyDirs = @(
    "osgearth-docs",
    "osgearth-master",
    "rocky-main",
    "rocky-upstream"
)
foreach ($legacy in $legacyDirs) {
    if (Test-Path (Join-Path $refsRoot $legacy)) {
        throw "Legacy directory still present: $legacy"
    }
}

if ($rows.Count -ne $manifest.repos.Count) {
    throw "Expected $($manifest.repos.Count) repos, verified $($rows.Count)"
}

Write-Host ""
Write-Host "Done. $($rows.Count) reference repos ready under $refsRoot"
