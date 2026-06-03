param(
    [string]$AndroidHome = "C:\Users\myg\AppData\Local\Android\Sdk",
    [string]$GradleUserHome = ".\.gradle-agent-home"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_SDK_ROOT = $AndroidHome

    .\gradlew.bat -g $GradleUserHome `
        :vectorra-maps:testDebugUnitTest `
        :vectorra-maps-turf:testDebugUnitTest `
        :vectorra-sample:assembleDebug `
        assembleDebug `
        :vectorra-maps:publishReleasePublicationToMavenLocal `
        :vectorra-maps-turf:publishReleasePublicationToMavenLocal `
        "-Pvectorra.sample.usePublishedAar=true" `
        :vectorra-sample:assembleDebug

    & (Join-Path $PSScriptRoot "check-native-libs.ps1")
    & (Join-Path $PSScriptRoot "test-device-smoke-result-checker.ps1")

    Write-Host "Android local acceptance gate passed."
}
finally {
    Pop-Location
}
