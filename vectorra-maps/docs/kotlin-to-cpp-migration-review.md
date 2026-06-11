# Kotlin 侧实现下沉 C++ 审查

本文审查 `:vectorra-maps` 当前的 Kotlin/C++ 职责划分，识别哪些 Kotlin 实现应该迁移到 native 层（`vectorra_jni.cpp` / rocky）。审查范围：`VectorraMapEngine.kt`、`VectorraMapView.kt`、`mvt/`、`query/`、`network/`、`offline/` 全部源码，以及 `vectorra_jni.cpp`（4374 行）与 `CMakeLists.txt` 的对照。

## 总体判断

当前架构是"Kotlin 重、C++ 薄"：C++ 侧只是 rocky 的配置/转发桥（单个 `VectorraNativeEngine` 类），而渲染器核心的三类职责落在了 Kotlin：

1. **坐标投影与命中测试**——Kotlin 用平面 Web Mercator 近似一个 native 端实际是 3D 球体透视相机的渲染器，且 SDK 内部存在三套互不一致的投影实现；
2. **相机控制、手势换算与动画**——native 已有 `rocky::MapManipulator`（自带 viewpoint 动画），Kotlin 却在外面重新实现了一整套，每帧经 JNI 推送绝对相机；
3. **MVT 矢量瓦片管线**——手写 protobuf 解码、瓦片覆盖计算、金字塔回退、调度全部在 JVM，解码结果以 7 个数组的巨型 JNI 调用逐瓦片送入 native。

第 1 类是**正确性问题**（pitch、高纬度、globe 视角下查询/点击结果就是错的），第 2、3 类是性能与双份维护问题。网络栈（回环 HTTP 代理）有其存在理由（TLS、拦截器），短期不必整体迁移，但有两个具体缺陷需要修。

下沉优先级排序：**投影 > 相机/手势 > MVT 解码 > MVT 调度 > 网络栈**。

## P0：屏幕投影与 queryRenderedFeatures（正确性）

### 现状

- `VectorraAnnotationHitTester.pixelForCoordinate` / `coordinateForPixel`（`VectorraAnnotationHitTester.kt:92-120`）用平面 Web Mercator + bearing 旋转计算屏幕坐标，**完全不考虑 pitch**，也不考虑 native 球体投影，`TILE_SIZE = 512`（`:248`）。
- `VectorraMapEngine` 的手势换算用的是另一套常量 `WEB_MERCATOR_TILE_SIZE = 256`（`VectorraMapEngine.kt:1697`）。
- native 真实相机是 `rocky::MapManipulator` 的球体透视模型：FOV 30°、`viewpoint.range` 由 zoom 换算、pitch 限制在 -90°~-5°（`vectorra_jni.cpp:3373-3386`）。

即同一个 SDK 内有**三套互不一致的投影模型**（Kotlin 256 平面 / Kotlin 512 平面 / native 球体透视）。

### 影响面

所有建立在 `pixelForCoordinate` 之上的功能在 pitch ≠ 0、高纬度或低 zoom（球面弯曲可见）时给出错误结果：

- 公共 API `pixelForCoordinate` / `coordinateForPixel`（`VectorraMapEngine.kt:497-503`）；
- `queryRenderedFeatures` 的全部三路查询：标注命中、GeoJSON 索引、MVT 要素命中（`VectorraMapEngine.kt:473-495`）；
- 地图点击事件 `dispatchMapClick`（`:1336`）；
- GeoJSON 聚类（`VectorraGeoJsonIndex` 的 `project` 回调注入的就是 hitTester 的投影，`VectorraMapEngine.kt:139-142`）；
- 双指缩放焦点保持 `zoomByScaleAt`（`:314`）。

### 建议

新增两个 JNI 查询接口，由 native 用真实的 view/projection 矩阵回答：

