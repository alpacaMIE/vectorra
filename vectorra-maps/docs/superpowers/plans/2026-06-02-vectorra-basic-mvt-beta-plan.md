# Vectorra Basic MVT Beta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a basic MVT decoder and GeoJSON conversion path for Vectorra Maps Beta.

**Architecture:** Implement dependency-free protobuf wire parsing in Kotlin, decode MVT layers/features/geometry into focused models, and convert tile-local MVT coordinates into existing Vectorra GeoJSON/query models. Keep renderer and network loading out of this first MVT step.

**Tech Stack:** Kotlin, JUnit, existing Vectorra query models, Android Gradle Plugin 8.6.0.

---

### Task 1: MVT Models

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/mvt/VectorraMvtModels.kt`

- [ ] Add tile, layer, feature, value, geometry, tile id, and decoded feature models.

### Task 2: Protobuf Reader And Decoder

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/mvt/VectorraMvtDecoder.kt`
- Test: `vectorra-maps/vectorra-maps/src/test/java/com/vectorra/maps/mvt/VectorraMvtDecoderTest.kt`

- [ ] Implement varint, fixed32, fixed64, bytes, string, and zig-zag decoding.
- [ ] Decode MVT layer, feature, value, tags, and geometry fields.
- [ ] Decode MoveTo, LineTo, and ClosePath geometry commands.

### Task 3: GeoJSON Conversion

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/mvt/VectorraMvtGeoJson.kt`
- Test: `vectorra-maps/vectorra-maps/src/test/java/com/vectorra/maps/mvt/VectorraMvtGeoJsonTest.kt`

- [ ] Convert points, lines, and polygons to `VectorraGeoJsonFeature`.
- [ ] Convert tile-local coordinates to Web Mercator longitude/latitude.
- [ ] Preserve MVT layer name, feature id, and properties.

### Task 4: Docs And Version

**Files:**
- Modify: `vectorra-maps/gradle.properties`
- Modify: `vectorra-maps/README.md`
- Modify: `vectorra-maps/docs/beta/android-aar-integration.md`
- Modify: `vectorra-maps/docs/beta/api-stability.md`
- Modify: `vectorra-maps/docs/beta/release-versioning.md`
- Create: `vectorra-maps/docs/beta/basic-mvt.md`

- [ ] Bump version to `0.3.0-beta.1`.
- [ ] Document MVT scope and non-goals.

### Task 5: Verify And Commit

**Files:**
- Verify all modified files.

- [ ] Run:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

- [ ] Commit:

```powershell
git add vectorra-maps
git commit -m "feat: add basic MVT decoder"
```
