param(
    [string]$OutputDirectory = "build/android-test-apk-checker-test"
)

$ErrorActionPreference = "Stop"
$originalLastExitCode = $global:LASTEXITCODE

Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent $PSScriptRoot
$checker = Join-Path $PSScriptRoot "check-android-test-apk.ps1"
if (-not (Test-Path $checker)) {
    throw "Android test APK checker not found: $checker"
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

function New-TestZip {
    param(
        [string]$Path,
        [string[]]$Entries
    )

    $zip = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entryName in $Entries) {
            $entry = $zip.CreateEntry($entryName)
            $stream = $entry.Open()
            try {
                $bytes = [System.Text.Encoding]::UTF8.GetBytes("fixture")
                $stream.Write($bytes, 0, $bytes.Length)
            }
            finally {
                $stream.Dispose()
            }
        }
    }
    finally {
        $zip.Dispose()
    }
}

function Invoke-Checker {
    param(
        [string]$Artifact
    )

    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $checker -Artifact $Artifact 2>&1
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($output | Out-String).Trim()
    }
}

function Assert-Passed {
    param(
        [string]$Name,
        [string]$Artifact
    )

    $result = Invoke-Checker -Artifact $Artifact
    if ($result.ExitCode -ne 0) {
        throw "$Name should pass but exited $($result.ExitCode): $($result.Output)"
    }
    Write-Host "Expected pass for ${Name}: $($result.Output)"
}

function Assert-Failed {
    param(
        [string]$Name,
        [string]$Artifact,
        [string]$Pattern
    )

    $result = Invoke-Checker -Artifact $Artifact
    if ($result.ExitCode -eq 0) {
        throw "$Name should fail but passed: $($result.Output)"
    }
    if ($result.Output -notmatch $Pattern) {
        throw "$Name failed with unexpected output: $($result.Output)"
    }
    Write-Host "Expected failure for ${Name}: $($result.Output)"
}

$validRelative = "build/android-test-apk-checker-test/valid.apk"
$nativeRelative = "build/android-test-apk-checker-test/with-native.apk"
$emptyRelative = "build/android-test-apk-checker-test/empty.apk"
$missingRelative = "build/android-test-apk-checker-test/missing.apk"

try {
    New-TestZip -Path (Join-Path $repoRoot $validRelative) -Entries @("AndroidManifest.xml", "classes.dex")
    New-TestZip -Path (Join-Path $repoRoot $nativeRelative) -Entries @("AndroidManifest.xml", "lib/arm64-v8a/libvectorra_jni.so")
    New-Item -ItemType File -Force -Path (Join-Path $repoRoot $emptyRelative) | Out-Null

    Assert-Passed -Name "valid androidTest APK" -Artifact $validRelative
    Assert-Failed -Name "missing androidTest APK" -Artifact $missingRelative -Pattern "missing androidTest APK"
    Assert-Failed -Name "empty androidTest APK" -Artifact $emptyRelative -Pattern "androidTest APK is empty"
    Assert-Failed -Name "native library entry" -Artifact $nativeRelative -Pattern "unexpectedly contains native library"
}
finally {
    $global:LASTEXITCODE = $originalLastExitCode
}

Write-Host "Android instrumentation APK checker self-test passed."
