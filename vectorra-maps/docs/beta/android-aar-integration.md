# Android AAR Integration

Vectorra Maps Beta is currently published as Android AAR artifacts for local Maven or private Maven repositories.

## Requirements

- Android `minSdk 26` or newer.
- A Vulkan-capable Android device.
- No OpenGL fallback.
- Native ABI support for `arm64-v8a` and `x86_64`.

## Coordinates

```kotlin
dependencies {
    implementation("com.vectorra:vectorra-maps:0.5.0-beta.1")
}
```

Use the Turf utilities only when GeoJSON or geometry helpers are needed:

```kotlin
dependencies {
    implementation("com.vectorra:vectorra-maps-turf:0.5.0-beta.1")
}
```

## Local Publication

From `vectorra-maps/`:

```powershell
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
```

## Verify Sample From Published AAR

The sample app normally depends on `project(":vectorra-maps")`. To verify external-style AAR consumption:

```powershell
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

This mode reads `com.vectorra:vectorra-maps:0.5.0-beta.1` from `mavenLocal()`.

## Minimal Kotlin Usage

```kotlin
val mapView = VectorraMapView(context)
mapView.lifecycleCallback = object : VectorraMapLifecycleCallback {
    override fun onMapReady(view: VectorraMapView, map: VectorraMap) {
        map.setCamera(CameraOptions(longitude = 104.293174, latitude = 32.2857965, zoom = 4.2))
        map.addRasterLayer(
            id = "base",
            templateUrl = "https://example.com/tiles/{z}/{x}/{y}.png",
            minZoom = 0,
            maxZoom = 18
        )
    }
}
```

## Error Callback

Use the direct listener when the app only needs renderer/device/resource errors:

```kotlin
val subscription = mapView.addMapLoadErrorListener { _, error ->
    when (error) {
        is VectorraMapLoadError.UnsupportedDevice -> showUnsupportedDevice()
        is VectorraMapLoadError.NativeRenderer -> showRendererError(error.message)
        is VectorraMapLoadError.Resource -> showResourceError(error.message)
    }
}
```

Close the returned subscription when the surrounding component is destroyed if it outlives the `VectorraMapView`.

For layer/source status, redacted network logging, and offline prefetch diagnostics, see [Diagnostics and troubleshooting](diagnostics-troubleshooting.md).
