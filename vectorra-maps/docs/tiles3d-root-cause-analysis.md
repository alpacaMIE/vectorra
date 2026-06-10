# 3D Tiles 加载性能与贴图问题根因分析

日期：2026-06-10
范围：rocky 原生 3D Tiles 管线（`third_party/rocky/src/rocky/Tiles3D.*`、`rocky/vsg/Tiles3DNode.*`、`rocky/vsg/Tiles3DLayer.*`）、JNI 桥（`vectorra-maps/src/main/cpp/vectorra_jni.cpp`）、示例数据源 NLSC 台湾建筑 tileset。

---

## 0. 数据集实测（分析依据）

对示例数据 `https://3dtiles.nlsc.gov.tw/building/tiles3d/3/tileset.json`（`MainActivity.kt:1280`）实际抓取并解析：

| 指标 | 实测值 |
|---|---|
| tileset.json 体积 | **49,709,637 字节（约 49.7 MB，单体文件）** |
| 瓦片总数 | **262,267**，且**每个瓦片都有 content** |
| 树深度 | 0–14 层（峰值在 10/11 层：87,807 / 83,493 个） |
| content 格式 | 全部 `.glb`（无 b3dm） |
| boundingVolume | 全部 **sphere** 类型，中心模长 ≈ 6,375,199 m（即直接是 ECEF 坐标） |
| transform | **全树 0 个**（包括根） |
| refine | 根为 REPLACE，其余继承 |
| 根 geometricError | 2155.18 |

对 `0.glb`（根，919 KB）、`0_3_2_1.glb`（中层，15 KB）、`0_3_2_1_0_0_1_1.glb`（叶层，12.6 KB）解析 glTF JSON chunk：

- `images: 0`，`textures: 0`，`samplers: 0` —— **所有层级的 glb 都不含任何纹理**。
- 材质统一为 `baseColorFactor=[1,1,1,1], metallicFactor=0, roughnessFactor=0.95`（纯白 PBR）。
- 顶点属性为 `POSITION / NORMAL / COLOR_0 / _FEATURE_ID_0`，其中 `COLOR_0` 是 normalized `VEC4/UNSIGNED_BYTE` **顶点色**（实测值域如 min=[1,1,1,255]、max=[223,221,223,255]，即灰彩色建筑色）。
- `extensionsUsed = [EXT_texture_bound, EXT_structural_metadata, EXT_mesh_features]`。`EXT_texture_bound` 是 NLSC 的私有扩展（externally-bound texture），不在 `extensionsRequired` 中，glb 内部没有任何图像数据。

> 结论先行：**这个数据源本身就没有内嵌贴图**，颜色信息以顶点色（COLOR_0）形式存在。当前渲染出来的"黑色建筑"是光照缺失导致的，而不是"贴图丢了"。

---

## 1. 问题二：「加载的 3D Tiles 没有贴图」

截图（`vectorra-sample/test_tiles3d*.png`、`s1.png`/`s2.png`）显示建筑渲染为纯黑色块。两个独立根因：

### 根因 2.1（渲染侧，主因）：场景中没有任何光源，vsg PBR 着色器输出黑色

- 瓦片 glb 通过 `vsg::read_cast`（`Tiles3DNode.cpp:197`）由 vsgXchange/assimp 加载，产物使用 **VSG 标准 PBR/Phong ShaderSet**，其光照来自 record 阶段从场景图收集的 `vsg::Light` 节点。**没有灯 → PBR 输出 = 0 → 纯黑**。
- rocky 自带的地形着色器不依赖场景灯（默认 `lighting` 关闭），所以地图/影像正常显示，唯独 assimp 加载的模型黑掉——与截图完全吻合。
- rocky 所有示例程序都显式加灯：
  - `apps/rocky_simple/rocky_simple.cpp:253-256`：`view->addChild(vsg::createHeadlight())` + 弱环境光；
  - `apps/rocky_demo/rocky_demo.cpp:247`：`app.scene->addChild(vsg::createHeadlight())`；
  - `Application.cpp:218-232`：只有命令行 `--sky` 才会装 `SkyNode`（ambient + 太阳平行光）。