- `worldToScreen(lon, lat, height) -> (x, y, visible)`：vsg 相机矩阵正向投影；
- `screenToWorld(x, y) -> (lon, lat)`：对地球椭球/地形做 intersector 拾取（rocky/vsg 已有 `LineSegmentIntersector` 基础设施）。

Kotlin 端 `VectorraAnnotationHitTester` 与 `VectorraGeoJsonIndex` 保留过滤、排序、距离判定逻辑，但把"经纬度 → 屏幕像素"这一步替换为 native 调用（批量接口，一次投影一组坐标，避免逐点 JNI）。这是后续所有迁移的地基：投影只剩一个 owner。

注意：查询发生在点击瞬间，频率低，批量 JNI 的开销可忽略；不需要把命中测试整体下沉，先下沉投影即可消除正确性问题。

## P0/P1：相机状态、手势换算与动画

### 现状

- 手势识别在 `VectorraMapView.onTouchEvent`（`VectorraMapView.kt:158-331`）：拖拽/双指捏合/旋转/俯仰/双击/双指点击/惯性 fling（`OverScroller`，`:81-96`）全部 Kotlin 自研。
- 手势 → 相机换算在 `VectorraMapEngine`：`panByPixels` / `zoomByScaleAt` / `rotateBy` / `pitchBy`（`:276-387`），基于平面 Mercator 数学（`:1700-1766`）。
- 相机动画 `easeTo`/`flyTo` 用 Android `ValueAnimator` + `DecelerateInterpolator` 逐帧插值（`:397-436`）。
- 每次相机变化经 `Choreographer.postFrameCallback` 调 JNI `setCamera`，native 端 `manipulator->setViewpoint(viewpoint, 0s)` 瞬时硬切（`vectorra_jni.cpp:3479-3483`）。
- native 端 `onTouch` JNI 通道已存在但实现是**空壳**——只存了最后触点坐标，没有接 manipulator（`vectorra_jni.cpp:1608-1618`）。
- zoom→range 换算公式**两侧各维护一份**：Kotlin `VectorraCameraRange.kt:7-28` 与 C++ `vectorra_jni.cpp:3344-3358`（常量 FOV 30°、地球周长、clamp 100~30M 米逐项相同）。Kotlin 版现在只剩单元测试在用（`VectorraMapEngineCameraRangeTest.kt`），生产路径未引用——这正是双份公式开始漂移的信号。

### 问题

1. **模型错配**：平面 Mercator 手势换算驱动球体渲染器。低 zoom / 高纬度 / pitch 下，拖拽距离与地图实际移动不匹配，焦点缩放无法精确锚定手指位置。
2. **双份状态**：Kotlin `cameraState` 与 native manipulator viewpoint 各存一份，依赖每帧单向推送保持同步；native 端任何相机变化（未来的惯性、碰撞、地形跟随）都无法反映回 Kotlin。
3. **动画质量**：`ValueAnimator` 在 UI 线程插值再经 JNI → 渲染线程 queue，相机动画与渲染帧不同步；rocky `setViewpoint(viewpoint, duration)` 本身支持平滑过渡却传了 `0s`。
4. **双份公式**：上述 zoom→range、pitch clamp（Kotlin 0~80°，native -90~-5° 即 0~85°）、纬度 clamp 等参数两边重复维护。

### 建议

把"手势 → 相机"的换算与动画下沉到 native，方向是让 `rocky::MapManipulator` 成为相机唯一 owner：

