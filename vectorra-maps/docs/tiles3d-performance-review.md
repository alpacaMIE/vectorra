# 3D Tiles 性能审查与优化方案

本文基于对 `Tiles3DNode` / `Tiles3DLayer`、网络层（`URI.cpp` / curl）、缓存（`Cache.h`）、compile 管线（`VSGContext`）和 JNI 接线的审查，并结合 vsgXchange v1.1.9 `SceneGraphBuilder` 行为与本地 vcpkg curl feature 状态整理。

当前架构（遍历驱动选择 + SSE 优先级队列 + in-flight 预算 + 批量 compile + LRU 过期）整体是健康的，上一轮修复解决了正确性问题。剩余性能空间集中在五个结构性问题上，其中一个是直接 bug。

## 0. 先修 bug：SSE 使用了未缩放的 viewport 高度

该问题会同时影响加载速度、FPS 和内存占用。

- Vulkan 渲染表面被限制在长边 1280px（`vectorra_jni.cpp:59`、`vectorra_jni.cpp:1726-1752`），但 `setViewportHeight()` 传入的是未缩放的 Android view 高度（`vectorra_jni.cpp:745`、`vectorra_jni.cpp:1230`）。
- 在 1080x2400 的典型设备上，SSE 被高估约 1.9 倍，细分会深约一个级别，下载、编译和渲染的 tile 量约为必要值的 2-4 倍。
- 修法：两处改传 `effectiveRenderHeight()`（`vectorra_jni.cpp:3346`，MVT 路径已经在使用它）。这是性价比最高的一项。

## 1. 网络加载速度

### 现状

- curl 8.20，HTTP/1.1（vcpkg 无 `http2` feature）。
- thread-local easy handle，6 个工作线程最多对应 6 条并发连接。
- gzip 已开启。
- 同 URI 并发去重已有 `ScopedGate`。
- 永久失败有 deadpool。

### P0：curl 调优与取消链路

`http_get_curl()`（`URI.cpp:267-309`）缺少几个关键选项：

- `CURLOPT_NOSIGNAL=1`：多线程下必须。
- `CURLOPT_LOW_SPEED_LIMIT` / `CURLOPT_LOW_SPEED_TIME`：目前只有连接超时，没有传输超时。一条 stall 的传输会永久钉死 6 个线程之一。
- `CURLOPT_TCP_KEEPALIVE=1`、`CURLOPT_BUFFERSIZE=512KB`。

重试退避目前睡在池线程里（`URI.cpp:325-330`，最长数秒的 `sleep_for`）。tile 请求应设 `maxNetworkAttempts=1`，把退避完全交给 `Tile3DNode` 已有的 `failWithBackoff`，避免占用线程。

取消链路当前没有接通：

- 载入 lambda 用的是 `vsgctx->io`（无 cancelable），应改为 `u.read(io.with(c))`（`Tiles3DNode.cpp:421`）。
- curl 路径没有 progress 回调，中途无法 abort；httplib 路径已有类似能力（`URI.cpp:451-454`）。需加 `CURLOPT_XFERINFOFUNCTION` 检查 `io.canceled()`。
- 没有任何路径会放弃离屏 tile 的 in-flight future：`unloadContent()` 对未完成的加载直接返回 `false`（`Tiles3DNode.cpp:574`）。快速拖动时最多 32 个过期请求仍会继续消耗带宽和加载槽位。
- 还有一个隐性滞留：下载完成但已离屏的 tile，解析好的 `vsg::Node`（含解码后纹理）挂在 `_loadFuture` 里。这种 tile 不在 LRU tracker 中，直到相机回访前不会被驱逐。建议在 update 订阅或 expire pass 中增加“连续 N 帧未被遍历且 future 已 available，则丢弃 future”的清理。

### P1：结构性提速

- 磁盘缓存：目前每次冷启动都会全量重下，NLSC 单 `tileset.json` 就有 49.7MB。可在 `URI::read` 加一层 content-addressed 磁盘缓存（Android `cacheDir`，URL hash 做 key，可选 ETag 校验）。复访场景的加载速度提升会是数量级的。这也是 `log.md` 里已列的 P1。
- 下载与解码分池：现在下载、Draco、WebP 解码同跑在 6 线程池（`Tiles3DNode.cpp:468`）。解码占线程时网络管道会空转。建议拆成 network pool（8-12 线程，纯 I/O 等待）和 decode pool（约 CPU 核数 - 2），`maxConcurrentLoads` 跟随 network pool 深度调整。
- HTTP/2（中期）：vcpkg curl 加 `http2` feature（引入 nghttp2）并改 multi interface。单连接多路复用对“大量小文件”型 tile 服务收益显著。HK 数据集是 http 明文 + 外链 json 链，h2 收益尤其大。在此之前，可先用 curl share interface 跨线程共享 DNS/TLS session。

