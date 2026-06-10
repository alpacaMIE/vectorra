param(
    [string]$AndroidHome = "C:\Users\myg\AppData\Local\Android\Sdk",
    [string]$AndroidNdkHome = (Join-Path $AndroidHome "ndk\28.2.13676358"),
    [string]$VcpkgGitUrl = "https://github.com/microsoft/vcpkg.git"
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

$repoRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $repoRoot "build"
$vcpkgRoot = Join-Path $buildRoot "vcpkg"
$installedRoot = Join-Path $buildRoot "vcpkg_installed_ndk28"
$triplets = @("arm64-android", "x64-android")
$packages = @(
    "proj",
    "glm",
    "spdlog",
    "nlohmann-json",
    "entt",
    "curl",
    "libwebp",
    "vsg[windowing]",
    "vsgxchange[assimp,freetype,draco]",
    "imgui[vulkan-binding]"
)

$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
$env:ANDROID_NDK_HOME = $AndroidNdkHome

New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null

if (-not (Test-Path $vcpkgRoot)) {
    Invoke-Checked "git" @("clone", $VcpkgGitUrl, $vcpkgRoot)
}

Push-Location $vcpkgRoot
try {
    Invoke-Checked ".\bootstrap-vcpkg.bat" @("-disableMetrics")
    foreach ($triplet in $triplets) {
        foreach ($package in $packages) {
            Invoke-Checked ".\vcpkg.exe" @(
                "install",
                "$package`:$triplet",
                "--overlay-ports=$repoRoot\third_party\rocky\vcpkg-ports",
                "--x-install-root=$installedRoot",
                "--clean-after-build"
            )
        }
    }
}
finally {
    Pop-Location
}