- Kotlin `VectorraMapView` 保留手势**识别**（Android 触摸事件、`GestureSettings` 开关、touch slop 等平台惯例），把识别结果以语义化命令传给 native：`panBy(dx, dy)`、`zoomBy(scale, fx, fy)`、`rotateBy(deg)`、`pitchBy(deg)`、`flingBy(vx, vy)`——native 用真实相机模型执行（屏幕向量 → 球面位移）。现有空壳 `onTouch` 通道可废弃或改造。
- `easeTo`/`flyTo` 改为传目标 viewpoint + duration，由 native `setViewpoint(viewpoint, duration)` 执行插值（渲染线程内，与帧同步）。
- 相机状态回传：native 在相机变化帧通过回调（复用 `ResourceStatusCallback` 的注册模式，`vectorra_jni.cpp:3750`）推送 `CameraState` 给 Kotlin，Kotlin `cameraState` 变为只读镜像，`MIN/MAX_ZOOM`、pitch clamp 等约束全部收敛到 native 一处。
- 删除 Kotlin 侧 Mercator 相机数学与 `VectorraCameraRange.kt`（公式测试改为 instrumented 测试或 native 单测）。

注意事项：`zoomByScaleAt` 的焦点锚定语义、fling 减速曲线要在迁移后与现状做手感对比；rocky MapManipulator 桌面端已有完整的 drag/zoom/rotate 实现可参考复用。

## P1：MVT 解码与几何编组

### 现状

- protobuf 解码是 Kotlin 手写的 `PbfReader`（`VectorraMvtDecoder.kt:180-259`）：每个嵌套 message `copyOfRange` 复制一次字节数组，每个顶点装箱成 `VectorraMvtPoint` 对象，packed varint 先读成 `List<Long>` 再 map 成 `List<Int>`。
- 解码后在 Kotlin 把 tile 局部坐标换算成 WGS84 经纬度 `List<Double>`（装箱），见 `VectorraMvtGeoJson.kt` / `VectorraMvtRenderContract.kt:43-121`。
- 每个瓦片一次 JNI 调用 `renderMvtTile`，编组 2 个字符串数组 + 4 个 int 数组 + 1 个 double 数组（`VectorraNative.kt:113-134`）；`nativeCoordinates()` 还要先 `flatMap` 出装箱 `List<Double>` 再转 `DoubleArray`（`VectorraMvtRenderContract.kt:103-105`）。
- native 拿到经纬度后用 `rocky::SRS::WGS84` 建几何（`vectorra_jni.cpp:1299` 起），即真正的投影/三角化本来就在 C++。
- 6 个 worker 线程做解码（`VECTOR_TILE_LOAD_WORKER_COUNT = 6`，`VectorraMapEngine.kt:1998`）。

### 问题

解码热路径在 JVM 上产生海量小对象（每瓦片数千~数万顶点，每顶点 1 个对象 + 多次装箱），滑动/缩放时 6 线程并发解码触发 GC 压力；JNI 编组再整体复制一次。把字节直接交给 C++ 解码可以把这条链路压缩成"字节 → C++ 结构 → 几何"，零 JVM 分配。

### 建议

- 新 JNI 接口改为传**原始瓦片字节**：`renderMvtTileBytes(layerId, z, x, y, style, byte[])`（或 `ByteBuffer.allocateDirect` 零拷贝）。C++ 端用 protozero 风格解码器（参考 `vectorra-references/maplibre-native/` 的 vector tile 解析）+ tile 局部坐标→经纬度换算，对接现有 `renderMvtTile` 的几何构建代码。
- Kotlin 保留：样式定义（`VectorraVectorTileLayer`）、source 配置、瓦片获取（暂时）。
- 查询要素的支撑：`VectorraMvtRuntimeTileStore.queryFeatures()` 现在依赖 Kotlin 解码产物做命中测试（`VectorraMvtRuntimeTileStore.kt:32-53`）。过渡方案是 native 解码时同时返回精简的查询几何（要素 id/属性/简化外形），或者把 MVT 要素命中也下沉为 native 查询接口（与 P0 的投影 API 合并设计更佳）。
- `VectorraMvtDecoder` 是 `@VectorraBetaApi` 公共 API，已有用户可见面；迁移后可保留为纯工具类（不再在渲染热路径上），或按 beta 语义废弃。

## P1：MVT 瓦片调度（覆盖计算 / 金字塔 / 缓存淘汰）

### 现状