- 而 `vectorra_jni.cpp:1622` 以无参构造 `rocky::Application()`，**全文无任何 `Light` / `createHeadlight` / `SkyNode`**。所以场景零光源。

### 根因 2.2（数据侧）：NLSC 数据本身不含纹理，只有顶点色

如第 0 节实测：全级别 glb `textures: 0`，颜色在 `COLOR_0` 顶点色里。即使光照修复，得到的也是"按顶点着色的彩色白模"，**不可能渲染出照片级贴图**。若需求是"有贴图的实景建筑"，要么实现 NLSC 私有 `EXT_texture_bound` 扩展（需其纹理服务配套），要么更换数据源（如带嵌入 jpg/KTX2 纹理的标准 3D Tiles）。

### 解决方案

**S2.1 加光源（P0，几行代码）** —— 在 JNI 创建 `Application` 之后、realize 之前：

```cpp
// vectorra_jni.cpp — app 创建后
auto headlight = vsg::createHeadlight();          // 跟随相机的平行光
app->scene->addChild(headlight);

auto ambient = vsg::AmbientLight::create();
ambient->color = { 0.25f, 0.25f, 0.25f };          // 比 rocky_simple 的 0.03 高，城市白模需要更亮的底光
app->scene->addChild(ambient);
```

注意：不建议直接启用 `SkyNode`，它会顺带打开地形光照（`Application.cpp:224-225`），改变现有底图视觉，属于额外回归面。

**S2.2 验证顶点色链路（P0 验证项）** —— 加灯后若建筑呈灰白而非彩色，说明 vsgXchange/assimp 没把 `COLOR_0` 写进顶点 color 数组（assimp 的 glTF2 导入器支持 `mColors[0]`，vsgXchange 一般会透传，但需在设备上确认）。若确实丢失：在加载后做一次后处理 visitor，把 `COLOR_0` accessor 写入 vsg 的 colors array；或给 vsgXchange 打补丁。

**S2.3 数据侧决策（P2）**：
- 短期：接受"顶点色白模"作为 NLSC 数据的渲染上限，并在产品文档中说明；
- 长期（二选一）：
  1. 实现 `EXT_texture_bound`（需要拿到 NLSC 的扩展规范与纹理 URL 模板，按 glb 中的扩展字段二次请求纹理并替换材质）；
  2. 用带标准嵌入纹理的 tileset（自建或第三方）验证/交付贴图能力；届时注意 KTX2/Draco 解码能力（vcpkg 已带 draco；ktx 特性未开）。

---

## 2. 问题一：「加载/显示效率极差、视野内瓦片加载不出来、拖动卡顿甚至闪退」

这是多个叠加的根因。按影响从大到小排列，每条均给出代码出处与对应症状。

### 根因 1.1：REPLACE 细分死锁 —— `allChildrenReady()` 要求"视锥外的子瓦片"也就绪，但视锥外子瓦片永远不会被请求

`Tiles3DNode.cpp:280-292`：

```cpp
bool Tile3DNode::allChildrenReady() const {
    for (const auto& child : _childGroup->children) {
        auto* ct = child->cast<Tile3DNode>();
        if (ct && ct->hasContent() && !ct->isContentReady())
            return false;          // ← 遍历的是【全部】子瓦片
    }
    ...
}
```

而两处预取循环（`Tiles3DNode.cpp:375-380`、`404-417`）都用 `intersectsFrustum()` 过滤，**只请求视锥内的子瓦片**。于是：

