# Vectorra Project Guide

## 项目定位

Vectorra 当前包含两个主要目录：

- `vectorra-maps/`：正在开发的地图 SDK，是本仓库的主要开发目标。目标是在功能上对标 Mapbox Maps，并扩展支持 3D Tiles 加载与渲染。
- `vectorra-references/`：参考用开源项目与规范集合，只读。可以阅读、对比、提炼实现思路，但不要在该目录内新增、删除或修改文件。

## 目录边界

所有产品代码、文档、测试和构建调整都应优先发生在 `vectorra-maps/` 内。

`vectorra-references/` 内包含 Mapbox/MapLibre、Cesium Native、3D Tiles、glTF、osmdroid、Tangram ES、rocky upstream、Vulkan/ANGLE 等参考资料。使用这些内容时，将它们视为外部参考源，不要直接改动，也不要把大段代码机械复制进产品目录。

`vectorra-maps/third_party/rocky/` 是 SDK 当前接入的 native 渲染依赖。除非任务明确要求修改 vendored rocky，否则优先在 `vectorra-maps/vectorra-maps/src/main/cpp/` 的 JNI/桥接层或 Kotlin API 层完成适配。

## 当前工程结构

`vectorra-maps/` 是 Android Gradle 多模块工程：

- `:vectorra-maps`：核心 SDK 模块，namespace 为 `com.vectorra.maps`。
- `:vectorra-maps-turf`：GeoJSON 与 Turf 风格几何工具模块，namespace 为 `com.vectorra.maps.turf`。
- `:vectorra-sample`：示例 Android 应用，用于手动验证 SDK 行为。

核心 SDK 目前的主要入口：

- `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/RockyMapView.kt`：Android `SurfaceView` 容器、手势处理、生命周期、截图。
- `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/RockyMap.kt`：SDK 面向使用者的 Kotlin API。
- `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/RockyMapEngine.kt`：Kotlin API 到 native 渲染器的协调层，包含相机、图层、标注、查询、网络代理、定位等逻辑。
- `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/internal/RockyNative.kt`：JNI 方法声明。
- `vectorra-maps/vectorra-maps/src/main/cpp/rocky_jni.cpp`：Android/Vulkan/rocky native 桥接实现。
- `vectorra-maps/vectorra-maps/src/main/cpp/CMakeLists.txt`：native 构建入口，接入 `third_party/rocky`、Vulkan、Android NDK 与 vcpkg 依赖。

## 开发目标

Vectorra Maps 的长期目标是提供现代地图 SDK 能力：

- Mapbox 风格的 `MapView`、相机、手势、生命周期、截图和事件回调。
- 栅格、矢量、DEM、地形、标注、查询、离线/缓存、网络拦截与样式控制。
- 面向 Android 的稳定 Kotlin API，内部可通过 JNI 调用 native renderer。
- 能加载和渲染 3D Tiles，包括 tileset、glTF/GLB 内容、层级选择、LOD、包围体、材质/纹理与地理定位。

新增能力时优先保持 API 小而稳定：先定义清晰的 Kotlin 使用入口，再把实现下沉到 engine/JNI/native 层。公共 API 命名应尽量贴近地图 SDK 使用习惯，但避免无意义地复制参考项目的内部结构。

## 构建与验证

常用命令在 `vectorra-maps/` 目录执行：

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat assembleDebug
```

工程当前使用：

- Android Gradle Plugin `8.6.0`
- Kotlin `2.1.0`
- compileSdk `34`
- minSdk `26`
- NDK `28.2.13676358`
- native ABI：`arm64-v8a`、`x86_64`

涉及 native 渲染、Vulkan、CMake 或 vcpkg 的改动，应至少运行相关模块构建；只改 Kotlin 纯逻辑时，优先运行对应 unit tests。示例应用用于验证 surface 生命周期、地图加载、基础图层、DEM 和手势。

## 开发约定

- 不修改 `vectorra-references/`。
- 保持 `vectorra-maps/` 的模块边界：SDK API 在 `:vectorra-maps`，几何工具在 `:vectorra-maps-turf`，演示代码在 `:vectorra-sample`。
- Kotlin 公共 API 应避免暴露 native 细节；JNI 方法保持 `internal` 封装。
- 相机、坐标、屏幕像素、Web Mercator、瓦片 URL 模板等逻辑应有单元测试覆盖。
- 对网络、缓存、离线、瓦片请求和坐标转换的改动，要优先补充或更新测试。
- native 层改动要注意 Android surface 生命周期、线程安全、Vulkan 设备兼容性和资源释放。
- 3D Tiles 相关实现应以规范为准，可参考 `vectorra-references/3d-tiles/`、`vectorra-references/cesium-native/` 和 `vectorra-references/glTF/`，但实现落在 `vectorra-maps/`。

## 给后续 Agent 的提示

开始任务前先确认工作目录。仓库根目录不是 Gradle 工程根；Gradle 命令需要在 `vectorra-maps/` 下执行。

如果需要理解参考实现，可以读取 `vectorra-references/`，但所有可交付修改应写入 `vectorra-maps/` 或根目录文档。若任务看起来需要修改参考项目，先重新判断是否应在产品工程中实现等价适配。
