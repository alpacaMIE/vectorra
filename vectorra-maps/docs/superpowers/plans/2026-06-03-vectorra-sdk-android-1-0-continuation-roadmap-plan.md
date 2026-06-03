# Vectorra SDK Android 1.0 Continuation Roadmap Plan

## Goal

把当前 Android Beta 能力推进到可发布的 1.0：先前置状态语义、native 事件通道、`TileCacheStore` 内部骨架，并固化 GLB/GLTF、raster、DEM、MBTiles 基线；再实现正式 3D Tiles Runtime、MVT 渲染与查询、离线缓存产品化；最后完成 Android 1.0 发布硬化。

## Source Scope

产品实现、测试和文档修改限定在 `vectorra-maps/` 内。不要修改 `vectorra-references/`。除非具体任务明确要求 vendored rocky 适配，否则不要修改 `vectorra-maps/third_party/rocky/`。

Gradle 命令从 `vectorra-maps/` 执行。

## Interface Decisions

Android 1.0 必须先固化 layer/source 状态语义与公开状态契约。状态语义先定义事件 payload、resource kind、source/layer id、generation、状态值、错误类型/消息、事件来源和线程投递规则；公开 API 的最小形状是状态类型、监听 API、当前状态查询 API；状态值覆盖 loading/loaded/failed/removed。状态来源只能是 engine/native 回调，sample、3D Tiles、MVT、offline 不得各自维护第二套产品状态。

Android 1.0 的唯一正式 3D Tiles 公共 API 路径是 source/layer/options：

- `Vectorra3DTilesSource`
- `Vectorra3DTilesLayer`
- `Vectorra3DTilesOptions`

现有 `VectorraMap.add3DTilesModelLayer(...)` 只作为 native model smoke 的过渡入口保留，从 Phase 0 起标记为 deprecated/experimental，不继续承载新的 3D Tiles runtime 能力。Phase 1 之后的新功能必须接入 `Vectorra3DTiles*` source/layer 路径。Phase 4 发布硬化时，`add3DTilesModelLayer(...)` 必须从正式集成文档中移除或保持 deprecated/experimental；不能同时把它和 `Vectorra3DTiles*` 作为 1.0 正式入口。

`VectorraMap.addModelLayer(...)` 是独立 GLB/GLTF model layer API，不是 3D Tiles API。

3D Tiles content 进入 native renderer 前必须先定义 renderer contract。P1 支持 full transform matrix 或 ECEF placement，不把 tileset/tile transform 压缩到现有 lon/lat/height/scale/yaw model API。b3dm 内嵌 GLB payload 固定写入 `TileCacheStore` 管理的 cache URI 后渲染，不新增 native bytes 渲染通道。

MVT runtime 先定义 native vector render contract，再实现 Kotlin runtime tile store。已加载 tile、decoded tile、native render handle、query index 和 unload 状态由 Kotlin runtime tile store 作为唯一 owner。`queryRenderedFeatures` 只查询仍处于 loaded 状态的 tile feature。

P0 引入内部 `TileCacheStore` skeleton/adapter，作为 P1 3D Tiles 与 P2 MVT 请求复用的缓存 owner 接口。P3 再把 prefetch、proxy 请求、cleanup 和 cache status 产品化为唯一缓存 owner。`TileProxyServer` 可以继续处理 HTTP 代理职责，但不能成为 offline API 的状态来源。

## Phase-Task Backlog

### Phase 0 - 渲染链路固化