`VectorraMvtTileCover`（视口覆盖瓦片计算，平面 Mercator + bearing 旋转矩形，`VectorraMvtTileCover.kt:38-108`）、`VectorraMvtTilePyramid`（父子瓦片回退）、`VectorraMvtRuntimeTileStore`（已加载/在渲染瓦片集合、LRU trim）、`VectorraMapEngine.updateVectorTileLayers`（每帧 Choreographer 驱动、6 线程优先级队列、generation/requestId 防竞态，`VectorraMapEngine.kt:933-1138`）构成一套完整的 JVM 端瓦片调度器。

### 问题

1. 覆盖计算同样用平面 Mercator 近似球体相机——pitch 下可见瓦片估算偏差，并且**不含 pitch 时远处瓦片的放大覆盖**（MapLibre 的 tile cover 对 pitched view 有专门处理）；相机下沉 native 后，Kotlin 侧没有权威相机可用，这套调度必须跟着走。
2. rocky 自身的 raster/terrain 瓦片管线（`TerrainTileNode`/`TileLayer`，含 SSE 驱动细分、缓存、并发控制）已在 native 解决同类问题，MVT 是平行的第二套调度器。
3. 每帧 `visibleTileIds` 重算多次（`loadVectorTileTask` 成功路径里又算两次，`VectorraMapEngine.kt:1063-1075`），均为分配密集的 Kotlin 集合操作。

### 建议

分两步：先迁解码（上节，独立可做），再把调度建成 native `MvtLayer`——挂进 rocky 的 map/layer 体系，复用其相机驱动的瓦片选择与生命周期（参考 rocky `TileLayer` 与 `vectorra-references/rocky/`），瓦片获取经现有代理 URL（与 raster 路径一致）。Kotlin 的 `addVectorTileLayer` 收敛为一次配置调用，删除 `VectorraMvtTileCover/Pyramid/RuntimeTileStore` 与引擎里约 400 行调度代码。

这一步依赖 P0/P1 的相机下沉（native 调度需要权威相机），顺序不能颠倒。

## P1~P2：瓦片网络栈（回环 HTTP 代理）

### 现状

- raster/DEM 瓦片：native curl → `http://127.0.0.1:port/tile/{uuid}?z&x&y`（Kotlin `TileProxyServer`，手写 HTTP/1.1 server，`TileProxyServer.kt:120-297`）→ Kotlin 调度器/缓存/拦截器链 → `HttpURLConnection` 外网请求 → 字节经回环写回 → native 解码图片。
- 3D Tiles：native rocky URI（libcurl）直连，不走代理（`vectorra_jni.cpp:1233-1241`）。
- MVT/资源预取：Kotlin `TileResourceFetcher` 直接 HTTP，不经代理。
- 代理存在的真实理由：`ROCKY_SUPPORTS_HTTPS OFF`（`CMakeLists.txt:24`）——native 无 TLS，HTTPS 必须由 JVM 终结；以及拦截器（`TileRequestInterceptor` 公共 API）、Kotlin 缓存、MBTiles 本地 provider 复用同一 URL 形态。

### 问题

1. **每瓦片两次 socket + 双倍内存拷贝**：回环虽快，但 `Connection: close`（`TileProxyServer.kt:291`）使 native curl 对**每个瓦片新建一条 TCP 连接**，无 keep-alive、无复用。
2. **代理 URL 含随机 UUID**（`TileProxyServer.kt:79-88`：每次 `addRasterLayer` 生成 `UUID.randomUUID()`，端口也是随机的）。rocky 的磁盘缓存按 URI 做 key（`ROCKY_CACHE_PATH`，512MB），**冷启动后全部 miss**——native 磁盘缓存对走代理的图层形同虚设，且与 Kotlin `TileCacheStore` 双份落盘同样的字节。
3. 三条网络路径（代理 / rocky URI / TileResourceFetcher）能力不一致：拦截器与 Kotlin 缓存只覆盖代理路径，3D Tiles 的 header 注入是另一套（`headersFor` 拼接后直传 native）。

