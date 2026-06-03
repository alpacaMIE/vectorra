param(
    [string[]]$Artifacts = @()
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent $PSScriptRoot
$defaultExpectations = [ordered]@{
    "vectorra-maps/build/outputs/aar/vectorra-maps-debug.aar" = @(
        "jni/arm64-v8a/librocky.so",
        "jni/arm64-v8a/libvectorra_jni.so",
        "jni/x86_64/librocky.so",
        "jni/x86_64/libvectorra_jni.so"
    )
    "vectorra-maps/build/outputs/aar/vectorra-maps-release.aar" = @(
        "jni/arm64-v8a/librocky.so",
        "jni/arm64-v8a/libvectorra_jni.so",
        "jni/x86_64/librocky.so",
        "jni/x86_64/libvectorra_jni.so"
    )
    "vectorra-sample/build/outputs/apk/debug/vectorra-sample-arm64-v8a-debug.apk" = @(
        "lib/arm64-v8a/librocky.so",
        "lib/arm64-v8a/libvectorra_jni.so"
    )
    "vectorra-sample/build/outputs/apk/debug/vectorra-sample-x86_64-debug.apk" = @(
        "lib/x86_64/librocky.so",
        "lib/x86_64/libvectorra_jni.so"
    )
    "vectorra-sample/build/outputs/apk/debug/vectorra-sample-universal-debug.apk" = @(
        "lib/arm64-v8a/librocky.so",
        "lib/arm64-v8a/libvectorra_jni.so",
        "lib/x86_64/librocky.so",
        "lib/x86_64/libvectorra_jni.so"
    )
}

if ($Artifacts.Count -eq 0) {
    $Artifacts = @($defaultExpectations.Keys)
}

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
        $requiredEntries = $defaultExpectations[$relativeArtifact]
        if ($null -eq $requiredEntries) {
            $requiredEntries = @(
                "jni/arm64-v8a/librocky.so",
                "jni/arm64-v8a/libvectorra_jni.so",
                "jni/x86_64/librocky.so",
                "jni/x86_64/libvectorra_jni.so"
            )
        }
        foreach ($entry in $requiredEntries) {
            if ($entries -notcontains $entry) {
                $missing += "$relativeArtifact missing $entry"
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
