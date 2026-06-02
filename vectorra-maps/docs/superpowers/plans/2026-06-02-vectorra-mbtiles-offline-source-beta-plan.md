# Vectorra MBTiles Offline Source Beta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a raster MBTiles offline source API and render path that works through the existing Vectorra raster layer pipeline.

**Architecture:** Keep MBTiles reading in Android/Kotlin and leave native rocky unchanged. Extend `TileProxyServer` with an internal local tile provider registration, then expose `VectorraMbTilesRasterSource` and `VectorraMap.addMbTilesRasterLayer`.

**Tech Stack:** Kotlin, Android `SQLiteDatabase`, Android AAR, existing Vectorra tile proxy, JUnit unit tests.

---

### Task 1: Local Tile Provider Proxy

**Files:**
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/network/TileProxyServer.kt`
- Test: `vectorra-maps/vectorra-maps/src/test/java/com/vectorra/maps/network/TileProxyServerTest.kt`

- [ ] Add an internal `LocalTileProvider` fun interface returning `TileResponse`.
- [ ] Add `proxyTemplateForLocalProvider(sourceId, layerId, resourceType, provider)`.
- [ ] Route local provider registrations through the same `/tile/{id}?z={z}&x={x}&y={y}` endpoint.
- [ ] Add tests for local provider success and local provider 404 behavior.

### Task 2: MBTiles Models And Metadata Parser

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/offline/VectorraMbTilesRasterSource.kt`
- Test: `vectorra-maps/vectorra-maps/src/test/java/com/vectorra/maps/offline/VectorraMbTilesRasterSourceTest.kt`

- [ ] Add `VectorraMbTilesMetadata`.
- [ ] Add `VectorraMbTilesBounds`.
- [ ] Add `VectorraMbTilesCenter`.
- [ ] Add `VectorraMbTilesTileRowScheme`.
- [ ] Add pure metadata parser and tile-row conversion helpers.
- [ ] Test metadata defaults, supported formats, unsupported formats, and TMS row conversion.

### Task 3: Android SQLite Reader

**Files:**
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/offline/VectorraMbTilesRasterSource.kt`

- [ ] Add `VectorraMbTilesRasterSource.open(file, id)`.
- [ ] Use Android `SQLiteDatabase.openDatabase(..., OPEN_READONLY)`.
- [ ] Query `metadata(name, value)` and parse it.
- [ ] Query tile bytes from `tiles(tile_column, tile_row, zoom_level)`.
- [ ] Return 404 tile responses for missing tiles.

### Task 4: Map API Integration

**Files:**
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMap.kt`
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMapEngine.kt`

- [ ] Add `VectorraMap.addMbTilesRasterLayer(source, layerId = source.id)`.
- [ ] Register the MBTiles source as a local provider in `TileProxyServer`.
- [ ] Add a raster layer with the generated localhost template and metadata zoom range.

### Task 5: Sample And Docs

**Files:**
- Modify: `vectorra-maps/vectorra-sample/src/main/java/com/vectorra/sample/MainActivity.kt`
- Modify: `vectorra-maps/README.md`
- Create: `vectorra-maps/docs/beta/mbtiles-offline-source.md`

- [ ] Add a sample button that attempts to open `filesDir/sample.mbtiles`.
- [ ] Document app-side placement of MBTiles files.
- [ ] Document format limitations and tile-row scheme behavior.

### Task 6: Verify And Commit

**Files:**
- Verify all modified files.

- [ ] Run:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
```

- [ ] Commit:

```powershell
git add vectorra-maps
git commit -m "feat: add raster MBTiles offline source"
```