### 建议

短期（保留代理，修缺陷，小改动）：

- 代理注册 key 从 UUID 改为稳定的 `sourceId:layerId`，监听端口固定化或将端口写入 native 缓存 key 之外——让 rocky 磁盘缓存跨会话命中；二选一：禁用走代理图层的 rocky 磁盘缓存（避免双份落盘），由 Kotlin 缓存统一负责。
- 代理支持 HTTP/1.1 keep-alive，消除逐瓦片 TCP 握手。

长期（真正下沉，较大工程）：

- vcpkg 给 curl 加 TLS backend（mbedTLS/openssl），`ROCKY_SUPPORTS_HTTPS ON`，raster/DEM 改走 rocky URI 直连 + native 缓存；Kotlin 拦截器降级为可选回调（仅当用户注册了拦截器才经 JNI 上行），`TileNetworkConfig` API 面保持不变。
- MBTiles：`ROCKY_SUPPORTS_MBTILES`（现为 OFF）+ sqlite3 依赖可让 native 直读 .mbtiles，去掉"SQLite → 回环 HTTP → native"的绕行；Kotlin 的 `VectorraMbTilesRasterSource` 保留为元数据/校验 API。

这一项收益是确定的但不是当前瓶颈（瓦片量级低于 MVT 解码的对象churn），列 P1~P2，建议先做短期两项。

## P2：GeoJSON 聚类与查询索引（暂缓迁移）

`VectorraGeoJsonIndex` 的贪心聚类是 O(n²) 且每次查询全量重算（`VectorraGeoJsonIndex.kt:95-153`，`query` 入口先 `clusterLeaves.clear()`）。但当前 GeoJSON source/layer **只服务查询，没有 native 渲染路径**（`VectorraMapEngine.kt:527-541` 只写入索引），把它迁到 C++ 不解决任何渲染问题。结论：

- 暂缓迁移；它的正确性问题（投影）随 P0 的 native 投影 API 自动修复（`project` lambda 换实现即可）。
- 未来做 GeoJSON native 渲染时，聚类应以 supercluster 形态（参考 `vectorra-references/maplibre-native/` 的实现）随渲染一起下沉，届时 Kotlin 索引整体退役。
- 若短期数据量上来，先把 Kotlin 实现改为缓存网格聚类结果（按 zoom 整级缓存）即可，不必动语言边界。

## 杂项佐证（双份维护已经发生的证据）

| 证据 | 位置 |
| --- | --- |
| zoom→range 公式 Kotlin/C++ 各一份，Kotlin 版仅测试引用 | `VectorraCameraRange.kt:7-28` vs `vectorra_jni.cpp:3344-3358` |
| 渲染视口降采样逻辑两份（max 1280px 缩放） | `VectorraMapEngine.kt:1569-1582` vs `vectorra_jni.cpp:3360-3372` |
| 投影 tile size 不一致：引擎 256 / 命中测试 512 / native range 公式 512 | `VectorraMapEngine.kt:1697`、`VectorraAnnotationHitTester.kt:248` |
| pitch 上限不一致：Kotlin 80°、native clamp 等效 85° | `VectorraMapEngine.kt:1696`、`vectorra_jni.cpp:3384` |
| 纬度 clamp 不一致：引擎 ±85.0、命中测试 ±85.05112878 | `VectorraMapEngine.kt:1691`、`VectorraAnnotationHitTester.kt:249-250` |
| 死 JNI 接口：`setTileset3DLayerViewportHeight` 声明后无 Kotlin 调用方 | `VectorraNative.kt:111` |
| native `onTouch` 空壳：只存触点，未接 manipulator | `vectorra_jni.cpp:1608-1618` |

## 不建议迁移（保留 Kotlin）