- 任何一个父瓦片只要有一个子瓦片持续在视锥外（屏幕边缘的父瓦片几乎必然如此），`allChildrenReady()` 永远返回 false；
- `traverse()`（`Tiles3DNode.cpp:356`）便永远走不进"下钻子级"分支，**永远停留在父级粗模**；
- NLSC 数据深达 14 层，意味着屏幕边缘大片区域永远停在很粗的 LOD，视觉上就是"视野内的瓦片加载不出来"（截图中只有零星黑色小块 = 根/浅层粗模）。
- 即便在屏幕中心，REPLACE 也要求"逐层全量加载完一层才能下钻一层"，初次出图需要串行走完 10+ 层"请求→下载→编译"，首屏延迟被人为放大数倍。

**对应症状**：加载不出来（主要原因）、首屏极慢。

### 根因 1.2：加载完成后不请求新帧 —— on-demand 渲染下，下载完的瓦片"永远"不上屏（rocky 层缺陷；当前 Android app 未实际触发，见更正）

- rocky 默认按需渲染：`Application.h:109 renderContinuously = false`；只有 `vsgcontext->requestFrame()` 之后才渲染 2 帧（`Application.cpp:511-524`）。
- 瓦片内容的"领取与编译入口"`resolveContent()` **只在 record 遍历里被调用**（`Tiles3DNode.cpp:343`），而 record 只在渲染帧发生。
- 后台 worker 完成 glb 下载后（`Tiles3DNode.cpp:180-202`）**没有任何 `requestFrame()` 调用**。

按需模式下的时序：用户拖动 → 触发请求 → 手指停下 → 2 帧后停止渲染 → 下载陆续完成但无人触发新帧 → `resolveContent()` 不再执行 → 瓦片不再出现，直到用户再次触摸屏幕。

> **更正（2026-06-10 复核）**：`vectorra_jni.cpp:1624` 实际设置了 `app->renderContinuously = true`，Android 端目前是连续渲染，所以该缺陷在当前 app 中**没有实际触发**。它仍是 Tiles3D 图层级的正确性缺口（任何 on-demand 宿主都会踩中），修复保留；同时这也意味着未来若想靠切回 on-demand 渲染省电，必须依赖本修复。

**对应症状**：（按需渲染宿主）加载不出来 / 停止操作后画面不再更新。

### 根因 1.3：逐瓦片同步编译阻塞帧循环 —— 拖动卡顿 / ANR 的主因

`Tiles3DNode.cpp:236-246`：每个瓦片下载完成后，往 `onNextUpdate` 队列里塞一个 lambda，其中调用 `ctx->compile(node)`。

- `VSGContextImpl::compile`（`VSGContext.cpp:970-1075`）走 `viewer->compileManager->compile()`，**带 fence 阻塞**，源码注释明确警告："this can block… Be sure to group as many compiles together as possible"。
- `VSGContextImpl::update()`（`VSGContext.cpp:1162+`）用 swap-and-run 一次性执行**全部**排队 lambda——也就是说，一次 update 阶段会对本帧到达的所有瓦片**逐个、串行、阻塞**地做 Vulkan 编译（descriptor 创建 + 纹理/缓冲上传 + fence 等待）。
- 移动 GPU 上单个 glb 编译约 5–20 ms；拖动时几十上百个瓦片同时到达 → update 阶段一次卡几百 ms 到数秒 → **帧循环线程被卡死，拖不动**；卡过 5s 即 ANR/闪退观感。

**对应症状**：拖动非常卡顿、卡到拖不动。

### 根因 1.4：请求风暴 —— 无优先级、无有效取消、无并发/在途预算

`Tiles3DNode.cpp:168-209 requestContent()`：

- 线程池 16 线程（`get_pool("rocky::Tiles3DNode", 16)`），每个视锥内瓦片在出现的瞬间立即入队，**没有按 SSE/距离排序的优先级**（weejobs 本身支持 `jobs::context.priority`，`weejobs.h:277`，但这里没用）。
- 取消只在任务开始前检查一次（`Tiles3DNode.cpp:182-183 if (c.canceled())`），一旦开始同步 HTTPS 下载 + assimp 解析就会**跑到底**，即使瓦片早已滑出屏幕。
- 拖动横跨 26 万瓦片的城市数据时：每一帧都把新视野各层级瓦片全部入队 → 队列无限增长 → 16 个线程持续满载下载/解析 → CPU、内存、带宽全部被打满，与 1.3 的编译风暴叠加。

