# Vectorra Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename SDK-owned `Rocky*` Kotlin, JNI, CMake, sample, and test symbols to `Vectorra*` without modifying vendored rocky code or rocky shader assets.

**Architecture:** This is a mechanical naming migration. Public and internal SDK-owned symbols become `Vectorra*`; vendored dependency identifiers such as `third_party/rocky`, `rocky::`, `ROCKY_SUPPORTS_*`, `ROCKY_FILE_PATH`, and assets under `src/main/assets/rocky` remain as rocky integration details.

**Tech Stack:** Kotlin 2.1.0, Android Gradle Plugin 8.6.0, CMake 3.22.1, JNI, PowerShell, JUnit.

---

## Workspace Constraint

This checkout has no git metadata, so the commit steps required by the standard plan workflow cannot run. If git metadata is restored before execution, commit after verification with:

```powershell
git add docs vectorra-maps vectorra-maps-turf vectorra-sample
git commit -m "refactor: rename sdk api to vectorra"
```

## Files

**Create:**

- None.

**Rename:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp\rocky_jni.cpp` -> `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp\vectorra_jni.cpp`
- Every source or test file under these roots whose filename contains `Rocky` should be renamed by replacing `Rocky` with `Vectorra`:
  - `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java`
  - `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\test\java`
  - `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps-turf\src\main\java`
  - `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps-turf\src\test\java`

**Modify:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\**\*.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\test\java\com\vectorra\maps\**\*.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps-turf\src\main\java\com\vectorra\maps\turf\**\*.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps-turf\src\test\java\com\vectorra\maps\turf\**\*.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\src\main\java\com\vectorra\sample\MainActivity.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp\CMakeLists.txt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp\vectorra_jni.cpp`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\consumer-rules.pro`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps-turf\consumer-rules.pro`

**Do not modify:**

- `D:\workspace\code\vectorra\vectorra-references`
- `D:\workspace\code\vectorra\vectorra-maps\third_party\rocky`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\assets\rocky`

## Task 1: Baseline Test State

- [ ] **Step 1: Run Vectorra Maps unit tests before the rename**

Run:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
```

Expected: PASS. If it fails, record the failure as baseline before editing.

- [ ] **Step 2: Run Turf unit tests before the rename**

Run:

```powershell
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
```

Expected: PASS. If it fails, record the failure as baseline before editing.

## Task 2: Rename Files

- [ ] **Step 1: Rename Kotlin files containing `Rocky`**

Run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$renameRoots = @(
    "vectorra-maps\src\main\java",
    "vectorra-maps\src\test\java",
    "vectorra-maps-turf\src\main\java",
    "vectorra-maps-turf\src\test\java"
)

Get-ChildItem -Path $renameRoots -Recurse -File |
    Where-Object { $_.Name -like "*Rocky*" } |
    Sort-Object FullName -Descending |
    ForEach-Object {
        Rename-Item -LiteralPath $_.FullName -NewName ($_.Name -replace "Rocky", "Vectorra")
    }
```

Expected: files such as `RockyMapView.kt`, `RockyMap.kt`, `RockyNative.kt`, and `RockyRasterTileSourceTest.kt` are renamed to their `Vectorra*` equivalents.

- [ ] **Step 2: Rename JNI source file**

Run:

```powershell
Rename-Item -LiteralPath "vectorra-maps\src\main\cpp\rocky_jni.cpp" -NewName "vectorra_jni.cpp"
```

Expected: `vectorra-maps\src\main\cpp\vectorra_jni.cpp` exists.

## Task 3: Rename SDK-Owned Symbols

- [ ] **Step 1: Replace `Rocky` with `Vectorra` in SDK-owned source and test text**

Run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$textRoots = @(
    "vectorra-maps\src\main\java",
    "vectorra-maps\src\test\java",
    "vectorra-maps-turf\src\main\java",
    "vectorra-maps-turf\src\test\java",
    "vectorra-sample\src\main\java",
    "vectorra-maps\src\main\cpp",
    "vectorra-maps\consumer-rules.pro",
    "vectorra-maps-turf\consumer-rules.pro"
)

Get-ChildItem -Path $textRoots -Recurse -File |
    Where-Object {
        $_.Extension -in @(".kt", ".cpp", ".txt", ".pro") -or $_.Name -eq "CMakeLists.txt"
    } |
    ForEach-Object {
        $path = $_.FullName
        $content = Get-Content -LiteralPath $path -Raw
        $updated = $content.Replace("Rocky", "Vectorra")
        if ($updated -ne $content) {
            Set-Content -LiteralPath $path -Value $updated -NoNewline
        }
    }
```

Expected: Kotlin class names, imports, test names, sample usage, comments, C++ local class names, and JNI function owner names use `Vectorra`.

- [ ] **Step 2: Rename JNI library target and source references**

Run:

```powershell
$path = "vectorra-maps\src\main\cpp\CMakeLists.txt"
$content = Get-Content -LiteralPath $path -Raw
$content = $content.Replace("project(rocky_android_bridge LANGUAGES C CXX)", "project(vectorra_android_bridge LANGUAGES C CXX)")
$content = $content.Replace("add_library(rocky_jni SHARED rocky_jni.cpp)", "add_library(vectorra_jni SHARED vectorra_jni.cpp)")
$content = $content.Replace("target_link_options(rocky_jni", "target_link_options(vectorra_jni")
$content = $content.Replace("target_include_directories(rocky_jni", "target_include_directories(vectorra_jni")
$content = $content.Replace("target_link_libraries(rocky_jni", "target_link_libraries(vectorra_jni")
$content = $content.Replace("ROCKY_ANDROID_PAGE_SIZE_LINK_OPTIONS", "VECTORRA_ANDROID_PAGE_SIZE_LINK_OPTIONS")
Set-Content -LiteralPath $path -Value $content -NoNewline
```

Expected: CMake creates `vectorra_jni` from `vectorra_jni.cpp` while preserving upstream `rocky` target names and `ROCKY_SUPPORTS_*` options.

- [ ] **Step 3: Rename JNI library load and internal constants**

Run:

```powershell
$kotlinPath = "vectorra-maps\src\main\java\com\vectorra\maps\internal\VectorraNative.kt"
$content = Get-Content -LiteralPath $kotlinPath -Raw
$content = $content.Replace('System.loadLibrary("rocky_jni")', 'System.loadLibrary("vectorra_jni")')
Set-Content -LiteralPath $kotlinPath -Value $content -NoNewline

$viewPath = "vectorra-maps\src\main\java\com\vectorra\maps\VectorraMapView.kt"
$content = Get-Content -LiteralPath $viewPath -Raw
$content = $content.Replace("ROCKY_ASSET_ROOT", "VECTORRA_ASSET_ROOT")
$content = $content.Replace("ROCKY_ASSET_MARKER", "VECTORRA_ASSET_MARKER")
$content = $content.Replace("ROCKY_CJK_FONT", "VECTORRA_CJK_FONT")
$content = $content.Replace(".rocky_assets_ready_v2", ".vectorra_assets_ready_v1")
Set-Content -LiteralPath $viewPath -Value $content -NoNewline

$cppPath = "vectorra-maps\src\main\cpp\vectorra_jni.cpp"
$content = Get-Content -LiteralPath $cppPath -Raw
$content = $content.Replace('constexpr const char* TAG = "rocky_jni";', 'constexpr const char* TAG = "vectorra_jni";')
$content = $content.Replace("ROCKY_CAMERA_FOVY_DEGREES", "VECTORRA_CAMERA_FOVY_DEGREES")
Set-Content -LiteralPath $cppPath -Value $content -NoNewline
```

Expected: app loads `vectorra_jni`; local SDK constants use Vectorra naming; the asset root value remains `"rocky"` because the assets are vendored rocky resources.

## Task 4: Verify Rename Completeness

- [ ] **Step 1: Scan Kotlin and JNI source for unexpected `Rocky`**

Run:

```powershell
rg -n "Rocky" `
    vectorra-maps\src\main\java `
    vectorra-maps\src\test\java `
    vectorra-maps-turf\src\main\java `
    vectorra-maps-turf\src\test\java `
    vectorra-sample\src\main\java `
    vectorra-maps\consumer-rules.pro `
    vectorra-maps-turf\consumer-rules.pro `
    vectorra-maps\src\main\cpp\vectorra_jni.cpp
```

Expected: no matches. If matches remain in SDK-owned symbols, replace them with `Vectorra`.

- [ ] **Step 2: Scan for stale JNI library target references**

Run:

```powershell
rg -n "rocky_jni|RockyNative|Java_com_vectorra_maps_internal_RockyNative" `
    vectorra-maps\src\main\java `
    vectorra-maps\src\main\cpp
```

Expected: no matches.

- [ ] **Step 3: Confirm intentionally retained rocky integration identifiers**

Run:

```powershell
rg -n "third_party/rocky|rocky::|ROCKY_SUPPORTS_|ROCKY_FILE_PATH|src/main/assets/rocky|target_link_libraries\\(rocky|target_link_options\\(rocky|target_compile_definitions\\(rocky" `
    vectorra-maps\src\main\cpp `
    vectorra-maps\build.gradle.kts
```

Expected: matches may remain because they refer to the vendored rocky dependency and its build options.

## Task 5: Run Verification

- [ ] **Step 1: Run Vectorra Maps unit tests after rename**

Run:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Run Turf unit tests after rename**

Run:

```powershell
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Run sample build if native dependencies are available**

Run:

```powershell
.\gradlew.bat :vectorra-sample:assembleDebug
```

Expected: PASS when `build/vcpkg/scripts/buildsystems/vcpkg.cmake` and Android native dependencies are available. If it fails because vcpkg has not been bootstrapped, record the failure and run `.\tools\bootstrap-vcpkg-android.ps1` before retrying native-dependent builds.

## Task 6: Commit When Git Is Available

- [ ] **Step 1: Commit the rename**

Run only if this checkout has git metadata:

```powershell
git status --short
git add docs vectorra-maps vectorra-maps-turf vectorra-sample
git commit -m "refactor: rename sdk api to vectorra"
```

Expected: one commit containing the plan docs and rename migration. In the current checkout, this step is expected to be skipped because `git rev-parse --show-toplevel` fails.