## 2. FPS

### P0：接入 sharedObjects

这是已验证、预期最大的单项 FPS / 加载耗时收益。

- vsgXchange v1.1.9 的 `SceneGraphBuilder` 逻辑是：如果 `options` 存在则取 `options->sharedObjects`；如果仍为空，则创建新的 `vsg::SharedObjects`。rocky 目前没有给 `readerWriterOptions` 设置 `sharedObjects`（`VSGContext.cpp:719` 起的构造逻辑）。
- 结果是每个 tile 各自重建 PBR `ShaderSet`、shader module、pipeline、sampler。移动端一条 pipeline 创建动辄几十 ms，这是当前每 tile compile 慢、GPU 对象成倍增长的主因。NLSC（无纹理、顶点色）共享后 pipeline 几乎全部去重；HK（每 tile 独立纹理）pipeline 仍可能每 tile 一条，但 shader module 复用和驱动 pipeline cache 命中会让创建成本大幅下降，sampler 数量也不再逼近驱动上限。
- 建议每个 `Tiles3DNode` 持有一个专属 `vsg::SharedObjects`，在载入 lambda 中赋给 `localOpts->sharedObjects`（`Tiles3DNode.cpp:452`），随 layer 关闭整体释放；并在 `expireTiles` 里每 N 帧调用一次 `prune()`。原因是共享的 pipeline config 持有 descriptor（含纹理数据）强引用，不 prune 会越积越多。已核实本地 VSG 的 `SharedObjects` 用 `recursive_mutex` 保护，6 个并发载入线程安全，且提供 `prune()`。

### P0 / P1：compile 移出帧循环

- `processCompileQueue` 在 update pass（帧循环线程）阻塞等 fence，每 pass 4 个 tile（`Tiles3DNode.cpp:157`）。纹理大时一帧多挂 10-50ms，加载期掉帧明显；同时 4/pass 也是吞吐上限，积压 25 个 tile 需要 6 帧以上才能清完。
- rocky 自家的 `NodePager` 已经在 worker job 里直接调 `vsgcontext->compile()`（`NodePager.cpp:151`）。可按这个先例，把 compile 挪进载入 job 末尾（`CompileManager` 内部排队、线程安全），onNextUpdate 只做 `_content = node` 接线和 `requestFrame`。需要注意把 `onCompileFailed` 的状态字段访问改成线程安全，并保留 `callerHandlesFailure` 语义。完成后帧循环不再被编译阻塞。

### P1：遍历开销

- 每个可见子 tile 每帧被做 3 次 frustum 测试和 2 次 SSE（`allChildrenReady`、pre-fetch 循环、子节点自身 traverse）。可给 `Tile3DNode` 缓存 `(frameCount, 测试结果)` 去重。
- `touchTile` 每个有内容的 tile 每帧拿一次全局 mutex（`Tiles3DNode.cpp:69-80`）。可改成原子写 `lastUsedFrame`，由 expire pass 统一重排 LRU，让记录线程完全无锁。
- HK 数据集全部是 box volume，当前用外接球剔除（`Tiles3DNode.cpp:311`），球比 OBB 膨胀很多，导致多载多画。建议给 box 加 OBB-frustum 测试。

### P2：架构级优化

- 将选择逻辑移到 update 阶段，record 阶段只负责绘制（`log.md` 已列）。
- 引入 Cesium 式 skip-LOD，避免 HK 这种 `json -> json -> b3dm` 深链必须逐级下载中间 LOD。

## 3. 内存占用限制

### P0：contentCache 是当前最大的失控项

- 每个下载成功的 tile 原始字节（b3dm / glb，MB 级）都会进入 `contentCache`。它是一个按条目数（1024）而非字节数封顶的 LRU（`VSGContext.cpp:983`，`Cache.h:200-206`），49.7MB 的 `tileset.json` 也只是一个条目。最坏情况下，原始字节就能占用数百 MB 到 1GB，并与解析后节点、GPU 副本形成三重占用。
- 方案：`LRUCache` 增加字节预算模式（例如 64-128MB 上限），且 tile 内容这种“大、低复用”载荷直接绕过内存缓存，用磁盘缓存补位。
- 顺带：`URI::read` 把每个响应体完整拷贝了两次（`URI.cpp:786` 的 `put`、`URI.cpp:789` 的 `URIResponse` 构造）。改成 move 即可省掉每 tile 两次 MB 级 memcpy。