**对应症状**：卡顿、发热、加载排队极长（视觉上=加载不出来）。

### 根因 1.5：LRU 容量远小于工作集 + 第二轮"强制驱逐"会驱逐当前可见瓦片 → 驱逐/重载抖动

- 示例传入 `maximumLoadedTiles = 128`（`MainActivity.kt:437`；层默认 256，`Tiles3DLayer.h:40`）。城市视角下 REPLACE 细分的可见工作集轻松超过几百上千瓦片。
- `expireTiles` 第二轮（`Tiles3DNode.cpp:92-106`）在超限时**无视存活时间、无视是否本帧可见**，从 LRU 头部强拆。可见瓦片每帧都会被 touch，但只要总量 > 128，上一帧 touch 较早的可见瓦片就会被拆掉 → 下一帧又立刻重新请求 → **下载→编译→驱逐→再下载** 的死循环，网络与编译风暴永不收敛。
- 另外 `_maxTileAge = 5s`（`Tiles3DNode.h:75`）对第一轮驱逐也偏激进。

**对应症状**：持续卡顿、流量异常、瓦片闪烁/时有时无。

### 根因 1.6：GPU 资源即时释放（不走延迟 GC）→ 闪退

`Tiles3DNode.cpp:253-264 unloadContent()` 直接 `_content = nullptr`，且它是在 **record 遍历过程中**（`Tiles3DNode::traverse → expireTiles`）被调用的。

- 引用清零会立即销毁 Vulkan buffer/descriptor/image，而 GPU 可能仍在执行上一帧引用这些资源的 command buffer → 未定义行为/设备丢失/段错误闪退。
- rocky 自身所有卸载路径都使用 `vsgcontext->dispose()` 延迟 8 帧 GC（`VSGContext.cpp _gc.resize(8)`；参见 `TerrainNode.cpp:41`、`NodePager.cpp:69`、`ECSNode.cpp:67` 等 12 处），唯独 Tiles3D 没有。

**对应症状**：拖动中随机闪退（与 1.5 的高频驱逐叠加后概率大增）。

### 根因 1.7：49.7 MB 单体 tileset.json 的解析与常驻内存 → 移动端 OOM 闪退 + 首屏慢

- `Tiles3DTileset::fromJSON`（`Tiles3D.cpp:184-214`）用 nlohmann::json 把 49.7 MB 文本整体建 DOM，解析峰值约为文件体积的 3–6 倍（**150–300 MB 量级，估算**）。
- 解析产物 26.2 万个 `Tiles3DTile`：结构体本身约 450 B（box/region/sphere 三个 optional + optional dmat4 + 两个 std::string + children vector），加上每瓦片预先拼好的**绝对 URL**（`resolved`，约 70 字符堆分配）与 shared_ptr 控制块，常驻约 **150 MB 量级（估算）**。
- `Tile3DNode` 构造时再**按值拷贝**整个 `Tiles3DTile`（`Tiles3DNode.h:122 Tiles3DTile _tile`），节点物化后又是一份。
- 叠加底图、地形、Vulkan 资源，低/中端 Android 设备触发 LMK/OOM 闪退完全合理；同时下载+解析 49.7 MB JSON 也让"点击按钮后很久没反应"。

**对应症状**：闪退（OOM 型）、首次加载极慢。

### 根因 1.8：加载失败永不重试 → 永久空洞

`resolveContent()` 失败分支（`Tiles3DNode.cpp:216-221`）只清空 `_loadFuture`，**`_contentRequested` 保持 true**，而 `requestContent()` 开头就被它挡住（`Tiles3DNode.cpp:170`）。请求风暴期间大量超时/断连的瓦片从此变成永久空洞；而且该瓦片 `unloadContent()` 返回 false（无内容可卸），连 LRU 驱逐重置这条路也走不通。

