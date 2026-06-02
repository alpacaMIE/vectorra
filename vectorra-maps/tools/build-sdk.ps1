param(
    [string]$AndroidHome = "C:\Users\myg\AppData\Local\Android\Sdk",
    [string]$GradleUserHome = "..\build\gradle-home"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome

.\gradlew.bat -g $GradleUserHome `
    :vectorra-maps:assembleDebug `
    :vectorra-maps:testDebugUnitTest `
    :vectorra-maps-turf:testDebugUnitTest `
    :vectorra-sample:assembleDebug `
    "-Pkotlin.incremental=false" `
    --no-daemon