### P1：262k tile 元数据瘦身

NLSC 实测 PSS 约 935MB，主因是元数据体积。

- 解析峰值：49.7MB JSON 用 nlohmann DOM 解析，瞬时占用约数百 MB。建议换 rapidjson（已在依赖树）in-situ 或 SAX 流式解析。
- 常驻体积：`Tiles3DTile` 每个实例带 3 个 `optional<array<double,N>>`（约 190B，只用其一）。解析时可直接折算成 `dsphere`，box 则折算为 OBB。每 tile 一条 resolved 绝对 URI 长字符串，可改成存相对 URI + base 索引，请求时再拼。`Tile3DNode` 整份拷贝 `Tiles3DTile`（`Tiles3DNode.cpp:282`），可改为持有 `shared_ptr<const>`。粗估可砍 50%-70%。
- `_childGroup` 只增不减：长时间漫游后所有访问过的内部节点常驻。可增加“长期不可见子树折叠”，释放 `Tile3DNode`，保留数据模型，回访时重建。

### P1：GPU 字节预算驱逐

`maximumLoadedTiles` 按条数限制，但 tile 体积差异极大。Android 诊断代码里已有现成的 `CollectResourceRequirements` 字节统计（`VSGContext.cpp:1076-1098`），可把它变成每 tile 记账，驱逐改为“字节预算 + 条数”双限；也可读取 `VK_EXT_memory_budget` 后自适应收紧。

### P2：纹理侧优化

vsg 会按 sampler `maxLod` 自动生成 mipmap，GPU 纹理内存增加约 33%，也会增加 transfer 时间。可提供 `maxTextureSize` 降采样选项；长期看，服务端输出 KTX2 / BasisU 压缩纹理才是根治。

## 优先级总表

| 优先级 | 项目 | 改动量 | 预期收益 |
| --- | --- | --- | --- |
| P0 | SSE 改用 `effectiveRenderHeight` | 2 行 | 全链路负载降低到约 1/2-1/4 |
| P0 | `readerWriterOptions` / `localOpts` 接 `sharedObjects` + `prune` | 约 20 行 | compile 耗时大降，GPU 对象成倍减少 |
| P0 | `contentCache` 字节预算 / tile 绕过 + `read` 去拷贝 | 小 | 数百 MB 级内存回收 |
| P0 | curl 超时 / `NOSIGNAL` / keepalive；重试不睡池线程 | 小 | 消除 stall 钉死与吞吐塌陷 |
| P0 | 取消链路接通（`io.with(c)` + `XFERINFO` + 离屏放弃） | 中 | 拖动场景带宽和槽位不再浪费 |
| P1 | compile 移入载入 job（`NodePager` 模式） | 中 | 加载期帧时间不再被 fence 阻塞 |
| P1 | 磁盘缓存 | 中 | 复访加载速度数量级提升 |
| P1 | 下载 / 解码分池 | 小到中 | 网络管道持续打满 |
| P1 | 元数据瘦身 + SAX 解析 | 中到大 | NLSC 常驻 / 峰值内存减半以上 |
| P1 | GPU 字节预算驱逐 | 中 | 内存上限可控，低端机稳定 |
| P2 | OBB 剔除、遍历去重、update 阶段选择、skip-LOD、HTTP/2 | 大 | 长期架构收益 |

## 验证建议

建议固定相机路径脚本化（例如 adb swipe 序列），三个指标分别使用：

- 加载完成时间分布：`rocky_tiles3d` 日志。
- 内存：`dumpsys meminfo` 的 PSS。
- 帧时间：`dumpsys gfxinfo`。

HK（Draco + WebP 纹理重）和 NLSC（262k 元数据重）两个数据集都要跑。它们的瓶颈不同：前者主要压 compile / 纹理内存，后者主要压元数据 / 遍历。

## 推荐第一批改动

建议第一批先做四个 P0 小项：SSE 高度、`sharedObjects`、`contentCache`、curl flags。一次构建即可在两个数据集上看到明显差异。