- **手势识别本身**（touch slop、双击窗口、velocity tracker）：Android 平台惯例，留在 `VectorraMapView`；下沉的是"识别结果 → 相机"的换算。
- **`TileNetworkConfig` / 拦截器 / 重试 / URL 改写 API**：公共 Kotlin API，用户以 JVM 对象注入；执行层未来可下沉，API 面不动。
- **Offline/Prefetch 编排**（`VectorraOfflineManager`、`VectorraPrefetchTaskRunner`、`VectorraOfflineRegion` 瓦片枚举）：I/O 编排 + 进度回调，无热路径。
- **MBTiles SQLite 读取**：依赖 `android.database`，除非走 `ROCKY_SUPPORTS_MBTILES`（见上文长期项）。
- **定位组件、生命周期、截图（PixelCopy）、asset 解压**：Android 服务绑定，无迁移价值。
- **资源状态簿记**（`emitResourceStatus` 等）：监听器分发属于 API 层；native 侧需要做的是补发更多状态事件，而不是接管簿记。
- **`:vectorra-maps-turf`**：纯几何工具库，独立模块、有测试、非热路径。

## 建议迁移顺序

| 阶段 | 内容 | 依赖 | 主要验证 |
| --- | --- | --- | --- |
| 1 | native 投影 API（worldToScreen/screenToWorld 批量接口），hitTester/GeoJsonIndex 换投影实现 | 无 | pitch>0、高纬度下点击查询/`pixelForCoordinate` 与渲染画面一致；现有 query 单测改造 |
| 2 | 相机 owner 下沉 MapManipulator：语义化手势命令 + `setViewpoint(duration)` 动画 + 相机回调；删除 Kotlin Mercator 相机数学与双份公式 | 1 | 手势手感对比（焦点缩放锚定、fling）、`cameraState` 回传时序、相机单测迁移 |
| 3 | MVT 解码下沉：字节直传 JNI，C++ protozero 解码 + 坐标换算；查询几何由 native 返回 | 可与 2 并行 | 解码正确性对拍（同瓦片 Kotlin/C++ 输出 diff）、滑动帧率与 GC 监控对比 |
| 4 | MVT 调度下沉为 native MvtLayer（复用 rocky TileLayer 模式） | 2、3 | 瓦片加载/淘汰行为对比、`VectorraMvtRuntimeTileStoreTest` 语义迁移 |
| 5 | 网络栈：短期修代理（稳定 URL key、keep-alive、去双份磁盘缓存）；长期 native TLS + 缓存直连 | 无（短期项可立即做） | 冷启动瓦片缓存命中率、单瓦片延迟对比 |

每阶段完成后 Kotlin 侧应有对应的**删除**（不是并存）：阶段 2 删相机数学，阶段 3 删 `VectorraMvtDecoder` 热路径，阶段 4 删调度器。两套实现并存超过一个阶段就会重演现在的双份漂移。

## 风险与前置条件

- **JNI 回调线程**：相机回传、资源状态回调发生在渲染/update 线程，Kotlin 端必须继续经 mainHandler 转发（现有 `dispatchResourceStatus` 模式已正确处理）。
- **单测迁移成本**：Kotlin 侧相机/瓦片覆盖/调度有较完整 JVM 单测（`VectorraMvtTileCoverTest`、`VectorraMvtRuntimeTileStoreTest` 等），下沉后需要等价的 native 测试或 instrumented 测试承接，否则回归保护净减少。
- **公共 API 兼容**：`VectorraMvtDecoder`、`TileProxyServer` 是 public/beta API；迁移按 beta 废弃流程处理，`VectorraMap` 接口本身不受影响。
- **rocky 改动边界**：MvtLayer、投影接口尽量实现在 `vectorra_jni.cpp` 或新增 bridge 源文件；确需进 `third_party/rocky` 时注意其 CMake 在 configure 期 glob 源文件（新增 .cpp 需 touch `src/rocky/CMakeLists.txt`）。
