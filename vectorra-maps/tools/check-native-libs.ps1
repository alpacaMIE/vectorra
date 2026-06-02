param(
    [string[]]$Artifacts = @(
        "vectorra-maps/build/outputs/aar/vectorra-maps-debug.aar",
        "vectorra-sample/build/outputs/apk/debug/vectorra-sample-debug.apk"
    )
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent $PSScriptRoot
$abis = @("arm64-v8a", "x86_64")
$libs = @("librocky.so", "librocky_jni.so")
$missing = @()

foreach ($relativeArtifact in $Artifacts) {
    $artifact = Join-Path $repoRoot $relativeArtifact
    if (-not (Test-Path $artifact)) {
        $missing += "missing artifact: $relativeArtifact"
        continue
    }

    $zip = [System.IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        $entries = $zip.Entries | ForEach-Object { $_.FullName }
        foreach ($abi in $abis) {
            foreach ($lib in $libs) {
                $patterns = @("jni/$abi/$lib", "lib/$abi/$lib")
                $found = $false
                foreach ($pattern in $patterns) {
                    if ($entries -contains $pattern) {
                        $found = $true
                        break
                    }
                }
                if (-not $found) {
                    $missing += "$relativeArtifact missing $($patterns -join ' or ')"
                }
            }
        }
    }
    finally {
        $zip.Dispose()
    }
}

if ($missing.Count -gt 0) {
    $missing | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Native library check passed."
