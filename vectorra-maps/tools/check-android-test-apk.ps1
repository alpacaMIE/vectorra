param(
    [string]$Artifact = "vectorra-maps/build/outputs/apk/androidTest/debug/vectorra-maps-debug-androidTest.apk"
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $repoRoot $Artifact

if (-not (Test-Path $apk)) {
    Write-Error "missing androidTest APK: $Artifact"
    exit 1
}

$apkInfo = Get-Item -LiteralPath $apk
if ($apkInfo.Length -le 0) {
    Write-Error "androidTest APK is empty: $Artifact"
    exit 1
}

$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $nativeEntries = $zip.Entries |
        Where-Object { $_.FullName -like "*.so" } |
        Select-Object -ExpandProperty FullName

    if ($nativeEntries.Count -gt 0) {
        $nativeEntries | ForEach-Object { Write-Error "$Artifact unexpectedly contains native library $_" }
        exit 1
    }
}
finally {
    $zip.Dispose()
}

Write-Host "Android instrumentation APK check passed."