**对应症状**：视野内瓦片永远加载不出来（确定性复现：弱网/风暴期）。

### 次要问题（一并记录）

| # | 问题 | 位置 |
|---|---|---|
| a | 注释声称"NLSC 全是 region 包围体"，实测全是 sphere。当前数据 sphere 恰好是 ECEF 所以剔除结果正确，但凡数据带 `transform`（spec 规定 sphere/box 在瓦片局部坐标系），剔除与 SSE 距离全错 | `Tiles3DNode.cpp:294-304`、`computeScreenSpaceError` |
| b | REPLACE 模式下父瓦片下钻渲染子级时不 touch 自己的 LRU → 作为回退层的父内容 5 秒后被驱逐，子级一旦被驱逐就露出空洞 | `Tiles3DNode.cpp:356-381` |
| c | 预取的子瓦片编译完成后若父级不再被遍历（用户已拖走），既不在 LRU 中也无人引用其卸载 → GPU 内容孤儿化 | `Tiles3DNode.cpp:404-417` |
| d | 每次 dispatch 都打 `__android_log_print`，风暴期日志本身就是开销 | `Tiles3DNode.cpp:208` |
| e | `ContentCache` 仅内存 1024 条，无磁盘缓存；驱逐重载在缓存淘汰后回源网络 | `VSGContext.cpp:886` |

---

## 3. 解决方案

### 阶段 P0 —— 止血（小改动，预计 1–2 天，优先全部落地）

1. **修细分判定**（根因 1.1）：`allChildrenReady()` 改为只要求**视锥内**的子瓦片就绪，视锥外子瓦片视为"就绪"（它们不会被绘制）：

```cpp
bool Tile3DNode::allChildrenReady(const vsg::RecordTraversal& rv) const {
    if (!_childGroup) return false;
    for (const auto& child : _childGroup->children) {
        auto* ct = child->cast<Tile3DNode>();
        if (ct && ct->hasContent() && !ct->isContentReady()
              && ct->intersectsFrustum(rv))   // ← 新增：只看视锥内
            return false;
    }
    return true;
}
```
   注意配套：下钻后若相机转动让"未加载的原视锥外子瓦片"入镜，其自身 traverse 会请求内容；期间该子瓦片区域留空，可接受（与 Cesium 行为一致），后续在 P2 引入"父级回退渲染"消除。

2. **下载完成触发渲染帧**（根因 1.2）：`requestContent()` 的 load lambda 捕获 `VSGContext`，在成功 `return node` 前调用 `ctx->requestFrame()`（原子自增，线程安全）。这样"下载完成 → 渲染一帧 → resolveContent → 排队编译 → 再渲一帧"链路自动闭环。

3. **延迟释放 GPU 资源**（根因 1.6）：`unloadContent()` 改为：

```cpp
if (_content) _tilesetNode->vsgContext()->dispose(_content);  // 8 帧延迟 GC
_content = nullptr;
```

4. **失败重试**（根因 1.8）：失败分支记录 `_lastFailFrame` 并重置 `_contentRequested = false`，配合简单退避（如 120 帧后才允许重试，连续失败 3 次后翻倍），既消除永久空洞又不放大风暴。

5. **驱逐策略修正**（根因 1.5）：
   - 第二轮强制驱逐跳过 `_lastCulledFrame >= currentFrame - 1` 的瓦片（绝不拆当前可见集）；
   - `maximumLoadedTiles` 默认 256 → **1024**，示例 128 → 512（NLSC 单瓦片 glb 平均仅 ~15 KB，1024 个瓦片显存压力可控）；后续 P1 换成字节预算。
   - REPLACE 下钻分支中也 touch 父瓦片（次要问题 b）。

6. **加光源**（问题二，见 S2.1）。

### 阶段 P1 —— 吞吐与流畅性（预计 3–5 天）

