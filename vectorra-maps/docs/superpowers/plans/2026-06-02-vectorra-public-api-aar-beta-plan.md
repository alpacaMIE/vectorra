# Vectorra Public API And AAR Beta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a verified Android Beta integration loop for Vectorra Maps through public API boundaries, AAR publication, sample consumption, docs, error callbacks, and version policy.

**Architecture:** Keep runtime behavior stable and avoid broad API redesign. Centralize artifact identity in Gradle properties, expose only small API metadata/error listener additions, and verify external integration by consuming the Maven-local AAR from the sample app.

**Tech Stack:** Android Gradle Plugin 8.6.0, Kotlin 2.1.0, Android AAR, Maven Publish, Android NDK 28.2, Vulkan native renderer.

---

### Task 1: Centralize Beta Artifact Identity

**Files:**
- Modify: `vectorra-maps/gradle.properties`
- Modify: `vectorra-maps/build.gradle.kts`
- Modify: `vectorra-maps/settings.gradle.kts`

- [ ] **Step 1: Add version and group properties**

Add:

```properties
VECTORRA_GROUP=com.vectorra
VECTORRA_VERSION=0.1.0-beta.1
```

- [ ] **Step 2: Apply group and version to all modules**

Add root Gradle configuration:

```kotlin
val vectorraGroup = providers.gradleProperty("VECTORRA_GROUP").get()
val vectorraVersion = providers.gradleProperty("VECTORRA_VERSION").get()

allprojects {
    group = vectorraGroup
    version = vectorraVersion
}
```

- [ ] **Step 3: Enable Maven-local repository only for published-AAR sample verification**

In `settings.gradle.kts`, add `mavenLocal()` when `vectorra.sample.usePublishedAar=true`.

### Task 2: Update Maven Publications

**Files:**
- Modify: `vectorra-maps/vectorra-maps/build.gradle.kts`
- Modify: `vectorra-maps/vectorra-maps-turf/build.gradle.kts`
- Modify: `vectorra-maps/vectorra-maps/consumer-rules.pro`

- [ ] **Step 1: Use centralized group and version**

Set each publication to:

```kotlin
groupId = project.group.toString()
version = project.version.toString()
```

- [ ] **Step 2: Keep current artifact IDs**

Use:

```kotlin
artifactId = "vectorra-maps"
artifactId = "vectorra-maps-turf"
```

- [ ] **Step 3: Add POM metadata**

Add artifact names, descriptions, and project URL to both publications.

- [ ] **Step 4: Preserve native JNI names under consumer shrinking**

Add a keep rule for `com.vectorra.maps.internal.VectorraNative`.

### Task 3: Add Beta API Metadata And Error Listener

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraSdk.kt`
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMapLifecycle.kt`
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMapView.kt`
- Modify: `vectorra-maps/vectorra-sample/src/main/java/com/vectorra/sample/MainActivity.kt`

- [ ] **Step 1: Add SDK constants and annotations**

Create:

```kotlin
package com.vectorra.maps

object VectorraSdk {
    const val VERSION = "0.1.0-beta.1"
    const val API_STATUS = "beta"
    const val MIN_ANDROID_SDK = 26
    const val RENDERER = "vulkan"
}

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class VectorraBetaApi(val since: String = "0.1.0-beta.1")

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class VectorraExperimentalApi(val since: String = "0.1.0-beta.1")
```

- [ ] **Step 2: Add direct map error listener**

Add `VectorraMapErrorListener`, `errorListener`, and `addMapLoadErrorListener`.

- [ ] **Step 3: Dispatch load errors to all error callbacks**

Call the direct listener and registered listener list from `dispatchMapLoadError`.

- [ ] **Step 4: Demonstrate the callback in the sample**

Use `addMapLoadErrorListener` in `MainActivity`.

### Task 4: Let Sample Consume Published AAR

**Files:**
- Modify: `vectorra-maps/vectorra-sample/build.gradle.kts`

- [ ] **Step 1: Add a dependency switch**

When `vectorra.sample.usePublishedAar=true`, depend on:

```kotlin
implementation("${project.group}:vectorra-maps:${project.version}")
```

Otherwise keep:

```kotlin
implementation(project(":vectorra-maps"))
```

### Task 5: Add Beta Documentation

**Files:**
- Create: `vectorra-maps/README.md`
- Create: `vectorra-maps/docs/beta/android-aar-integration.md`
- Create: `vectorra-maps/docs/beta/api-stability.md`
- Create: `vectorra-maps/docs/beta/release-versioning.md`

- [ ] **Step 1: Document the SDK scope**

State Android-first, Vulkan-only, `minSdk 26`, and Beta feature boundaries.

- [ ] **Step 2: Document Gradle coordinates and sample commands**

Show Maven-local publication and sample AAR consumption commands.

- [ ] **Step 3: Document error callbacks**

Show both lifecycle and direct error listener usage.

- [ ] **Step 4: Document version policy**

Define Beta version behavior and API stability boundaries.

### Task 6: Verify And Commit

**Files:**
- Verify all modified files.

- [ ] **Step 1: Run unit tests**

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
```

- [ ] **Step 2: Publish AARs locally**

```powershell
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
```

- [ ] **Step 3: Build sample from published AAR**

```powershell
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

- [ ] **Step 4: Commit**

```powershell
git add vectorra-maps
git commit -m "chore: add Vectorra beta AAR integration loop"
```
