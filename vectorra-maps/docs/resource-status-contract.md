# Vectorra Resource Status Contract

This document records the Phase 0 resource status contract used by raster, DEM, model, and MBTiles paths.

## Payload

`VectorraResourceStatus` is the public payload for layer/source load state:

- `kind`: resource family, currently `RASTER`, `DEM`, `MODEL`, or `MBTILES`.
- `sourceId`: stable source identity for the requested resource.
- `layerId`: stable render layer identity for the requested resource.
- `generation`: monotonically increasing value per `kind/sourceId/layerId`.
- `state`: one of `LOADING`, `LOADED`, `FAILED`, or `REMOVED`.
- `eventSource`: component that produced the event, currently `ENGINE`, `NATIVE`, `TILE_PROXY`, or `LOCAL_PROVIDER`.
- `error`: required only when `state == FAILED`.

## State Semantics

- `LOADING`: the engine accepted a resource request and submitted it to the next owner. For P0.T1 this is emitted from Kotlin before calling native renderer entry points. P0.T2 will reuse the same event path for native progress.
- `LOADED`: the owning renderer or provider has confirmed the resource is available. P0.T2 should emit this from native/provider callbacks, not from sample UI.
- `FAILED`: the owning engine/native/proxy/provider path rejected or failed the request. The payload must include a `VectorraResourceLoadError`.
- `REMOVED`: the layer/source has been removed from its owner and should no longer be treated as renderable or queryable.

## Threading

Current status queries are synchronous. Listener callbacks are delivered on the Android main thread. Status storage is protected so future native/render-thread callbacks can use the same engine entry point without creating another state owner.

## Ownership

The engine is the single public owner of current resource status. Sample code must read statuses with `addResourceStatusListener`, `getLayerResourceStatus`, or `getSourceResourceStatus` instead of maintaining a separate product status model.