7. **编译限额 + 批量化**（根因 1.3）：`resolveContent()` 不再直接往 `onNextUpdate` 塞"每瓦片一个 compile"，改为把就绪节点放入 Tiles3DNode 级别的 `_readyQueue`；每次 update 只取**预算内**（如 ≤4 个或 ≤8 ms）的节点，包进一个临时 `vsg::Group` 做**一次** `ctx->compile(group)`，剩余的 `requestFrame()` 留到下一帧。效果：update 阶段耗时有上界，拖动不再被编译卡死。
   - 进阶（可选）：把 compile 移到专用后台线程（`CompileManager` 支持多线程上下文），update 阶段只做 `vsg::updateViewer` 合并。

8. **请求优先级 + 真正可取消**（根因 1.4）：
   - `jobs::context.priority = [w = vsg::observer_ptr<Tile3DNode>(self)]{ ... }` 返回该瓦片当前 SSE（或负距离），让 weejobs 优先出队屏幕中央/误差大的瓦片；滑出视野的瓦片优先级自然衰减。
   - load lambda 在**网络读取之后、assimp 解析之前**再查一次 `c.canceled()`；瓦片滑出视野/被驱逐时显式丢弃 future（已是 abandon 语义），至少省掉解析与编译。
   - 线程池 16 → 网络 4–6 线程即可（移动端并发连接数与带宽有限），另设 2 线程解析池；并增加 tileset 级在途上限（如 32），超限的请求留待下一帧 traverse 自然重试。

9. **traverse/record 解耦**（架构卫生，为 P2 铺路）：把"选择 + 请求 + 领取"逻辑从 `RecordTraversal` 移到 update 阶段（`vsgcontext->onUpdate` 或专用 Operation），record 阶段只读取选择结果绘制。消除 record 期间修改可变状态的隐患，并为多视图正确性打基础。

10. **glb 磁盘缓存**（次要问题 e）：为 `.glb` 增加持久缓存（按 URL 哈希落盘 + LRU 字节上限），冷启动与回看场景不再回源。

### 阶段 P2 —— 架构与内存（预计 1–2 周）

11. **数据结构瘦身**（根因 1.7）：
    - `Tile3DNode` 持有 `std::shared_ptr<const Tiles3DTile>`（或裸指针指向树内节点），**删除按值拷贝**；
    - `Tiles3DContent` 只存相对 URI，绝对 URL 在 `requestContent` 时即时拼接；
    - `Tiles3DBoundingVolume` 三个 optional 数组改为 variant/紧凑结构；
    - 解析采用 nlohmann SAX（`json::sax_parse`）直接建 `Tiles3DTile` 树，避免 49.7 MB DOM 峰值；
    - 目标：26 万瓦片树常驻 < 40 MB，解析峰值 < 100 MB。
12. **正式的瓦片选择算法**：对齐 cesium-native 的 selection（每帧生成选择集，带加载/卸载滞回、`skipLevelOfDetail` 跳层加载、父级回退渲染），替代现在"父瓦片自治递归"的模式。可直接参考 `vectorra-references` 中的 cesium-native / osgEarth `ThreeDTilesetNode` 实现。
13. **规范兼容性**：按 3D Tiles spec 把 `transform` 链应用到 sphere/box 包围体后再做剔除与 SSE（修复次要问题 a），并补充对 `tileset.json` 嵌套（external tileset）与 implicit tiling 的支持评估。
14. **字节预算驱逐**：`maximumLoadedTiles` 个数上限改为"估算 GPU 字节 + 主存字节"双预算。

### 验收与回归清单

- **功能**：模拟器 + 真机各跑一次 `3D Tiles` / `3D Zoom` smoke：
  - 静置 10s 后视野内瓦片全部出图（验证 1.1/1.2）；
  - 连续 `adb shell input swipe` 10 次拖动期间帧耗时（`Application::stats`）p95 < 33 ms（验证 1.3/1.4/1.5）；
  - 拖动期间 logcat 无 `AndroidRuntime`/`libc` fatal、无 Vulkan validation error（验证 1.6）；
  - `dumpsys meminfo` PSS 峰值相对当前版本下降且稳定不增长（验证 1.7/孤儿内容 c）；
  - 断网→恢复后瓦片能补载（验证 1.8）。