| Task | 内容 | 验收 |
| --- | --- | --- |
| P0.T0 状态语义与事件通道 | 定义 layer/source 状态事件 payload、resource kind、source/layer id、generation、状态值、错误类型/消息、事件来源和线程投递规则；明确 loading/loaded/failed/removed 对 raster、DEM、model、MBTiles 的语义 | P0.T1/T2 不再需要补状态身份、生命周期或 native callback 设计；sample 和测试能按同一语义断言状态 |
| P0.T1 状态公共契约 | 按 P0.T0 新增公开 layer/source load/error 状态类型、监听 API、当前状态查询 API | SDK 使用者、sample、测试都通过同一状态契约读取 loading/loaded/failed/removed；API 不暴露 native/proxy 细节 |
| P0.T2 Native 状态桥接 | 实现 JNI/native 到 Kotlin 的状态事件桥接，把 raster、DEM、model 的 loading/loaded/failed/removed 状态从 native/engine 回传 Kotlin | 状态来源只有 engine/native 回调；sample 不另建状态；native model/raster/DEM error 不再只落 logcat |
| P0.T3 基线验证 | 运行 unit tests、sample assemble、全工程 assemble；记录 model/raster/DEM/MBTiles 当前状态 | 构建结果和已知问题清单落文档；阻断项标明 owner、复现步骤、影响能力 |
| P0.T4 Model 回归 | 固化 local/remote `.glb/.gltf` 加载、显示、隐藏、删除、重加；坏 URL 进入状态错误路径 | sample 可见 local/remote model；坏 GLB/坏 URL 在 sample 错误 UI 可见；remove/re-add 无崩溃 |
| P0.T5 Raster/DEM 回归 | 验证 XYZ/TMS/WMTS、headers、proxy/cache、terrain exaggeration、visibility | raster 与 DEM pitched browsing 可见；visibility 切换和 cache hit 不改变状态语义；错误进入统一 layer/source 状态 |
| P0.T6 文档与过渡 API | 更新 README、3D Tiles、API stability 文档，删除旧的 model 未确认描述；从 P0 起标记 `add3DTilesModelLayer` 为 deprecated/experimental smoke API | 文档与当前能力一致；`add3DTilesModelLayer` 不作为 1.0 正式 3D Tiles 入口 |
| P0.T7 P0 设备 smoke | 在至少一个 Vulkan-capable 设备或模拟器执行 native smoke | 记录 device/API/ABI/GPU/Vulkan；覆盖启动、加载、坏 URL、visibility、remove/re-add、旋转或 pause/resume、截图非空、销毁重建 |
| P0.T8 `TileCacheStore` 内部骨架 | 新增 internal `TileCacheStore` skeleton/adapter，封装 cache key、memory/disk 读写入口、cache URI 分配入口和 proxy/local provider 适配边界 | P1/P2 请求不直接依赖 `TileProxyServer` 自有缓存状态；P3 只做 offline API、prefetch、cleanup、cache status 产品化 |
| P0.T9 P0 验收 | 汇总状态契约、缓存骨架、测试和设备验证结果 | P0 无 P0/P1 blocker；后续 phase 不需要重复定义状态源或缓存 owner |

### Phase 1 - 3D Tiles Runtime Beta

| Task | 内容 | 验收 |
| --- | --- | --- |
| P1.T0 3D Tiles Renderer Contract | 定义 3D Tiles content 到 native renderer 的输入契约，支持 full transform matrix 或 ECEF placement；b3dm 内嵌 GLB payload 固定写入 `TileCacheStore` cache URI 后渲染，不新增 native bytes 通道 | P1 不依赖现有 lon/lat/height/scale/yaw model API 表达 tileset transform；b3dm payload 输入方式确定；renderer contract 有 Kotlin/JNI/native 测试或 compile smoke |
| P1.T1 API 定义 | 新增正式 `Vectorra3DTilesSource`、`Vectorra3DTilesLayer`、`Vectorra3DTilesOptions`；不扩展 `add3DTilesModelLayer` | source/layer/options API 可编译并有模型测试；sample 使用正式 source/layer 路径加载 3D Tiles；状态接入统一 layer/source 状态契约 |
| P1.T2 Tileset 加载 | 支持 URL/本地 `tileset.json`，复用 base URI 解析 content URI | 加载失败进入统一 layer/source error；本地/远程 content URI fixture 解析一致 |
| P1.T3 Runtime Tile State 与 Traversal | 建立 3D Tiles runtime tile state；实现 camera-driven traversal、SSE/LOD、视锥/距离筛选、请求优先级、unload budget，支持 `ADD`/`REPLACE` | traversal fixture 覆盖 root、child、`ADD`、`REPLACE`、SSE 阈值、视锥/距离筛选、预算截断；pan/zoom 后 tile load/unload 正确且状态明确 |
| P1.T4 GLB/GLTF Content 生命周期 | 加载 `.glb/.gltf` tile content，复用 headers、interceptor、`TileCacheStore` cache，支持取消、去重、unload 和错误上报 | content 请求状态可追踪；同一 tile content 不重复加载；tile unload 后不再渲染或保留查询状态 |
| P1.T5 Transform 与 Bounds | tile content 复用 P1.T0 renderer contract，应用 tileset transform、tile transform、`RTC_CENTER` 合成后的 renderer transform 与 visibility；使用 region/box/sphere 做 runtime debug 与基础筛选 | 固定 tileset fixture 的经纬度、高程、姿态、transform chain、`RTC_CENTER`、可见性、WGS84/ECEF debug bounds 测试通过 |
| P1.T6 b3dm 渲染支持 | 解析 b3dm header、feature table、batch table 和内部 GLB payload；正确应用 `RTC_CENTER` 到 tile/model transform；不支持变体早失败 | b3dm fixture 覆盖 header、feature/batch table 长度、padding、GLB 偏移、`BATCH_LENGTH`、`RTC_CENTER` 和渲染路径 |
| P1.T7 P1 设备 smoke | sample 通过 `Vectorra3DTiles*` 加载 building/model tileset 并执行 native 生命周期验证 | UI 无明显卡顿；remove/re-add、旋转或 pause/resume、截图非空、销毁重建无崩溃；错误 tileset 在 sample 可见 |
| P1.T8 P1 验收 | 汇总 API、fixture、设备验证结果 | 3D Tiles 正式入口只有 `Vectorra3DTiles*`；b3dm 渲染和 `RTC_CENTER` 正确定位无 P0/P1 blocker |

### Phase 2 - MVT 渲染与查询

| Task | 内容 | 验收 |
| --- | --- | --- |
| P2.T0 MVT Native Render Contract | 定义 vector tile feature batch、style 参数、native render handle、tile unload/remove 的 native API 契约 | P2.T2 的 Kotlin store 不再隐含 native handle 自然存在；render/update/remove/unload 输入输出固定并有 compile smoke |
| P2.T1 Vector Tile API | 新增 `VectorraVectorTileSource` 和简化 line/fill/circle/symbol layer options | API 不暴露 Mapbox Style JSON 兼容承诺；source layer/filter 命名明确；状态接入统一 layer/source 状态契约 |
| P2.T2 Kotlin MVT Runtime Tile Store | 基于 P2.T0 新增 Kotlin store，唯一管理 decoded tile、native render handle、query index、load/update/unload | pan/zoom/remove 后卸载 tile 从 store、native render handle、query index 同步移除；无第二 owner |
| P2.T3 Tile 加载 | 复用 tile proxy、headers、interceptor、`TileCacheStore` cache，支持 XYZ/TMS，并把加载结果写入 Kotlin store | 网络失败进入统一 layer/source error；cache hit 与网络加载产出的 decoded tile 语义一致 |
| P2.T4 Geometry 转换 | 复用 `VectorraMvtDecoder`，将 extent 坐标转为 Web Mercator/WGS84 和 renderer 输入 | fixture 覆盖 z/x/y、extent、Y-down、tile 边界、polygon ring；转换误差有明确容差 |
| P2.T5 基础渲染 | 实现最小可见 line/fill/circle，symbol 首版用简单 label；renderer handle 由 Kotlin store 记录 | sample 可见 MVT 图层；pan/zoom 后卸载 tile 不再参与渲染；跨 tile 边界不留下 stale feature |
| P2.T6 Feature Query | `queryRenderedFeatures` 查询 Kotlin store 中已加载 tile feature，返回 layer/source layer/id/type/properties | fixture 点击坐标命中预期 feature；source-layer filter 生效；跨 tile 和 cache-hit 查询结果一致；unload 后不再命中 |
| P2.T7 P2 设备 smoke | sample 加载 MVT layer，执行 pan/zoom、query、visibility、remove/re-add | MVT 图层可见，query 命中稳定，remove/unload 后不再参与渲染或查询，错误 tile 在 sample 可见 |
| P2.T8 P2 验收 | MVT 渲染与查询不破坏 raster/DEM/model | 相关回归测试通过；P2 fixture 和 sample query 记录通过；runtime tile store 无重复状态源 |