- **视觉**：加灯后建筑呈灰彩色顶点色（非纯黑）；截图对比存档。
- **日志**：保留每层 open / 错误日志，去掉每请求 dispatch 日志（次要问题 d）。

## 4. 已实施的修复（2026-06-10）

P0 全部 + P1 中的编译批量限额 / 请求优先级 / 取消检查 / 在途预算已落地：

| 修复 | 对应根因 | 改动位置 |
|---|---|---|
| `allChildrenReady(rv)` 只要求**视锥内**子瓦片就绪 | 1.1 | `Tiles3DNode.cpp`、`Tiles3DNode.h` |
| worker 完成后经 `_loadsCompleted` 计数 + `onUpdate` 订阅转换为 `requestFrame()`（不在 worker 里碰裸 `VSGContext`，订阅随节点析构自动退订） | 1.2 | `Tiles3DNode.cpp` 构造函数 / `requestContent` |
| 批量限额编译：就绪节点进 `_readyQueue`，每个 update pass 最多编译 4 个、合并为**一次** `compile()`，积压时自动顺延到后续 update 并持续 `requestFrame()` | 1.3 | `Tiles3DNode::enqueueCompile/processCompileQueue` |
| `jobs::context.priority` = 瓦片最新 SSE（`_screenSpaceError` 原子量，traverse/预取时更新），高误差瓦片先加载；下载完成后、解析前增查 `canceled()`；线程池 16 → 6；`maxConcurrentLoads=32` 在途预算，超限请求留待下帧重试 | 1.4 | `Tiles3DNode::requestContent` |
| 强制驱逐跳过 `_lastCulledFrame >= frame-1` 的瓦片（绝不拆可见集）；`maximumLoadedTiles` 默认 256→1024，示例 128→512 | 1.5 | `Tiles3DNode::expireTiles`、`Tiles3DLayer.h`、`MainActivity.kt` |
| `unloadContent()` 改走 `vsgcontext->dispose()` 延迟 GC | 1.6 | `Tiles3DNode::unloadContent` |
| 失败退避重试：失败后 `_contentRequested=false` + 2s 起步指数退避（封顶 30s） | 1.8 | `Tiles3DNode::resolveContent` |
| REPLACE 下钻分支同样 touch 父瓦片 LRU（保留回退层） | 次要 b | `Tiles3DNode::traverse` |
| 删除每请求 dispatch 日志 | 次要 d | `Tiles3DNode::requestContent` |
| 修正"NLSC 全是 region"的过时注释，标注 transform+sphere/box 的已知限制 | 次要 a | `Tiles3DNode::intersectsFrustum` |
| 场景加 `vsg::createHeadlight()` + 0.25 环境光 | 2.1 | `vectorra_jni.cpp startRendererLocked` |

未实施（仍按计划排期）：P1 的 traverse/record 解耦、glb 磁盘缓存；P2 的数据结构瘦身（49.7MB JSON / 26 万瓦片常驻内存，根因 1.7）、cesium-native 式选择算法、transform 链规范兼容、字节预算驱逐。

### 根因 ↔ 症状对照表

| 症状 | 主要根因 | 次要根因 |
|---|---|---|
| 视野内瓦片加载不出来 | 1.1 细分死锁、1.2 不触发渲染帧 | 1.8 失败不重试、1.5 驱逐抖动 |
| 拖动严重卡顿/拖不动 | 1.3 帧循环阻塞编译 | 1.4 请求风暴、1.5 驱逐抖动 |
| 闪退 | 1.6 GPU 资源即时释放、1.7 OOM | 1.3 ANR 被杀 |
| 没有贴图（黑色建筑） | 2.1 无光源 | 2.2 数据本身无纹理（决定上限） |