### Phase 3 - 离线与缓存产品化

| Task | 内容 | 验收 |
| --- | --- | --- |
| P3.T1 `TileCacheStore` 产品化 | 在 P0.T8 骨架基础上补齐 prefetch、proxy、cleanup、cache status 的唯一产品级缓存 owner 能力 | `TileCache` 与 `TileProxyServer` 不再分别拥有产品级缓存状态；proxy/local provider 均通过 `TileCacheStore` 读写可缓存内容 |
| P3.T2 Offline API | 新增 `VectorraOfflineManager`、`VectorraOfflineRegion`、`VectorraPrefetchTask`、`VectorraCacheStatus` | API 不暴露 native/proxy 细节；offline 状态由 manager/task 单一来源维护；cache status 来源于 `TileCacheStore` |
| P3.T3 Region Prefetch | 支持 bounds、zoom range、source ids、progress、cancel；由 source 生成 tile 枚举并写入 `TileCacheStore` | tile 枚举 fixture 覆盖 bounds、zoom、XYZ/TMS；progress 单调；cancel 后任务进入 canceled，已完成缓存保留并可查询 |
| P3.T4 Cache 管理 | 支持 memory/disk size、entry count、cleanup、disk max bytes | cache key 包含 method、source/layer/resource type、normalized URL 或 local provider key、headers；interceptor 在 key 生成前确定请求，无法稳定确定 key 时 prefetch 失败并给出原因；cleanup 后状态准确 |
| P3.T5 Prefetch 错误策略 | 定义失败重试、并发、部分成功和失败汇总 | 失败 tile 有明确状态；retry 次数可测试；任务完成结果区分 completed/canceled/failed/partial |
| P3.T6 MBTiles Raster 完善 | 增强 raster MBTiles metadata validation 与 sample 错误提示 | raster MBTiles fixture 通过；不支持格式早失败且错误可见 |
| P3.T7 MBTiles MVT | 从 MBTiles 读取 vector tile bytes，复用 P2 MVT 渲染路径 | MVT MBTiles fixture 通过；离线读取与在线 MVT decoded/query 语义一致 |
| P3.T8 P3 设备 smoke | sample 加载离线 raster MBTiles 和 MVT MBTiles，验证离线显示、错误提示、cleanup、remove/re-add | 离线路径可见，错误可见，cleanup 后状态准确，remove/re-add 不产生重复 cache/source 状态 |
| P3.T9 P3 验收 | prefetch 后可离线显示，cleanup 后状态准确 | offline 场景测试通过；无重复 cache 状态源 |

### Phase 4 - Android 1.0 发布硬化

| Task | 内容 | 验收 |
| --- | --- | --- |
| P4.T1 Public API 清理 | 稳定命名、包结构、Beta/Experimental 标记；处理过渡 API | public API 清单明确；`add3DTilesModelLayer` 不作为 1.0 正式入口 |
| P4.T2 Error/Diagnostics | 统一 map/layer/source/network/offline/native error，补 redacted logging 文档 | sample 不依赖 logcat 才能理解错误；敏感 query/header 不泄露 |
| P4.T3 Sample 完整化 | sample 覆盖 raster、DEM、MVT、GeoJSON/drawing、3D Tiles、MBTiles、snapshot、location | 默认启动可见基础地图；每类能力有失败路径 UI |
| P4.T4 发布文档 | 更新 AAR integration、API stability、release/versioning、troubleshooting | 文档覆盖 1.0 集成路径；不宣传未验收能力 |
| P4.T5 AAR 验证 | 发布 SDK/Turf 到 Maven local，sample 使用 published AAR 构建 | AAR 包含 native libs/assets/rules/sources；sample 可用 published AAR 启动 |
| P4.T6 ABI 与设备矩阵 | 验证 `arm64-v8a` 与 `x86_64` 打包产物，并至少一台真实 Vulkan Android 设备执行完整 smoke | 双 ABI 打包通过；真实设备覆盖启动、加载、错误提示、visibility、remove/re-add、旋转/pause/resume、截图、销毁重建 |
| P4.T7 1.0 验收 | unit tests、sample assemble、全工程 assemble、AAR 验证、设备生命周期回归 | 核心能力无 P0/P1 blocker；未覆盖设备/ABI 只能作为明确 release risk 记录 |

## Public API Changes

- 新增公开 layer/source load/error 状态契约，最小形状为监听 API + 当前状态查询 API；状态值覆盖 loading/loaded/failed/removed，供 sample、业务方和测试共同使用；3D Tiles、MVT、offline 不再另建重复状态源。
- 新增正式 3D Tiles source/layer/options API；`add3DTilesModelLayer` 从 P0 起只保留为 deprecated/experimental smoke API，不承载 1.0 正式 3D Tiles runtime 能力。
- 新增 vector tile source 和简化 MVT style layer API，不实现完整 Mapbox Style JSON。
- 新增 offline manager、offline region、prefetch task、cache status API；offline API 不暴露 proxy/native 细节，cache status 由 `TileCacheStore` 支撑。
- 新增 internal `TileCacheStore` skeleton/adapter、3D Tiles renderer contract、MVT native render contract 作为正式 API 前置依赖；这些内部接口不暴露 native/proxy 细节。

## Verification Commands

每个 phase 从 `vectorra-maps/` 运行：

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat assembleDebug
```

P4 发布前额外运行：

```powershell
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

## Native Smoke Matrix

P0、P1、P2、P3、P4 的设备验证必须记录：

- device model、Android API、ABI、GPU/Vulkan 信息。
- cold start 到默认地图可见。
- local/remote model 或 tileset 加载成功。
- 坏 URL 或坏 tileset 错误在 sample UI 可见。
- visibility toggle、remove/re-add。
- rotate 或 pause/resume。
- snapshot 非空。
- destroy/recreate 不崩溃且资源释放可重复。
- P0 覆盖统一 layer/source 状态、native 状态 JNI 回调、坏 GLB/坏 URL UI。
- P1 覆盖通过 `Vectorra3DTiles*` 加载 building/model tileset、camera-driven traversal、pan/zoom tile load/unload、b3dm 渲染和 `RTC_CENTER` 正确定位。
- P2 覆盖 MVT pan/zoom、query、tile unload 后不再渲染或命中。
- P3 覆盖离线 raster MBTiles、MVT MBTiles、cleanup 和 remove/re-add。
- P4 覆盖 `arm64-v8a` 与 `x86_64` 打包产物，并至少一台真实 Vulkan Android 设备完整 smoke。

## Assumptions

- 平台范围限定为 Android 到 1.0；iOS/桌面不进入本规划。
- 时间口径为无日期里程碑，按 Phase 0 到 Phase 4 顺序推进。
- GLB/GLTF 视觉 model rendering 已修复，但必须在 P0 通过统一状态与设备回归固化。
- 3D Tiles 首版优先 building/model tileset；P1 必须支持 b3dm 渲染并正确应用 `RTC_CENTER`，不做 pnts、i3dm、cmpt、implicit tiling、metadata styling。
- b3dm 内嵌 GLB payload 在 P1 固定落 `TileCacheStore` cache URI 后复用 renderer，不新增 native bytes 渲染通道。
- MVT 首版只做简化渲染与查询，不做完整 Mapbox Style JSON。
