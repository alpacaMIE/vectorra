#include <jni.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <rocky/Profile.h>
#include <rocky/GeoPoint.h>
#include <rocky/TMSImageLayer.h>
#include <rocky/TMSElevationLayer.h>
#include <rocky/URI.h>
#include <rocky/Units.h>
#include <rocky/Viewpoint.h>
#include <rocky/Color.h>
#include <rocky/Image.h>
#include <rocky/Feature.h>
#include <rocky/ecs/Label.h>
#include <rocky/ecs/Line.h>
#include <rocky/ecs/Mesh.h>
#include <rocky/ecs/Model.h>
#include <rocky/ecs/Transform.h>
#include <rocky/ecs/Visibility.h>
#include <rocky/vsg/Application.h>
#include <rocky/vsg/ecs/FeatureBuilder.h>
#include <rocky/vsg/ecs/ModelSystem.h>
#include <rocky/vsg/MapManipulator.h>
#include <rocky/vsg/terrain/SurfaceNode.h>
#include <rocky/vsg/terrain/TerrainNode.h>
#include <rocky/vsg/Tiles3DLayer.h>

#include <glm/gtc/matrix_transform.hpp>

#include <vsg/core/Exception.h>
#include <vsg/lighting/AmbientLight.h>
#include <vsg/lighting/Light.h>
#include <vsg/utils/LineSegmentIntersector.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
#include <iomanip>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <cmath>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace
{
    constexpr const char* TAG = "vectorra_jni";
    constexpr int MAX_ANDROID_RENDER_EXTENT = 1280;
    constexpr double PI = 3.14159265358979323846;
    constexpr double EARTH_CIRCUMFERENCE_METERS = 40075016.686;
    constexpr double WEB_MERCATOR_TILE_SIZE = 256.0;
    constexpr double VECTORRA_CAMERA_FOVY_DEGREES = 30.0;
    constexpr double MIN_CAMERA_LATITUDE = -85.0;
    constexpr double MAX_CAMERA_LATITUDE = 85.0;
    constexpr double MIN_CAMERA_ZOOM = 0.0;
    constexpr double MAX_CAMERA_ZOOM = 22.0;
    constexpr double MIN_CAMERA_PITCH = 0.0;
    constexpr double MAX_CAMERA_PITCH = 80.0;
    constexpr double MIN_CAMERA_RANGE_METERS = 100.0;
    constexpr double MAX_CAMERA_RANGE_METERS = 30000000.0;
    constexpr double CAMERA_STATE_EPSILON = 1e-6;
    constexpr double FLING_DECAY_PER_SECOND = 4.0;
    constexpr double FLING_STOP_VELOCITY_PIXELS_PER_SECOND = 6.0;
    constexpr int COORDINATE_PROJECTION_INPUT_STRIDE = 3;
    constexpr int COORDINATE_PROJECTION_OUTPUT_STRIDE = 3;
    constexpr int SCREEN_TO_COORDINATE_OUTPUT_STRIDE = 3;
    constexpr int MVT_MAX_COVER_ZOOM = 30;
    constexpr int MVT_DECODED_TILE_CACHE_SIZE = 256;
    constexpr int MVT_PREFETCH_ZOOM_DELTA = 4;
    constexpr int MVT_PREFETCH_PADDING_TILES = 1;
    constexpr int MVT_COVER_SAMPLE_GRID = 5;
    constexpr double MVT_MERCATOR_MAX_LATITUDE = 85.0511287798066;

    struct NativeCameraState
    {
        double longitude = 104.293174;
        double latitude = 32.2857965;
        double zoom = 2.0;
        double pitch = 0.0;
        double bearing = 0.0;
        double targetHeightMeters = 0.0;
    };

    struct ProjectionSnapshot
    {
        bool ready = false;
        vsg::dmat4 viewMatrix;
        vsg::dmat4 projectionMatrix;
        double viewportX = 0.0;
        double viewportY = 0.0;
        double viewportWidth = 1.0;
        double viewportHeight = 1.0;
        int viewWidth = 1;
        int viewHeight = 1;
        int renderWidth = 1;
        int renderHeight = 1;
        rocky::SRS renderingSRS;
    };

    struct ScreenToCoordinateResult
    {
        bool hit = false;
        double longitude = 0.0;
        double latitude = 0.0;
    };

    struct ScreenToCoordinateQuery
    {
        std::mutex mutex;
        std::condition_variable cv;
        bool done = false;
        ScreenToCoordinateResult result;
    };

    struct PendingGestureMotion
    {
        bool hasPan = false;
        double panDeltaX = 0.0;
        double panDeltaY = 0.0;
        int panViewWidth = 1;
        int panViewHeight = 1;

        bool hasZoom = false;
        bool zoomAtFocus = false;
        double scale = 1.0;
        double focusX = 0.0;
        double focusY = 0.0;
        int zoomViewWidth = 1;
        int zoomViewHeight = 1;

        bool hasRotate = false;
        double rotateDegrees = 0.0;

        bool hasPitch = false;
        double pitchDegrees = 0.0;

        bool empty() const
        {
            return !hasPan && !hasZoom && !hasRotate && !hasPitch;
        }
    };

    const char* vkResultName(VkResult result)
    {
        switch (result)
        {
            case VK_SUCCESS: return "VK_SUCCESS";
            case VK_NOT_READY: return "VK_NOT_READY";
            case VK_TIMEOUT: return "VK_TIMEOUT";
            case VK_EVENT_SET: return "VK_EVENT_SET";
            case VK_EVENT_RESET: return "VK_EVENT_RESET";
            case VK_INCOMPLETE: return "VK_INCOMPLETE";
            case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
            case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
            case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
            case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
            case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case VK_ERROR_SURFACE_LOST_KHR: return "VK_ERROR_SURFACE_LOST_KHR";
            case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR: return "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
            default: return "VK_RESULT_UNKNOWN";
        }
    }

    const char* deviceTypeName(VkPhysicalDeviceType type)
    {
        switch (type)
        {
            case VK_PHYSICAL_DEVICE_TYPE_OTHER: return "OTHER";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "INTEGRATED_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "DISCRETE_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "VIRTUAL_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU: return "CPU";
            default: return "UNKNOWN";
        }
    }

    bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name)
    {
        return std::any_of(extensions.begin(), extensions.end(), [name](const VkExtensionProperties& extension)
        {
            return std::string(extension.extensionName) == name;
        });
    }

    bool isTemplateTileUrl(const std::string& url)
    {
        return
            (url.find("{z}") != std::string::npos || url.find("${z}") != std::string::npos) &&
            (url.find("{x}") != std::string::npos || url.find("${x}") != std::string::npos) &&
            (url.find("{y}") != std::string::npos || url.find("${y}") != std::string::npos ||
                url.find("{-y}") != std::string::npos || url.find("${-y}") != std::string::npos);
    }

    std::string vkResultMessage(VkResult result)
    {
        return std::string(vkResultName(result)) + "(" + std::to_string(result) + ")";
    }

    std::optional<std::string> checkVulkanAvailability()
    {
        uint32_t instanceExtensionCount = 0;
        VkResult result = vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount, nullptr);
        if (result != VK_SUCCESS)
        {
            return "Vulkan instance extension enumeration failed: " + vkResultMessage(result);
        }

        std::vector<VkExtensionProperties> instanceExtensions(instanceExtensionCount);
        if (instanceExtensionCount > 0)
        {
            result = vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount, instanceExtensions.data());
            if (result != VK_SUCCESS)
            {
                return "Vulkan instance extension enumeration failed: " + vkResultMessage(result);
            }
        }

        if (!hasExtension(instanceExtensions, VK_KHR_SURFACE_EXTENSION_NAME) ||
            !hasExtension(instanceExtensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME))
        {
            return "Vulkan unavailable: required Android surface instance extensions are missing";
        }

        const char* requestedExtensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };

        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "rocky_probe";
        appInfo.applicationVersion = 1;
        appInfo.pEngineName = "rocky_probe";
        appInfo.engineVersion = 1;
        appInfo.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &appInfo;
        instanceInfo.enabledExtensionCount = 2;
        instanceInfo.ppEnabledExtensionNames = requestedExtensions;

        VkInstance instance = VK_NULL_HANDLE;
        result = vkCreateInstance(&instanceInfo, nullptr, &instance);
        if (result != VK_SUCCESS || instance == VK_NULL_HANDLE)
        {
            return "Vulkan instance creation failed: " + vkResultMessage(result);
        }

        uint32_t physicalDeviceCount = 0;
        result = vkEnumeratePhysicalDevices(instance, &physicalDeviceCount, nullptr);
        vkDestroyInstance(instance, nullptr);
        if (result != VK_SUCCESS)
        {
            return "Vulkan physical device enumeration failed: " + vkResultMessage(result);
        }
        if (physicalDeviceCount == 0)
        {
            return "Vulkan unavailable: no physical device found";
        }

        return std::nullopt;
    }

    void logVulkanSurfaceProbe(ANativeWindow* window)
    {
        if (!window)
        {
            __android_log_print(ANDROID_LOG_WARN, TAG, "vulkan probe skipped: nativeWindow=null");
            return;
        }

        uint32_t instanceExtensionCount = 0;
        VkResult result = vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount, nullptr);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "vulkan probe instance extensions count result=%s(%d) count=%u",
            vkResultName(result),
            result,
            instanceExtensionCount);

        std::vector<VkExtensionProperties> instanceExtensions(instanceExtensionCount);
        if (result == VK_SUCCESS && instanceExtensionCount > 0)
        {
            result = vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount, instanceExtensions.data());
        }

        const bool hasSurfaceExtension = hasExtension(instanceExtensions, VK_KHR_SURFACE_EXTENSION_NAME);
        const bool hasAndroidSurfaceExtension = hasExtension(instanceExtensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "vulkan probe required instance extensions surface=%d androidSurface=%d",
            hasSurfaceExtension ? 1 : 0,
            hasAndroidSurfaceExtension ? 1 : 0);

        const char* requestedExtensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };

        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "rocky_probe";
        appInfo.applicationVersion = 1;
        appInfo.pEngineName = "rocky_probe";
        appInfo.engineVersion = 1;
        appInfo.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &appInfo;
        instanceInfo.enabledExtensionCount = 2;
        instanceInfo.ppEnabledExtensionNames = requestedExtensions;

        VkInstance instance = VK_NULL_HANDLE;
        result = vkCreateInstance(&instanceInfo, nullptr, &instance);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "vulkan probe vkCreateInstance result=%s(%d)",
            vkResultName(result),
            result);
        if (result != VK_SUCCESS || instance == VK_NULL_HANDLE)
        {
            return;
        }

        auto createAndroidSurface = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
            vkGetInstanceProcAddr(instance, "vkCreateAndroidSurfaceKHR"));
        auto destroySurface = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
            vkGetInstanceProcAddr(instance, "vkDestroySurfaceKHR"));
        auto getSurfaceSupport = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceSupportKHR"));
        auto getSurfaceFormats = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceFormatsKHR"));
        auto getPresentModes = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfacePresentModesKHR"));

        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "vulkan probe proc createAndroidSurface=%d getSurfaceSupport=%d",
            createAndroidSurface ? 1 : 0,
            getSurfaceSupport ? 1 : 0);

        VkSurfaceKHR surface = VK_NULL_HANDLE;
        if (createAndroidSurface)
        {
            VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
            surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
            surfaceInfo.window = window;
            result = createAndroidSurface(instance, &surfaceInfo, nullptr, &surface);
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "vulkan probe vkCreateAndroidSurfaceKHR result=%s(%d) surface=%p",
                vkResultName(result),
                result,
                reinterpret_cast<void*>(surface));
        }

        uint32_t physicalDeviceCount = 0;
        result = vkEnumeratePhysicalDevices(instance, &physicalDeviceCount, nullptr);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "vulkan probe physical devices result=%s(%d) count=%u",
            vkResultName(result),
            result,
            physicalDeviceCount);

        std::vector<VkPhysicalDevice> devices(physicalDeviceCount);
        if (result == VK_SUCCESS && physicalDeviceCount > 0)
        {
            result = vkEnumeratePhysicalDevices(instance, &physicalDeviceCount, devices.data());
        }

        for (uint32_t deviceIndex = 0; deviceIndex < physicalDeviceCount; ++deviceIndex)
        {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(devices[deviceIndex], &properties);

            uint32_t deviceExtensionCount = 0;
            result = vkEnumerateDeviceExtensionProperties(devices[deviceIndex], nullptr, &deviceExtensionCount, nullptr);
            std::vector<VkExtensionProperties> deviceExtensions(deviceExtensionCount);
            if (result == VK_SUCCESS && deviceExtensionCount > 0)
            {
                result = vkEnumerateDeviceExtensionProperties(devices[deviceIndex], nullptr, &deviceExtensionCount, deviceExtensions.data());
            }

            uint32_t surfaceFormatCount = 0;
            if (surface != VK_NULL_HANDLE && getSurfaceFormats)
            {
                VkResult formatsResult = getSurfaceFormats(devices[deviceIndex], surface, &surfaceFormatCount, nullptr);
                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "vulkan probe device[%u] surfaceFormats result=%s(%d) count=%u",
                    deviceIndex,
                    vkResultName(formatsResult),
                    formatsResult,
                    surfaceFormatCount);
            }

            uint32_t presentModeCount = 0;
            if (surface != VK_NULL_HANDLE && getPresentModes)
            {
                VkResult presentModesResult = getPresentModes(devices[deviceIndex], surface, &presentModeCount, nullptr);
                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "vulkan probe device[%u] presentModes result=%s(%d) count=%u",
                    deviceIndex,
                    vkResultName(presentModesResult),
                    presentModesResult,
                    presentModeCount);
            }

            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "vulkan probe device[%u] name=%s type=%s api=%u.%u.%u driver=%u swapchain=%d memoryBudget=%d",
                deviceIndex,
                properties.deviceName,
                deviceTypeName(properties.deviceType),
                VK_VERSION_MAJOR(properties.apiVersion),
                VK_VERSION_MINOR(properties.apiVersion),
                VK_VERSION_PATCH(properties.apiVersion),
                properties.driverVersion,
                hasExtension(deviceExtensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ? 1 : 0,
                hasExtension(deviceExtensions, VK_EXT_MEMORY_BUDGET_EXTENSION_NAME) ? 1 : 0);

            uint32_t queueFamilyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(devices[deviceIndex], &queueFamilyCount, nullptr);
            std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
            if (queueFamilyCount > 0)
            {
                vkGetPhysicalDeviceQueueFamilyProperties(devices[deviceIndex], &queueFamilyCount, queueFamilies.data());
            }

            for (uint32_t queueIndex = 0; queueIndex < queueFamilyCount; ++queueIndex)
            {
                VkBool32 presentSupported = VK_FALSE;
                VkResult supportResult = VK_SUCCESS;
                if (surface != VK_NULL_HANDLE && getSurfaceSupport)
                {
                    supportResult = getSurfaceSupport(devices[deviceIndex], queueIndex, surface, &presentSupported);
                }

                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "vulkan probe device[%u] queue[%u] flags=0x%x count=%u present=%d supportResult=%s(%d)",
                    deviceIndex,
                    queueIndex,
                    queueFamilies[queueIndex].queueFlags,
                    queueFamilies[queueIndex].queueCount,
                    presentSupported == VK_TRUE ? 1 : 0,
                    vkResultName(supportResult),
                    supportResult);
            }
        }

        if (surface != VK_NULL_HANDLE && destroySurface)
        {
            destroySurface(instance, surface, nullptr);
        }
        vkDestroyInstance(instance, nullptr);
    }

    struct RasterLayerConfig
    {
        std::string templateUrl;
        int minZoom = 0;
        int maxZoom = 18;
        bool visible = true;
        float opacity = 1.0f;
        float saturation = 0.0f;
        float contrast = 0.0f;
        int tileSize = 256;
        std::string scheme = "XYZ";
        std::string matrixSet;
        bool disableNativeDiskCache = false;
        std::vector<std::pair<std::string, std::string>> headers;
    };

    struct ElevationLayerConfig
    {
        std::string templateUrl;
        int minZoom = 0;
        int maxZoom = 14;
        bool visible = true;
        bool disableNativeDiskCache = false;
        std::vector<std::pair<std::string, std::string>> headers;
    };

    struct ModelLayerConfig
    {
        std::string uri;
        double longitude = 0.0;
        double latitude = 0.0;
        double heightMeters = 0.0;
        double scale = 1.0;
        double yawDegrees = 0.0;
        bool visible = true;
    };

    struct Tiles3DRendererContentConfig
    {
        std::string renderUri;
        std::string transformKind;
        std::array<double, 16> transformMatrix{};
        double ecefX = 0.0;
        double ecefY = 0.0;
        double ecefZ = 0.0;
        bool visible = true;
    };

    glm::dmat4 matrixFromColumnMajorArray(const std::array<double, 16>& values)
    {
        glm::dmat4 matrix(1.0);
        for (int column = 0; column < 4; ++column)
        {
            for (int row = 0; row < 4; ++row)
            {
                matrix[column][row] = values[static_cast<size_t>(column * 4 + row)];
            }
        }
        return matrix;
    }

    struct MvtRenderStyleConfig
    {
        std::string kind;
        bool visible = true;
        int color = -1;
        float opacity = 1.0f;
        float widthPixels = 1.0f;
        float radiusPixels = 4.0f;
        float textSizeSp = 12.0f;
    };

    struct MvtRenderTileConfig
    {
        std::string sourceId;
        std::string layerId;
        std::string sourceLayer;
        int tileZ = 0;
        int tileX = 0;
        int tileY = 0;
        MvtRenderStyleConfig style;
        bool rendered = true;
        std::vector<std::string> featureIds;
        std::vector<std::string> sourceLayers;
        std::vector<int> geometryTypes;
        std::vector<int> coordinateOffsets;
        std::vector<double> coordinates;
        std::vector<int> ringOffsets;
        std::vector<int> ringEnds;
        std::uint64_t revision = 0;
    };

    struct MvtQuerySnapshot
    {
        std::vector<std::string> featureIds;
        std::vector<std::string> sourceLayers;
        std::vector<int> geometryTypes;
        std::vector<int> coordinateOffsets = {0};
        std::vector<double> coordinates;
        std::vector<int> ringOffsets = {0};
        std::vector<int> ringEnds;
        std::vector<int> propertyOffsets = {0};
        std::vector<std::string> propertyKeys;
        std::vector<std::string> propertyValues;
    };

    struct MvtSubmitResult
    {
        std::string nativeTileHandle;
        std::string errorMessage;
        MvtQuerySnapshot query;
    };

    struct MvtTileId
    {
        int z = 0;
        int x = 0;
        int y = 0;

        bool operator<(const MvtTileId& rhs) const
        {
            if (z != rhs.z) return z < rhs.z;
            if (x != rhs.x) return x < rhs.x;
            return y < rhs.y;
        }

        bool operator==(const MvtTileId& rhs) const
        {
            return z == rhs.z && x == rhs.x && y == rhs.y;
        }
    };

    struct MvtLayerConfig
    {
        std::string sourceId;
        std::string layerId;
        std::string sourceLayer;
        std::string templateUrl;
        int sourceMinZoom = 0;
        int sourceMaxZoom = 14;
        int layerMinZoom = 0;
        int layerMaxZoom = 24;
        int tileSize = 512;
        std::string scheme = "XYZ";
        MvtRenderStyleConfig style;
        std::vector<std::pair<std::string, std::string>> headers;
    };

    struct MvtLoadedTile
    {
        MvtRenderTileConfig config;
        MvtQuerySnapshot query;
        std::uint64_t lastTouched = 0;
        bool active = false;
    };

    struct MvtTileLoadJobResult
    {
        std::string layerId;
        MvtTileId tileId;
        std::uint64_t generation = 0;
        bool success = false;
        bool idealAtRequest = false;
        std::string errorMessage;
        MvtRenderTileConfig config;
        MvtQuerySnapshot query;
    };

    struct MvtPendingTileLoad
    {
        std::uint64_t generation = 0;
        bool ideal = false;
        rocky::Future<MvtTileLoadJobResult> future;
    };

    struct MvtLayerRuntime
    {
        MvtLayerConfig config;
        std::uint64_t generation = 0;
        std::uint64_t touchCounter = 0;
        std::map<MvtTileId, MvtLoadedTile> loadedTiles;
        std::map<MvtTileId, MvtPendingTileLoad> pendingTiles;
        std::set<MvtTileId> activeTiles;
        std::set<MvtTileId> idealTiles;
        std::set<MvtTileId> prefetchTiles;
    };

    struct MvtByteView
    {
        const std::uint8_t* data = nullptr;
        std::size_t size = 0;
    };

    struct MvtPbfField
    {
        int number = 0;
        int wireType = 0;
    };

    class MvtPbfReader
    {
    public:
        MvtPbfReader(const std::uint8_t* data, std::size_t size)
            : data_(data), size_(size)
        {
        }

        explicit MvtPbfReader(const MvtByteView& view)
            : MvtPbfReader(view.data, view.size)
        {
        }

        bool isAtEnd() const
        {
            return position_ >= size_;
        }

        std::optional<MvtPbfField> readFieldOrNull()
        {
            if (isAtEnd())
            {
                return std::nullopt;
            }
            const auto tag = readVarint();
            if (tag == 0)
            {
                return std::nullopt;
            }
            return MvtPbfField{
                static_cast<int>(tag >> 3u),
                static_cast<int>(tag & 0x7u)};
        }

        std::uint64_t readVarint()
        {
            int shift = 0;
            std::uint64_t result = 0;
            while (shift < 64)
            {
                const auto byte = readByte();
                result |= (static_cast<std::uint64_t>(byte & 0x7fu) << shift);
                if ((byte & 0x80u) == 0)
                {
                    return result;
                }
                shift += 7;
            }
            throw std::runtime_error("Malformed protobuf varint.");
        }

        std::int64_t readSignedVarint()
        {
            const auto value = readVarint();
            return static_cast<std::int64_t>((value >> 1u) ^ (~(value & 1u) + 1u));
        }

        std::vector<int> readPackedVarintsAsInt()
        {
            MvtPbfReader packed(readBytesView());
            std::vector<int> values;
            while (!packed.isAtEnd())
            {
                const auto value = packed.readVarint();
                if (value > static_cast<std::uint64_t>(std::numeric_limits<int>::max()))
                {
                    throw std::runtime_error("MVT packed varint is outside int range.");
                }
                values.push_back(static_cast<int>(value));
            }
            return values;
        }

        std::string readString()
        {
            const auto view = readBytesView();
            return std::string(reinterpret_cast<const char*>(view.data), view.size);
        }

        MvtByteView readBytesView()
        {
            const auto size = readVarint();
            if (size > static_cast<std::uint64_t>(size_ - position_))
            {
                throw std::runtime_error("Invalid protobuf length-delimited field size.");
            }
            MvtByteView view{data_ + position_, static_cast<std::size_t>(size)};
            position_ += static_cast<std::size_t>(size);
            return view;
        }

        float readFixed32Float()
        {
            const auto bits = readFixed32();
            float result = 0.0f;
            std::memcpy(&result, &bits, sizeof(result));
            return result;
        }

        double readFixed64Double()
        {
            const auto bits = readFixed64();
            double result = 0.0;
            std::memcpy(&result, &bits, sizeof(result));
            return result;
        }

        void skip(int wireType)
        {
            switch (wireType)
            {
                case 0:
                    readVarint();
                    return;
                case 1:
                    readFixed64();
                    return;
                case 2:
                    readBytesView();
                    return;
                case 5:
                    readFixed32();
                    return;
                default:
                    throw std::runtime_error("Unsupported protobuf wire type.");
            }
        }

    private:
        std::uint8_t readByte()
        {
            if (position_ >= size_)
            {
                throw std::runtime_error("Unexpected end of protobuf data.");
            }
            return data_[position_++];
        }

        std::uint32_t readFixed32()
        {
            if (size_ - position_ < 4)
            {
                throw std::runtime_error("Invalid protobuf fixed32 field size.");
            }
            const auto* p = data_ + position_;
            position_ += 4;
            return static_cast<std::uint32_t>(p[0]) |
                (static_cast<std::uint32_t>(p[1]) << 8u) |
                (static_cast<std::uint32_t>(p[2]) << 16u) |
                (static_cast<std::uint32_t>(p[3]) << 24u);
        }

        std::uint64_t readFixed64()
        {
            if (size_ - position_ < 8)
            {
                throw std::runtime_error("Invalid protobuf fixed64 field size.");
            }
            std::uint64_t result = 0;
            for (int i = 0; i < 8; ++i)
            {
                result |= static_cast<std::uint64_t>(data_[position_ + static_cast<std::size_t>(i)]) << (8u * i);
            }
            position_ += 8;
            return result;
        }

        const std::uint8_t* data_ = nullptr;
        std::size_t size_ = 0;
        std::size_t position_ = 0;
    };

    struct MvtLocalPoint
    {
        int x = 0;
        int y = 0;
    };

    struct MvtDecodedFeature
    {
        std::optional<std::uint64_t> id;
        int geometryType = 0;
        std::vector<std::vector<MvtLocalPoint>> parts;
        std::vector<std::pair<std::string, std::string>> properties;
    };

    struct MvtDecodedLayer
    {
        std::string name;
        int version = 1;
        int extent = 4096;
        std::vector<std::string> keys;
        std::vector<std::string> values;
        std::vector<MvtByteView> featureViews;
    };

    int zigZagDecodeInt(int value)
    {
        return (static_cast<unsigned int>(value) >> 1u) ^ -(value & 1);
    }

    std::string numberToString(float value)
    {
        std::ostringstream out;
        out << std::setprecision(std::numeric_limits<float>::digits10 + 1) << value;
        return out.str();
    }

    std::string numberToString(double value)
    {
        std::ostringstream out;
        out << std::setprecision(std::numeric_limits<double>::digits10 + 1) << value;
        return out.str();
    }

    std::string decodeMvtValue(const MvtByteView& view)
    {
        MvtPbfReader reader(view);
        std::string value;
        while (!reader.isAtEnd())
        {
            const auto field = reader.readFieldOrNull();
            if (!field)
            {
                break;
            }
            switch (field->number)
            {
                case 1:
                    value = reader.readString();
                    break;
                case 2:
                    value = numberToString(reader.readFixed32Float());
                    break;
                case 3:
                    value = numberToString(reader.readFixed64Double());
                    break;
                case 4:
                case 5:
                    value = std::to_string(reader.readVarint());
                    break;
                case 6:
                    value = std::to_string(reader.readSignedVarint());
                    break;
                case 7:
                    value = reader.readVarint() != 0 ? "true" : "false";
                    break;
                default:
                    reader.skip(field->wireType);
                    break;
            }
        }
        return value;
    }

    std::vector<std::vector<MvtLocalPoint>> decodeMvtGeometry(int geometryType, const std::vector<int>& commands)
    {
        if (geometryType <= 0 || commands.empty())
        {
            return {};
        }

        std::vector<std::vector<MvtLocalPoint>> parts;
        std::vector<MvtLocalPoint>* current = nullptr;
        int cursorX = 0;
        int cursorY = 0;
        std::size_t index = 0;
        while (index < commands.size())
        {
            const int commandInteger = commands[index++];
            const int command = commandInteger & 0x7;
            const int count = commandInteger >> 3;
            switch (command)
            {
                case 1:
                    for (int i = 0; i < count; ++i)
                    {
                        if (index + 1 >= commands.size())
                        {
                            throw std::runtime_error("MVT MoveTo command is truncated.");
                        }
                        cursorX += zigZagDecodeInt(commands[index++]);
                        cursorY += zigZagDecodeInt(commands[index++]);
                        parts.push_back({MvtLocalPoint{cursorX, cursorY}});
                        current = &parts.back();
                    }
                    break;
                case 2:
                    for (int i = 0; i < count; ++i)
                    {
                        if (index + 1 >= commands.size())
                        {
                            throw std::runtime_error("MVT LineTo command is truncated.");
                        }
                        cursorX += zigZagDecodeInt(commands[index++]);
                        cursorY += zigZagDecodeInt(commands[index++]);
                        if (current)
                        {
                            current->push_back(MvtLocalPoint{cursorX, cursorY});
                        }
                    }
                    break;
                case 7:
                    for (int i = 0; i < count; ++i)
                    {
                        if (current && !current->empty())
                        {
                            const auto& first = current->front();
                            const auto& last = current->back();
                            if (first.x != last.x || first.y != last.y)
                            {
                                current->push_back(first);
                            }
                        }
                    }
                    break;
                default:
                    throw std::runtime_error("Unsupported MVT geometry command.");
            }
        }
        return parts;
    }

    void setMvtProperty(
        std::vector<std::pair<std::string, std::string>>& properties,
        const std::string& key,
        const std::string& value)
    {
        for (auto& property : properties)
        {
            if (property.first == key)
            {
                property.second = value;
                return;
            }
        }
        properties.emplace_back(key, value);
    }

    MvtDecodedFeature decodeMvtFeature(const MvtByteView& view, const MvtDecodedLayer& layer)
    {
        MvtPbfReader reader(view);
        MvtDecodedFeature feature;
        std::vector<int> tagIndexes;
        std::vector<int> geometryCommands;

        while (!reader.isAtEnd())
        {
            const auto field = reader.readFieldOrNull();
            if (!field)
            {
                break;
            }
            switch (field->number)
            {
                case 1:
                    feature.id = reader.readVarint();
                    break;
                case 2:
                    tagIndexes = reader.readPackedVarintsAsInt();
                    break;
                case 3:
                    feature.geometryType = static_cast<int>(reader.readVarint());
                    break;
                case 4:
                    geometryCommands = reader.readPackedVarintsAsInt();
                    break;
                default:
                    reader.skip(field->wireType);
                    break;
            }
        }

        for (std::size_t i = 0; i + 1 < tagIndexes.size(); i += 2)
        {
            const int keyIndex = tagIndexes[i];
            const int valueIndex = tagIndexes[i + 1];
            if (keyIndex >= 0 &&
                valueIndex >= 0 &&
                keyIndex < static_cast<int>(layer.keys.size()) &&
                valueIndex < static_cast<int>(layer.values.size()))
            {
                setMvtProperty(feature.properties, layer.keys[keyIndex], layer.values[valueIndex]);
            }
        }

        feature.parts = decodeMvtGeometry(feature.geometryType, geometryCommands);
        return feature;
    }

    MvtDecodedLayer decodeMvtLayer(const MvtByteView& view)
    {
        MvtPbfReader reader(view);
        MvtDecodedLayer layer;
        while (!reader.isAtEnd())
        {
            const auto field = reader.readFieldOrNull();
            if (!field)
            {
                break;
            }
            switch (field->number)
            {
                case 1:
                    layer.name = reader.readString();
                    break;
                case 2:
                    layer.featureViews.push_back(reader.readBytesView());
                    break;
                case 3:
                    layer.keys.push_back(reader.readString());
                    break;
                case 4:
                    layer.values.push_back(decodeMvtValue(reader.readBytesView()));
                    break;
                case 5:
                    layer.extent = static_cast<int>(reader.readVarint());
                    if (layer.extent <= 0)
                    {
                        layer.extent = 4096;
                    }
                    break;
                case 15:
                    layer.version = static_cast<int>(reader.readVarint());
                    break;
                default:
                    reader.skip(field->wireType);
                    break;
            }
        }
        return layer;
    }

    glm::dvec3 mvtLocalPointToLonLat(const MvtLocalPoint& point, int tileZ, int tileX, int tileY, int extent)
    {
        const double worldTiles = std::pow(2.0, static_cast<double>(tileZ));
        const double worldSize = static_cast<double>(extent) * worldTiles;
        const double globalX = static_cast<double>(tileX) * static_cast<double>(extent) + static_cast<double>(point.x);
        const double globalY = static_cast<double>(tileY) * static_cast<double>(extent) + static_cast<double>(point.y);
        const double longitude = globalX / worldSize * 360.0 - 180.0;
        const double mercatorY = PI * (1.0 - 2.0 * globalY / worldSize);
        const double latitude = std::atan(std::sinh(mercatorY)) * 180.0 / PI;
        return {longitude, latitude, 0.0};
    }

    std::uint32_t javaDoubleHash(double value)
    {
        std::uint64_t bits = 0;
        std::memcpy(&bits, &value, sizeof(bits));
        return static_cast<std::uint32_t>(bits ^ (bits >> 32u));
    }

    std::uint32_t coordinateHash(const glm::dvec3& coordinate)
    {
        return 31u * javaDoubleHash(coordinate.x) + javaDoubleHash(coordinate.y);
    }

    std::uint32_t listHash(const std::vector<std::uint32_t>& values)
    {
        std::uint32_t result = 1u;
        for (auto value : values)
        {
            result = 31u * result + value;
        }
        return result;
    }

    std::uint32_t coordinateListHash(const std::vector<glm::dvec3>& coordinates)
    {
        std::vector<std::uint32_t> hashes;
        hashes.reserve(coordinates.size());
        for (const auto& coordinate : coordinates)
        {
            hashes.push_back(coordinateHash(coordinate));
        }
        return listHash(hashes);
    }

    std::uint32_t ringListHash(const std::vector<std::vector<glm::dvec3>>& rings)
    {
        std::vector<std::uint32_t> hashes;
        hashes.reserve(rings.size());
        for (const auto& ring : rings)
        {
            hashes.push_back(coordinateListHash(ring));
        }
        return listHash(hashes);
    }

    std::string signedHashString(std::uint32_t hash)
    {
        const auto signedHash = hash <= 0x7fffffffu
            ? static_cast<std::int64_t>(hash)
            : static_cast<std::int64_t>(hash) - 0x100000000LL;
        return std::to_string(signedHash);
    }

    std::string decodedMvtFeatureId(
        const std::optional<std::uint64_t>& rawId,
        const std::string& layerName,
        std::uint32_t geometryHash,
        int geometryIndex,
        int geometryCount)
    {
        const std::string baseId = rawId
            ? std::to_string(*rawId)
            : layerName + ":" + signedHashString(geometryHash);
        return geometryCount == 1
            ? baseId
            : baseId + ":" + std::to_string(geometryIndex);
    }

    int expectedMvtRenderGeometryType(const std::string& styleKind)
    {
        if (styleKind == "LINE")
        {
            return 2;
        }
        if (styleKind == "FILL")
        {
            return 3;
        }
        if (styleKind == "CIRCLE" || styleKind == "SYMBOL")
        {
            return 1;
        }
        return 0;
    }

    int renderGeometryOrdinalForMvtType(int geometryType)
    {
        switch (geometryType)
        {
            case 1:
                return 0;
            case 2:
                return 1;
            case 3:
                return 2;
            default:
                return -1;
        }
    }

    void appendMvtRenderFeature(
        MvtRenderTileConfig& config,
        const std::string& featureId,
        const std::string& sourceLayer,
        int geometryOrdinal,
        const std::vector<glm::dvec3>& coordinates,
        const std::vector<int>& ringEnds)
    {
        if (coordinates.empty())
        {
            return;
        }
        config.featureIds.push_back(featureId);
        config.sourceLayers.push_back(sourceLayer);
        config.geometryTypes.push_back(geometryOrdinal);
        for (const auto& coordinate : coordinates)
        {
            config.coordinates.push_back(coordinate.x);
            config.coordinates.push_back(coordinate.y);
        }
        config.coordinateOffsets.push_back(static_cast<int>(config.coordinates.size()));
        config.ringEnds.insert(config.ringEnds.end(), ringEnds.begin(), ringEnds.end());
        config.ringOffsets.push_back(static_cast<int>(config.ringEnds.size()));
    }

    void appendMvtQueryFeature(
        MvtQuerySnapshot& query,
        const std::string& featureId,
        const std::string& sourceLayer,
        int geometryOrdinal,
        const std::vector<glm::dvec3>& coordinates,
        const std::vector<int>& ringEnds,
        const std::vector<std::pair<std::string, std::string>>& properties)
    {
        if (coordinates.empty())
        {
            return;
        }
        query.featureIds.push_back(featureId);
        query.sourceLayers.push_back(sourceLayer);
        query.geometryTypes.push_back(geometryOrdinal);
        for (const auto& coordinate : coordinates)
        {
            query.coordinates.push_back(coordinate.x);
            query.coordinates.push_back(coordinate.y);
        }
        query.coordinateOffsets.push_back(static_cast<int>(query.coordinates.size()));
        query.ringEnds.insert(query.ringEnds.end(), ringEnds.begin(), ringEnds.end());
        query.ringOffsets.push_back(static_cast<int>(query.ringEnds.size()));
        for (const auto& property : properties)
        {
            query.propertyKeys.push_back(property.first);
            query.propertyValues.push_back(property.second);
        }
        query.propertyOffsets.push_back(static_cast<int>(query.propertyKeys.size()));
    }

    void appendMvtFeatureOutputs(
        const MvtDecodedFeature& feature,
        const std::string& sourceLayer,
        const MvtRenderStyleConfig& style,
        int tileZ,
        int tileX,
        int tileY,
        int extent,
        MvtRenderTileConfig& renderConfig,
        MvtQuerySnapshot& query)
    {
        const int renderExpectedType = expectedMvtRenderGeometryType(style.kind);
        const int geometryOrdinal = renderGeometryOrdinalForMvtType(feature.geometryType);
        if (geometryOrdinal < 0)
        {
            return;
        }

        if (feature.geometryType == 1)
        {
            std::vector<glm::dvec3> points;
            for (const auto& part : feature.parts)
            {
                for (const auto& point : part)
                {
                    points.push_back(mvtLocalPointToLonLat(point, tileZ, tileX, tileY, extent));
                }
            }
            const int geometryCount = static_cast<int>(points.size());
            for (int index = 0; index < geometryCount; ++index)
            {
                const std::vector<glm::dvec3> coordinates = {points[static_cast<std::size_t>(index)]};
                const auto featureId = decodedMvtFeatureId(
                    feature.id,
                    sourceLayer,
                    coordinateHash(coordinates.front()),
                    index,
                    geometryCount);
                appendMvtQueryFeature(query, featureId, sourceLayer, geometryOrdinal, coordinates, {}, feature.properties);
                if (renderExpectedType == feature.geometryType)
                {
                    appendMvtRenderFeature(renderConfig, featureId, sourceLayer, geometryOrdinal, coordinates, {});
                }
            }
        }
        else if (feature.geometryType == 2)
        {
            std::vector<std::vector<glm::dvec3>> lines;
            for (const auto& part : feature.parts)
            {
                if (part.size() < 2)
                {
                    continue;
                }
                std::vector<glm::dvec3> line;
                line.reserve(part.size());
                for (const auto& point : part)
                {
                    line.push_back(mvtLocalPointToLonLat(point, tileZ, tileX, tileY, extent));
                }
                lines.push_back(std::move(line));
            }
            const int geometryCount = static_cast<int>(lines.size());
            for (int index = 0; index < geometryCount; ++index)
            {
                const auto& coordinates = lines[static_cast<std::size_t>(index)];
                const auto featureId = decodedMvtFeatureId(
                    feature.id,
                    sourceLayer,
                    coordinateListHash(coordinates),
                    index,
                    geometryCount);
                appendMvtQueryFeature(query, featureId, sourceLayer, geometryOrdinal, coordinates, {}, feature.properties);
                if (renderExpectedType == feature.geometryType)
                {
                    appendMvtRenderFeature(renderConfig, featureId, sourceLayer, geometryOrdinal, coordinates, {});
                }
            }
        }
        else if (feature.geometryType == 3)
        {
            std::vector<std::vector<glm::dvec3>> rings;
            for (const auto& part : feature.parts)
            {
                if (part.size() < 4)
                {
                    continue;
                }
                std::vector<glm::dvec3> ring;
                ring.reserve(part.size());
                for (const auto& point : part)
                {
                    ring.push_back(mvtLocalPointToLonLat(point, tileZ, tileX, tileY, extent));
                }
                rings.push_back(std::move(ring));
            }
            if (rings.empty())
            {
                return;
            }
            std::vector<glm::dvec3> coordinates;
            std::vector<int> ringEnds;
            for (const auto& ring : rings)
            {
                coordinates.insert(coordinates.end(), ring.begin(), ring.end());
                ringEnds.push_back(static_cast<int>(coordinates.size()));
            }
            const auto featureId = decodedMvtFeatureId(
                feature.id,
                sourceLayer,
                ringListHash(rings),
                0,
                1);
            appendMvtQueryFeature(query, featureId, sourceLayer, geometryOrdinal, coordinates, ringEnds, feature.properties);
            if (renderExpectedType == feature.geometryType)
            {
                appendMvtRenderFeature(renderConfig, featureId, sourceLayer, geometryOrdinal, coordinates, ringEnds);
            }
        }
    }

    MvtQuerySnapshot decodeMvtTileBytesIntoConfig(const std::vector<std::uint8_t>& bytes, MvtRenderTileConfig& config)
    {
        config.featureIds.clear();
        config.sourceLayers.clear();
        config.geometryTypes.clear();
        config.coordinateOffsets = {0};
        config.coordinates.clear();
        config.ringOffsets = {0};
        config.ringEnds.clear();

        MvtQuerySnapshot query;
        MvtPbfReader reader(bytes.data(), bytes.size());
        while (!reader.isAtEnd())
        {
            const auto field = reader.readFieldOrNull();
            if (!field)
            {
                break;
            }
            if (field->number == 3 && field->wireType == 2)
            {
                auto layer = decodeMvtLayer(reader.readBytesView());
                if (layer.name != config.sourceLayer)
                {
                    continue;
                }
                for (const auto& featureView : layer.featureViews)
                {
                    auto feature = decodeMvtFeature(featureView, layer);
                    appendMvtFeatureOutputs(
                        feature,
                        layer.name,
                        config.style,
                        config.tileZ,
                        config.tileX,
                        config.tileY,
                        layer.extent,
                        config,
                        query);
                }
            }
            else
            {
                reader.skip(field->wireType);
            }
        }
        return query;
    }

    std::string mvtTileHandle(const std::string& layerId, const MvtTileId& tileId)
    {
        return layerId + ":" +
            std::to_string(tileId.z) + "/" +
            std::to_string(tileId.x) + "/" +
            std::to_string(tileId.y);
    }

    void appendMvtQuerySnapshotForLayer(
        MvtQuerySnapshot& target,
        const MvtQuerySnapshot& source,
        const std::string& renderLayerId)
    {
        for (std::size_t index = 0; index < source.featureIds.size(); ++index)
        {
            target.featureIds.push_back(source.featureIds[index]);
            target.sourceLayers.push_back(source.sourceLayers[index]);
            target.geometryTypes.push_back(source.geometryTypes[index]);

            const int coordinateStart = source.coordinateOffsets[index];
            const int coordinateEnd = source.coordinateOffsets[index + 1];
            target.coordinates.insert(
                target.coordinates.end(),
                source.coordinates.begin() + coordinateStart,
                source.coordinates.begin() + coordinateEnd);
            target.coordinateOffsets.push_back(static_cast<int>(target.coordinates.size()));

            const int ringStart = source.ringOffsets[index];
            const int ringEnd = source.ringOffsets[index + 1];
            for (int ringIndex = ringStart; ringIndex < ringEnd; ++ringIndex)
            {
                target.ringEnds.push_back(source.ringEnds[ringIndex]);
            }
            target.ringOffsets.push_back(static_cast<int>(target.ringEnds.size()));

            const int propertyStart = source.propertyOffsets[index];
            const int propertyEnd = source.propertyOffsets[index + 1];
            for (int propertyIndex = propertyStart; propertyIndex < propertyEnd; ++propertyIndex)
            {
                target.propertyKeys.push_back(source.propertyKeys[propertyIndex]);
                target.propertyValues.push_back(source.propertyValues[propertyIndex]);
            }
            target.propertyKeys.push_back("__vectorra-render-layer");
            target.propertyValues.push_back(renderLayerId);
            target.propertyOffsets.push_back(static_cast<int>(target.propertyKeys.size()));
        }
    }

    void replaceAll(std::string& value, const std::string& token, const std::string& replacement)
    {
        std::size_t cursor = 0;
        while ((cursor = value.find(token, cursor)) != std::string::npos)
        {
            value.replace(cursor, token.size(), replacement);
            cursor += replacement.size();
        }
    }

    int mvtTmsRequestY(int z, int y)
    {
        if (z < 0 || z > MVT_MAX_COVER_ZOOM)
        {
            return y;
        }
        const int maxY = (1 << z) - 1;
        return std::max(0, maxY - y);
    }

    std::string mvtTileUrl(const MvtLayerConfig& config, const MvtTileId& tileId)
    {
        const int requestY = config.scheme == "TMS"
            ? mvtTmsRequestY(tileId.z, tileId.y)
            : tileId.y;
        std::string url = config.templateUrl;
        replaceAll(url, "${z}", std::to_string(tileId.z));
        replaceAll(url, "${x}", std::to_string(tileId.x));
        replaceAll(url, "${y}", std::to_string(requestY));
        replaceAll(url, "{z}", std::to_string(tileId.z));
        replaceAll(url, "{x}", std::to_string(tileId.x));
        replaceAll(url, "{y}", std::to_string(requestY));
        return url;
    }

    MvtRenderTileConfig mvtBaseTileConfig(const MvtLayerConfig& layer, const MvtTileId& tileId, bool rendered)
    {
        MvtRenderTileConfig config;
        config.sourceId = layer.sourceId;
        config.layerId = layer.layerId;
        config.sourceLayer = layer.sourceLayer;
        config.tileZ = tileId.z;
        config.tileX = tileId.x;
        config.tileY = tileId.y;
        config.style = layer.style;
        config.rendered = rendered;
        return config;
    }

    MvtTileLoadJobResult loadMvtTileJob(
        MvtLayerConfig layer,
        MvtTileId tileId,
        std::uint64_t generation,
        bool idealAtRequest,
        rocky::IOOptions io,
        rocky::Cancelable& cancelable)
    {
        MvtTileLoadJobResult result;
        result.layerId = layer.layerId;
        result.tileId = tileId;
        result.generation = generation;
        result.idealAtRequest = idealAtRequest;
        result.config = mvtBaseTileConfig(layer, tileId, false);

        if (cancelable.canceled())
        {
            result.errorMessage = "MVT tile load canceled.";
            return result;
        }

        try
        {
            rocky::URI::Context uriContext;
            for (const auto& header : layer.headers)
            {
                uriContext.headers.emplace_back(header.first, header.second);
            }
            const auto url = mvtTileUrl(layer, tileId);
            auto response = rocky::URI(url, uriContext).read(io.with(cancelable));
            if (response.failed())
            {
                result.errorMessage = response.error().string();
                return result;
            }
            if (cancelable.canceled())
            {
                result.errorMessage = "MVT tile load canceled.";
                return result;
            }

            const auto& data = response.value().content.data;
            if (data.empty())
            {
                result.errorMessage = "MVT tile response was empty.";
                return result;
            }
            std::vector<std::uint8_t> bytes(data.begin(), data.end());
            result.query = decodeMvtTileBytesIntoConfig(bytes, result.config);
            if (!std::all_of(result.config.coordinates.begin(), result.config.coordinates.end(), [](double value)
                {
                    return std::isfinite(value);
                }) ||
                !std::all_of(result.query.coordinates.begin(), result.query.coordinates.end(), [](double value)
                {
                    return std::isfinite(value);
                }))
            {
                result.errorMessage = "MVT tile decoded to non-finite coordinates.";
                return result;
            }
            result.success = true;
            return result;
        }
        catch (const std::exception& error)
        {
            result.errorMessage = error.what();
            return result;
        }
    }

    int mvtFloorMod(int value, int modulus)
    {
        const int remainder = value % modulus;
        return remainder < 0 ? remainder + modulus : remainder;
    }

    double mvtWrappedLongitude(double longitude)
    {
        if (!std::isfinite(longitude))
        {
            return 0.0;
        }
        double result = std::fmod(longitude + 180.0, 360.0);
        if (result < 0.0)
        {
            result += 360.0;
        }
        return result - 180.0;
    }

    double mvtMercatorTileX(double longitude, int z)
    {
        const double tileCount = static_cast<double>(1 << z);
        return (mvtWrappedLongitude(longitude) + 180.0) / 360.0 * tileCount;
    }

    double mvtMercatorTileY(double latitude, int z)
    {
        const double tileCount = static_cast<double>(1 << z);
        const double clampedLatitude = std::clamp(
            std::isfinite(latitude) ? latitude : 0.0,
            -MVT_MERCATOR_MAX_LATITUDE,
            MVT_MERCATOR_MAX_LATITUDE);
        const double sinLatitude = std::sin(clampedLatitude * PI / 180.0);
        return (0.5 - std::log((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)) * tileCount;
    }

    bool mvtLayerVisibleAtZoom(const MvtLayerConfig& config, double zoom)
    {
        return config.style.visible &&
            config.layerMinZoom <= config.layerMaxZoom &&
            zoom >= static_cast<double>(std::max(config.sourceMinZoom, config.layerMinZoom)) &&
            zoom <= static_cast<double>(config.layerMaxZoom);
    }

    int mvtIdealTileZoom(const MvtLayerConfig& config, double zoom)
    {
        return std::clamp(
            static_cast<int>(std::floor(std::isfinite(zoom) ? zoom : 0.0)),
            std::clamp(config.sourceMinZoom, 0, MVT_MAX_COVER_ZOOM),
            std::clamp(std::max(config.sourceMinZoom, config.sourceMaxZoom), 0, MVT_MAX_COVER_ZOOM));
    }

    MvtTileId mvtParentTileId(const MvtTileId& tileId)
    {
        return MvtTileId{
            tileId.z - 1,
            tileId.x / 2,
            tileId.y / 2};
    }

    std::array<MvtTileId, 4> mvtChildTileIds(const MvtTileId& tileId)
    {
        const int childZ = tileId.z + 1;
        const int childX = tileId.x * 2;
        const int childY = tileId.y * 2;
        return {
            MvtTileId{childZ, childX, childY},
            MvtTileId{childZ, childX + 1, childY},
            MvtTileId{childZ, childX, childY + 1},
            MvtTileId{childZ, childX + 1, childY + 1}};
    }

    bool mvtTileIsInside(const MvtTileId& tileId, const MvtTileId& ancestor)
    {
        if (tileId.z < ancestor.z)
        {
            return false;
        }
        const int scale = 1 << (tileId.z - ancestor.z);
        return tileId.x / scale == ancestor.x && tileId.y / scale == ancestor.y;
    }

    bool mvtLoadedDescendantCover(
        const MvtTileId& tileId,
        const std::set<MvtTileId>& loadedTileIds,
        int maxZoom,
        std::set<MvtTileId>& output)
    {
        if (loadedTileIds.find(tileId) != loadedTileIds.end())
        {
            output.insert(tileId);
            return true;
        }
        if (tileId.z >= maxZoom)
        {
            return false;
        }
        const bool hasDescendant = std::any_of(loadedTileIds.begin(), loadedTileIds.end(), [&](const MvtTileId& loaded)
        {
            return mvtTileIsInside(loaded, tileId);
        });
        if (!hasDescendant)
        {
            return false;
        }
        std::set<MvtTileId> cover;
        for (const auto& child : mvtChildTileIds(tileId))
        {
            if (!mvtLoadedDescendantCover(child, loadedTileIds, maxZoom, cover))
            {
                return false;
            }
        }
        output.insert(cover.begin(), cover.end());
        return true;
    }

    std::optional<MvtTileId> mvtLoadedParentTile(
        MvtTileId tileId,
        const std::set<MvtTileId>& loadedTileIds,
        int minZoom)
    {
        while (tileId.z > minZoom)
        {
            tileId = mvtParentTileId(tileId);
            if (loadedTileIds.find(tileId) != loadedTileIds.end())
            {
                return tileId;
            }
        }
        return std::nullopt;
    }

    std::set<MvtTileId> mvtRenderTileIds(
        const std::set<MvtTileId>& idealTileIds,
        const std::set<MvtTileId>& loadedTileIds,
        int minZoom,
        int maxZoom)
    {
        std::set<MvtTileId> renderTiles;
        if (idealTileIds.empty() || loadedTileIds.empty())
        {
            return renderTiles;
        }
        int maxLoadedZoom = minZoom;
        for (const auto& loaded : loadedTileIds)
        {
            maxLoadedZoom = std::max(maxLoadedZoom, loaded.z);
        }
        const int childCoverMaxZoom = std::min(maxZoom, maxLoadedZoom);
        for (const auto& ideal : idealTileIds)
        {
            if (loadedTileIds.find(ideal) != loadedTileIds.end())
            {
                renderTiles.insert(ideal);
                continue;
            }
            std::set<MvtTileId> childCover;
            if (mvtLoadedDescendantCover(ideal, loadedTileIds, childCoverMaxZoom, childCover))
            {
                renderTiles.insert(childCover.begin(), childCover.end());
                continue;
            }
            if (auto parent = mvtLoadedParentTile(ideal, loadedTileIds, minZoom))
            {
                renderTiles.insert(*parent);
            }
        }
        return renderTiles;
    }

    struct LabelAnnotationConfig
    {
        std::string id;
        double longitude = 0.0;
        double latitude = 0.0;
        std::string text;
        float textSize = 12.0f;
        int textColor = 0xffffffff;
        int textHaloColor = 0xff000000;
        float textHaloWidth = 2.0f;
        int textOffsetX = 0;
        int textOffsetY = 22;
        bool hasIcon = false;
        int iconColor = 0xffffffff;
        float iconRadius = 7.0f;
        bool allowOverlap = false;
    };

    struct LocationIndicatorConfig
    {
        bool enabled = false;
        double longitude = 0.0;
        double latitude = 0.0;
        double accuracyMeters = 0.0;
        double bearingDegrees = 0.0;
        bool showAccuracyRing = true;
        float accuracyRadiusPixels = 0.0f;
    };

    rocky::Color colorFromAndroidArgb(int argb)
    {
        return rocky::Color(static_cast<std::uint32_t>(argb), rocky::Color::Format::ARGB);
    }

    rocky::Image::Ptr createCircleIcon(int androidArgb, float radiusPx)
    {
        const int radius = std::max(1, static_cast<int>(std::ceil(radiusPx)));
        const int diameter = radius * 2;
        auto image = std::make_shared<rocky::Image>(
            rocky::Image::R8G8B8A8_UNORM,
            static_cast<unsigned>(diameter),
            static_cast<unsigned>(diameter));
        const auto color = colorFromAndroidArgb(androidArgb);
        const float center = static_cast<float>(radius) - 0.5f;
        const float r2 = static_cast<float>(radius * radius);
        for (int y = 0; y < diameter; ++y)
        {
            for (int x = 0; x < diameter; ++x)
            {
                const float dx = static_cast<float>(x) - center;
                const float dy = static_cast<float>(y) - center;
                const float alpha = (dx * dx + dy * dy) <= r2 ? color.a : 0.0f;
                image->write(rocky::Image::Pixel(color.r, color.g, color.b, alpha), x, y);
            }
        }
        return image;
    }

    rocky::Image::Ptr createLocationPuckIcon(float bearingDegrees)
    {
        constexpr int size = 48;
        constexpr float center = (size - 1) * 0.5f;
        constexpr float dotRadius = 10.0f;
        constexpr float strokeRadius = 13.0f;
        constexpr int fillArgb = static_cast<int>(0xff1e88e5u);
        constexpr int strokeArgb = static_cast<int>(0xffffffffu);
        constexpr int arrowArgb = static_cast<int>(0xff0d47a1u);

        auto image = std::make_shared<rocky::Image>(
            rocky::Image::R8G8B8A8_UNORM,
            static_cast<unsigned>(size),
            static_cast<unsigned>(size));
        const auto fill = colorFromAndroidArgb(fillArgb);
        const auto stroke = colorFromAndroidArgb(strokeArgb);
        const auto arrow = colorFromAndroidArgb(arrowArgb);
        const double rotation = (static_cast<double>(bearingDegrees) - 90.0) * PI / 180.0;
        const float ux = static_cast<float>(std::cos(rotation));
        const float uy = static_cast<float>(std::sin(rotation));
        const float vx = -uy;
        const float vy = ux;

        for (int y = 0; y < size; ++y)
        {
            for (int x = 0; x < size; ++x)
            {
                const float dx = static_cast<float>(x) - center;
                const float dy = static_cast<float>(y) - center;
                const float distance2 = dx * dx + dy * dy;
                auto pixel = rocky::Image::Pixel(0.0f, 0.0f, 0.0f, 0.0f);
                if (distance2 <= strokeRadius * strokeRadius)
                {
                    pixel = rocky::Image::Pixel(stroke.r, stroke.g, stroke.b, stroke.a);
                }
                if (distance2 <= dotRadius * dotRadius)
                {
                    pixel = rocky::Image::Pixel(fill.r, fill.g, fill.b, fill.a);
                }

                const float along = dx * ux + dy * uy;
                const float across = std::abs(dx * vx + dy * vy);
                if (along >= 6.0f && along <= 21.0f && across <= (21.0f - along) * 0.42f)
                {
                    pixel = rocky::Image::Pixel(arrow.r, arrow.g, arrow.b, arrow.a);
                }
                image->write(pixel, x, y);
            }
        }
        return image;
    }

    rocky::Image::Ptr createAccuracyRingIcon(float radiusPx)
    {
        const int radius = std::max(4, static_cast<int>(std::ceil(radiusPx)));
        const int diameter = radius * 2 + 4;
        const float center = (diameter - 1) * 0.5f;
        const float outerRadius = static_cast<float>(radius);
        const float innerRadius = std::max(1.0f, outerRadius - 2.0f);
        const auto fill = colorFromAndroidArgb(0x331e88e5);
        const auto stroke = colorFromAndroidArgb(static_cast<int>(0x991e88e5u));

        auto image = std::make_shared<rocky::Image>(
            rocky::Image::R8G8B8A8_UNORM,
            static_cast<unsigned>(diameter),
            static_cast<unsigned>(diameter));
        for (int y = 0; y < diameter; ++y)
        {
            for (int x = 0; x < diameter; ++x)
            {
                const float dx = static_cast<float>(x) - center;
                const float dy = static_cast<float>(y) - center;
                const float distance = std::sqrt(dx * dx + dy * dy);
                auto pixel = rocky::Image::Pixel(0.0f, 0.0f, 0.0f, 0.0f);
                if (distance <= outerRadius)
                {
                    pixel = rocky::Image::Pixel(fill.r, fill.g, fill.b, fill.a);
                }
                if (distance <= outerRadius && distance >= innerRadius)
                {
                    pixel = rocky::Image::Pixel(stroke.r, stroke.g, stroke.b, stroke.a);
                }
                image->write(pixel, x, y);
            }
        }
        return image;
    }

    void applyTerrainExaggerationToSurfaces(vsg::Node* node, float exaggeration)
    {
        if (!node)
        {
            return;
        }

        if (auto* surface = node->cast<rocky::SurfaceNode>())
        {
            surface->setTerrainExaggeration(exaggeration);
        }

        if (auto* group = node->cast<vsg::Group>())
        {
            for (auto& child : group->children)
            {
                applyTerrainExaggerationToSurfaces(child.get(), exaggeration);
            }
        }
    }

    class VectorraNativeEngine
    {
    public:
        ~VectorraNativeEngine()
        {
            std::unique_lock<std::mutex> lock(mutex);
            shutdownRendererLocked(lock);
            clearResourceStatusCallback();
            clearCameraCallback();
        }

        void setResourceStatusCallback(JNIEnv* env, jobject callback)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (statusCallback)
            {
                env->DeleteGlobalRef(statusCallback);
                statusCallback = nullptr;
            }
            statusCallbackMethod = nullptr;
            if (!cameraCallback)
            {
                javaVm = nullptr;
            }

            if (!callback)
            {
                return;
            }

            env->GetJavaVM(&javaVm);
            statusCallback = env->NewGlobalRef(callback);
            jclass callbackClass = env->GetObjectClass(callback);
            statusCallbackMethod = env->GetMethodID(
                callbackClass,
                "onNativeResourceStatus",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            env->DeleteLocalRef(callbackClass);
            if (!statusCallbackMethod)
            {
                env->DeleteGlobalRef(statusCallback);
                statusCallback = nullptr;
                if (!cameraCallback)
                {
                    javaVm = nullptr;
                }
            }
        }

        void setCameraCallback(JNIEnv* env, jobject callback)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (cameraCallback)
            {
                env->DeleteGlobalRef(cameraCallback);
                cameraCallback = nullptr;
            }
            cameraCallbackMethod = nullptr;
            if (!statusCallback)
            {
                javaVm = nullptr;
            }

            if (!callback)
            {
                return;
            }

            env->GetJavaVM(&javaVm);
            cameraCallback = env->NewGlobalRef(callback);
            jclass callbackClass = env->GetObjectClass(callback);
            cameraCallbackMethod = env->GetMethodID(
                callbackClass,
                "onNativeCameraChanged",
                "(DDDDD)V");
            env->DeleteLocalRef(callbackClass);
            if (!cameraCallbackMethod)
            {
                env->DeleteGlobalRef(cameraCallback);
                cameraCallback = nullptr;
                if (!statusCallback)
                {
                    javaVm = nullptr;
                }
            }
            else
            {
                emitCameraStateLocked(currentCameraState());
            }
        }

        std::string setSurface(JNIEnv* env, jobject surface, int width, int height)
        {
            std::unique_lock<std::mutex> lock(mutex);
            shutdownRendererLocked(lock);

            if (surface == nullptr)
            {
                return {};
            }

            nativeWindow = ANativeWindow_fromSurface(env, surface);
            if (!nativeWindow)
            {
                return "Android native window is not available for Vectorra renderer surface";
            }
            surfaceWidth = width > 0 ? width : 1;
            surfaceHeight = height > 0 ? height : 1;
            ANativeWindow_setBuffersGeometry(nativeWindow, surfaceWidth, surfaceHeight, 0);
            __android_log_print(ANDROID_LOG_INFO, TAG, "setSurface %dx%d", surfaceWidth, surfaceHeight);
            return startRendererLocked();
        }

        void setResourcePath(const std::string& path)
        {
            std::lock_guard<std::mutex> lock(mutex);
            resourcePath = path;
            if (!resourcePath.empty())
            {
                setenv("ROCKY_FILE_PATH", resourcePath.c_str(), 1);
                __android_log_print(ANDROID_LOG_INFO, TAG, "ROCKY_FILE_PATH=%s", resourcePath.c_str());
            }
        }

        // Persistent network content cache directory. Must be set before the
        // renderer starts (rocky reads ROCKY_CACHE_PATH at context creation).
        void setCachePath(const std::string& path)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!path.empty())
            {
                setenv("ROCKY_CACHE_PATH", path.c_str(), 1);
                __android_log_print(ANDROID_LOG_INFO, TAG, "ROCKY_CACHE_PATH=%s", path.c_str());
            }
        }

        void detachSurface()
        {
            std::unique_lock<std::mutex> lock(mutex);
            projectionSnapshot.ready = false;
            shutdownRendererLocked(lock);
            __android_log_print(ANDROID_LOG_INFO, TAG, "detachSurface");
        }

        void resize(int width, int height)
        {
            std::lock_guard<std::mutex> lock(mutex);
            surfaceWidth = width > 0 ? width : 1;
            surfaceHeight = height > 0 ? height : 1;
            __android_log_print(ANDROID_LOG_INFO, TAG, "resize %dx%d", surfaceWidth, surfaceHeight);
            // SSE must use the capped Vulkan render extent, not the Android view
            // height — the surface is downscaled to MAX_ANDROID_RENDER_EXTENT.
            for (auto& [id, layer] : tiles3DLayerMap)
                layer->setViewportHeight(static_cast<float>(effectiveRenderHeight()));
            if (app)
            {
                updateProjectionSnapshotLocked(app.get());
            }
        }

        void setCamera(
            double longitude,
            double latitude,
            double zoom,
            double pitch,
            double bearing,
            double targetHeightMeters,
            long durationMillis)
        {
            clearPendingMotion();
            std::lock_guard<std::mutex> lock(mutex);
            setCachedCameraStateLocked(NativeCameraState{
                wrapLongitude(longitude),
                clampLatitude(latitude),
                clampZoom(zoom),
                clampPitch(pitch),
                normalizeBearing(bearing),
                std::isfinite(targetHeightMeters) ? targetHeightMeters : 0.0});
            if (app)
            {
                queueSetCameraLocked(static_cast<float>(std::max<long>(durationMillis, 0L) / 1000.0));
            }
            else
            {
                emitCameraStateLocked(currentCameraState());
            }
        }

        void panByPixels(float deltaX, float deltaY, int viewWidth, int viewHeight)
        {
            if (!std::isfinite(deltaX) || !std::isfinite(deltaY) || viewWidth <= 0 || viewHeight <= 0)
            {
                return;
            }
            enqueuePanGesture(deltaX, deltaY, viewWidth, viewHeight);
        }

        void zoomByScale(float scale)
        {
            if (!std::isfinite(scale) || scale <= 0.0f)
            {
                return;
            }
            enqueueZoomGesture(scale);
        }

        void zoomByScaleAt(float scale, float focusX, float focusY, int viewWidth, int viewHeight)
        {
            if (!std::isfinite(scale) || scale <= 0.0f ||
                !std::isfinite(focusX) || !std::isfinite(focusY) ||
                viewWidth <= 0 || viewHeight <= 0)
            {
                return;
            }
            enqueueZoomAtGesture(scale, focusX, focusY, viewWidth, viewHeight);
        }

        void rotateByDegrees(double deltaDegrees)
        {
            if (!std::isfinite(deltaDegrees) || std::abs(deltaDegrees) < 0.01)
            {
                return;
            }
            enqueueRotateGesture(deltaDegrees);
        }

        void pitchByDegrees(double deltaDegrees)
        {
            if (!std::isfinite(deltaDegrees) || std::abs(deltaDegrees) < 0.01)
            {
                return;
            }
            enqueuePitchGesture(deltaDegrees);
        }

        void flingByVelocity(float velocityX, float velocityY, int viewWidth, int viewHeight)
        {
            if (!std::isfinite(velocityX) || !std::isfinite(velocityY) || viewWidth <= 0 || viewHeight <= 0)
            {
                return;
            }
            if (!running.load())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(motionMutex);
            flingVelocityX = velocityX;
            flingVelocityY = velocityY;
            flingViewWidth = viewWidth;
            flingViewHeight = viewHeight;
            flingActive = true;
            flingLastTick = std::chrono::steady_clock::now();
        }

        void cancelCameraMotion()
        {
            clearPendingMotion();
            std::lock_guard<std::mutex> lock(mutex);
            if (app)
            {
                queueCameraCommandLocked([](rocky::Application* activeApp)
                {
                    auto manipulator = manipulatorFor(activeApp);
                    if (!manipulator)
                    {
                        return false;
                    }
                    manipulator->clearViewpoint();
                    return true;
                });
            }
        }

        void cancelFling()
        {
            clearPendingMotion();
        }

        std::vector<double> projectCoordinates(const std::vector<double>& lonLatHeight)
        {
            ProjectionSnapshot snapshot;
            {
                std::lock_guard<std::mutex> lock(mutex);
                snapshot = projectionSnapshot;
            }

            if (!snapshot.ready || lonLatHeight.size() % COORDINATE_PROJECTION_INPUT_STRIDE != 0)
            {
                return {};
            }

            const std::size_t coordinateCount = lonLatHeight.size() / COORDINATE_PROJECTION_INPUT_STRIDE;
            std::vector<double> output(coordinateCount * COORDINATE_PROJECTION_OUTPUT_STRIDE, 0.0);
            const double viewScaleX = static_cast<double>(std::max(snapshot.viewWidth, 1)) /
                static_cast<double>(std::max(snapshot.renderWidth, 1));
            const double viewScaleY = static_cast<double>(std::max(snapshot.viewHeight, 1)) /
                static_cast<double>(std::max(snapshot.renderHeight, 1));

            for (std::size_t index = 0; index < coordinateCount; ++index)
            {
                const std::size_t inputOffset = index * COORDINATE_PROJECTION_INPUT_STRIDE;
                const double longitude = lonLatHeight[inputOffset];
                const double latitude = lonLatHeight[inputOffset + 1];
                const double height = lonLatHeight[inputOffset + 2];
                if (!std::isfinite(longitude) || !std::isfinite(latitude) || !std::isfinite(height))
                {
                    continue;
                }

                const auto world = rocky::GeoPoint(rocky::SRS::WGS84, longitude, latitude, height)
                    .transform(snapshot.renderingSRS);
                if (!world)
                {
                    continue;
                }

                const auto clip = snapshot.projectionMatrix *
                    (snapshot.viewMatrix * vsg::dvec4(world.x, world.y, world.z, 1.0));
                if (!std::isfinite(clip.x) ||
                    !std::isfinite(clip.y) ||
                    !std::isfinite(clip.w) ||
                    std::abs(clip.w) <= 1e-12)
                {
                    continue;
                }

                const double ndcX = clip.x / clip.w;
                const double ndcY = clip.y / clip.w;
                const double renderX = snapshot.viewportX + (ndcX + 1.0) * 0.5 * snapshot.viewportWidth;
                const double renderY = snapshot.viewportY + (1.0 - ndcY) * 0.5 * snapshot.viewportHeight;
                const std::size_t outputOffset = index * COORDINATE_PROJECTION_OUTPUT_STRIDE;
                output[outputOffset] = renderX * viewScaleX;
                output[outputOffset + 1] = renderY * viewScaleY;
                output[outputOffset + 2] = clip.w > 0.0 ? 1.0 : 0.0;
            }

            return output;
        }

        ScreenToCoordinateResult screenToCoordinate(double x, double y)
        {
            if (!std::isfinite(x) || !std::isfinite(y))
            {
                return {};
            }

            auto query = std::make_shared<ScreenToCoordinateQuery>();
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (!app)
                {
                    return {};
                }
                auto* activeApp = app.get();
                const double renderX = x * static_cast<double>(std::max(effectiveRenderWidth(), 1)) /
                    static_cast<double>(std::max(surfaceWidth, 1));
                const double renderY = y * static_cast<double>(std::max(effectiveRenderHeight(), 1)) /
                    static_cast<double>(std::max(surfaceHeight, 1));
                activeApp->onNextUpdate([this, activeApp, renderX, renderY, query]()
                {
                    ScreenToCoordinateResult result;
                    {
                        std::lock_guard<std::mutex> lock(mutex);
                        if (app.get() == activeApp)
                        {
                            result = screenToCoordinateOnUpdateLocked(
                                activeApp,
                                static_cast<int32_t>(std::lround(renderX)),
                                static_cast<int32_t>(std::lround(renderY)));
                        }
                    }
                    {
                        std::lock_guard<std::mutex> queryLock(query->mutex);
                        query->result = result;
                        query->done = true;
                    }
                    query->cv.notify_one();
                });
                requestFrameLocked();
            }

            std::unique_lock<std::mutex> queryLock(query->mutex);
            if (!query->cv.wait_for(queryLock, std::chrono::milliseconds(500), [&query]()
            {
                return query->done;
            }))
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "screenToCoordinate timed out waiting for renderer update");
                return {};
            }
            return query->result;
        }

        void addRasterLayer(
            const std::string& id,
            const std::string& templateUrl,
            int minZoom,
            int maxZoom,
            bool visible,
            double opacity,
            double saturation,
            double contrast,
            int tileSize,
            const std::string& scheme,
            const std::string& matrixSet,
            bool disableNativeDiskCache,
            std::vector<std::pair<std::string, std::string>> headers)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (rasterLayers.find(id) == rasterLayers.end())
            {
                rasterLayerOrder.emplace_back(id);
            }
            rasterLayers[id] = RasterLayerConfig{
                templateUrl,
                minZoom,
                maxZoom,
                visible,
                static_cast<float>(std::clamp(opacity, 0.0, 1.0)),
                static_cast<float>(std::clamp(saturation, -1.0, 1.0)),
                static_cast<float>(std::clamp(contrast, -1.0, 1.0)),
                std::max(1, tileSize),
                scheme,
                matrixSet,
                disableNativeDiskCache,
                std::move(headers)};
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "addRasterLayer id=%s minZoom=%d maxZoom=%d tileSize=%d scheme=%s matrixSet=%s",
                id.c_str(),
                minZoom,
                maxZoom,
                std::max(1, tileSize),
                scheme.c_str(),
                matrixSet.c_str());
            if (app)
            {
                queueAddRasterLayerLocked(id, rasterLayers[id]);
            }
        }

        void removeLayer(const std::string& id)
        {
            std::lock_guard<std::mutex> lock(mutex);
            rasterLayers.erase(id);
            rasterLayerOrder.erase(std::remove(rasterLayerOrder.begin(), rasterLayerOrder.end(), id), rasterLayerOrder.end());
            removeMvtLayerLocked(id);
            if (app)
            {
                queueRemoveLayerLocked(id);
            }
        }

        void moveLayerToTop(const std::string& id)
        {
            std::lock_guard<std::mutex> lock(mutex);
            auto itr = std::find(rasterLayerOrder.begin(), rasterLayerOrder.end(), id);
            if (itr != rasterLayerOrder.end())
            {
                rasterLayerOrder.erase(itr);
                rasterLayerOrder.emplace_back(id);
            }
            if (app)
            {
                queueMoveLayerToTopLocked(id);
            }
        }

        void setRasterLayerStyle(const std::string& id, bool visible, double opacity, double saturation, double contrast)
        {
            std::lock_guard<std::mutex> lock(mutex);
            auto itr = rasterLayers.find(id);
            if (itr == rasterLayers.end())
            {
                return;
            }

            itr->second.visible = visible;
            itr->second.opacity = static_cast<float>(std::clamp(opacity, 0.0, 1.0));
            itr->second.saturation = static_cast<float>(std::clamp(saturation, -1.0, 1.0));
            itr->second.contrast = static_cast<float>(std::clamp(contrast, -1.0, 1.0));
            if (app)
            {
                queueRasterLayerStyleLocked(id, itr->second);
            }
        }

        void addElevationLayer(
            const std::string& id,
            const std::string& templateUrl,
            int minZoom,
            int maxZoom,
            bool disableNativeDiskCache,
            std::vector<std::pair<std::string, std::string>> headers)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (elevationLayers.find(id) == elevationLayers.end())
            {
                elevationLayerOrder.emplace_back(id);
            }
            elevationLayers[id] = ElevationLayerConfig{
                templateUrl,
                minZoom,
                maxZoom,
                true,
                disableNativeDiskCache,
                std::move(headers)};
            __android_log_print(ANDROID_LOG_INFO, TAG, "addElevationLayer id=%s minZoom=%d maxZoom=%d", id.c_str(), minZoom, maxZoom);
            if (app)
            {
                queueAddElevationLayerLocked(id, elevationLayers[id]);
            }
        }

        void setTerrainExaggeration(double value)
        {
            std::lock_guard<std::mutex> lock(mutex);
            terrainExaggeration = static_cast<float>(std::clamp(value, 0.0, 10.0));
            if (!app)
            {
                return;
            }

            queueTerrainExaggerationLocked();
        }

        void queueAddRasterLayerLocked(const std::string& id, const RasterLayerConfig& config)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id, config]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                addRasterLayerLocked(id, config);
            });
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void queueRemoveLayerLocked(const std::string& id)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                removeLayerFromMapLocked(id);
            });
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void queueMoveLayerToTopLocked(const std::string& id)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                moveLayerToTopLocked(id);
            });
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void queueRasterLayerStyleLocked(const std::string& id, const RasterLayerConfig& config)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id, config]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                applyRasterLayerStyleLocked(id, config);
            });
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void queueAddElevationLayerLocked(const std::string& id, const ElevationLayerConfig& config)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id, config]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                addElevationLayerLocked(id, config);
            });
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void queueTerrainExaggerationLocked()
        {
            if (terrainExaggerationUpdateQueued)
            {
                return;
            }

            auto* activeApp = app.get();
            terrainExaggerationUpdateQueued = true;
            activeApp->onNextUpdate([this, activeApp]()
            {
                float value = 1.0f;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    terrainExaggerationUpdateQueued = false;
                    value = terrainExaggeration;
                    if (app.get() != activeApp || !app->mapNode)
                    {
                        return;
                    }

                    app->mapNode->terrainSettings().terrainExaggeration = value;
                    applyTerrainExaggerationToSurfaces(app->mapNode->terrainNode.get(), value);
                }

                if (activeApp->vsgcontext)
                {
                    activeApp->vsgcontext->requestFrame();
                }
                __android_log_print(ANDROID_LOG_INFO, TAG, "terrain exaggeration %.2f", value);
            });
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void setLayerVisible(const std::string& id, bool visible)
        {
            std::lock_guard<std::mutex> lock(mutex);
            auto itr = rasterLayers.find(id);
            if (itr != rasterLayers.end())
            {
                itr->second.visible = visible;
                if (app)
                {
                    queueRasterLayerStyleLocked(id, itr->second);
                }
            }
            auto mvtItr = mvtLayers.find(id);
            if (mvtItr != mvtLayers.end())
            {
                mvtItr->second.config.style.visible = visible;
                if (!visible)
                {
                    setMvtLayerActiveTilesLocked(mvtItr->second, {});
                }
                requestFrameLocked();
            }
        }

        void addModelLayer(
            const std::string& id,
            const std::string& uri,
            double longitude,
            double latitude,
            double heightMeters,
            double scale,
            double yawDegrees,
            bool visible)
        {
            if (id.empty() || uri.empty())
            {
                return;
            }

            std::lock_guard<std::mutex> lock(mutex);
            ModelLayerConfig config{
                uri,
                longitude,
                latitude,
                heightMeters,
                std::max(0.001, scale),
                yawDegrees,
                visible};
            modelLayers[id] = config;
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "addModelLayer id=%s uri=%s lon=%.6f lat=%.6f height=%.2f scale=%.3f yaw=%.2f visible=%d",
                id.c_str(),
                uri.c_str(),
                longitude,
                latitude,
                heightMeters,
                config.scale,
                yawDegrees,
                visible ? 1 : 0);
            if (app)
            {
                queueApplyModelLayerLocked(id, config);
            }
        }

        void removeModelLayer(const std::string& id)
        {
            if (id.empty())
            {
                return;
            }

            std::lock_guard<std::mutex> lock(mutex);
            modelLayers.erase(id);
            if (app)
            {
                queueRemoveModelLayerLocked(id);
            }
        }

        void setModelLayerVisible(const std::string& id, bool visible)
        {
            if (id.empty())
            {
                return;
            }

            std::lock_guard<std::mutex> lock(mutex);
            auto itr = modelLayers.find(id);
            if (itr != modelLayers.end())
            {
                itr->second.visible = visible;
            }
            if (app)
            {
                queueSetModelLayerVisibleLocked(id, visible);
            }
        }

        void add3DTilesRendererContent(
            const std::string& id,
            const std::string& renderUri,
            const std::string& transformKind,
            const std::array<double, 16>& transformMatrix,
            double ecefX,
            double ecefY,
            double ecefZ,
            bool visible)
        {
            if (id.empty() || renderUri.empty())
            {
                return;
            }
            if (transformKind != "MATRIX" && transformKind != "ECEF")
            {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    TAG,
                    "rejecting 3D Tiles renderer content id=%s invalid transformKind=%s",
                    id.c_str(),
                    transformKind.c_str());
                return;
            }
            if (!std::all_of(transformMatrix.begin(), transformMatrix.end(), [](double value)
                {
                    return std::isfinite(value);
                }) ||
                !std::isfinite(ecefX) ||
                !std::isfinite(ecefY) ||
                !std::isfinite(ecefZ))
            {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    TAG,
                    "rejecting 3D Tiles renderer content id=%s with non-finite transform",
                    id.c_str());
                return;
            }

            std::lock_guard<std::mutex> lock(mutex);
            tiles3DRendererContent[id] = Tiles3DRendererContentConfig{
                renderUri,
                transformKind,
                transformMatrix,
                ecefX,
                ecefY,
                ecefZ,
                visible};
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "registered 3D Tiles renderer content id=%s uri=%s transform=%s ecef=(%.3f,%.3f,%.3f) visible=%d",
                id.c_str(),
                renderUri.c_str(),
                transformKind.c_str(),
                ecefX,
                ecefY,
                ecefZ,
                visible ? 1 : 0);
            if (app)
            {
                queueApply3DTilesRendererContentLocked(id, tiles3DRendererContent[id]);
            }
        }

        void remove3DTilesRendererContent(const std::string& id)
        {
            if (id.empty())
            {
                return;
            }

            std::lock_guard<std::mutex> lock(mutex);
            tiles3DRendererContent.erase(id);
            if (app)
            {
                queueRemove3DTilesRendererContentLocked(id);
            }
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "removed 3D Tiles renderer content id=%s",
                id.c_str());
        }

        // --- Native 3D Tiles layer (C++ pipeline) ---

        void addTileset3DLayer(
            const std::string& layerId,
            const std::string& tilesetUri,
            const std::vector<std::pair<std::string, std::string>>& headers,
            float maxSSE,
            int maxTiles)
        {
            if (layerId.empty() || tilesetUri.empty())
                return;

            std::lock_guard<std::mutex> lock(mutex);
            removeTileset3DLayerByIdLocked(layerId);

            if (!app || !app->vsgcontext || !app->mapNode || !app->mapNode->map)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "addTileset3DLayer: app not ready id=%s", layerId.c_str());
                return;
            }

            rocky::URI::Context ctx;
            ctx.referrer = tilesetUri;
            for (const auto& h : headers)
                ctx.headers.emplace_back(h.first, h.second);

            auto layer = rocky::Tiles3DLayer::create();
            layer->name = layerId;
            layer->tilesetURI = rocky::URI(tilesetUri, ctx);
            layer->maximumScreenSpaceError = maxSSE;
            layer->maximumLoadedTiles = static_cast<unsigned>(std::max(1, maxTiles));
            layer->vsgctx = app->vsgcontext;
            layer->setViewportHeight(static_cast<float>(effectiveRenderHeight()));

            auto openResult = layer->open(app->vsgcontext->io);
            if (openResult.failed())
            {
                const auto msg = openResult.error().string();
                __android_log_print(ANDROID_LOG_ERROR, TAG,
                    "addTileset3DLayer open failed id=%s error=%s",
                    layerId.c_str(), msg.c_str());
                emitResourceStatusLocked("TILES3D", layerId, "FAILED", "NATIVE_RENDERER", msg);
                return;
            }

            app->mapNode->map->add(layer);
            tiles3DLayerMap[layerId] = layer;

            if (app->vsgcontext)
                app->vsgcontext->requestFrame();

            emitResourceStatusLocked("TILES3D", layerId, "LOADED");
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "addTileset3DLayer opened id=%s uri=%s maxSSE=%.1f maxTiles=%d",
                layerId.c_str(), tilesetUri.c_str(), maxSSE, maxTiles);
        }

        void removeTileset3DLayerByIdLocked(const std::string& layerId)
        {
            auto it = tiles3DLayerMap.find(layerId);
            if (it == tiles3DLayerMap.end())
                return;

            if (app && app->mapNode && app->mapNode->map)
                removeLayerFromMapLocked(layerId, false);

            tiles3DLayerMap.erase(it);
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "removeTileset3DLayerByIdLocked id=%s", layerId.c_str());
        }

        void removeTileset3DLayer(const std::string& layerId)
        {
            if (layerId.empty()) return;
            std::lock_guard<std::mutex> lock(mutex);
            removeTileset3DLayerByIdLocked(layerId);
            if (app && app->vsgcontext)
                app->vsgcontext->requestFrame();
        }

        void setTileset3DLayerViewportHeight(float height)
        {
            std::lock_guard<std::mutex> lock(mutex);
            for (auto& [id, layer] : tiles3DLayerMap)
                layer->setViewportHeight(height);
        }

        void addMvtLayer(const MvtLayerConfig& config)
        {
            if (config.sourceId.empty() ||
                config.layerId.empty() ||
                config.sourceLayer.empty() ||
                config.templateUrl.empty())
            {
                return;
            }
            if (config.sourceMinZoom < 0 ||
                config.sourceMaxZoom < config.sourceMinZoom ||
                config.layerMinZoom < 0 ||
                config.layerMaxZoom < config.layerMinZoom ||
                config.sourceMaxZoom > MVT_MAX_COVER_ZOOM)
            {
                return;
            }
            if (!std::isfinite(config.style.opacity) ||
                !std::isfinite(config.style.widthPixels) ||
                !std::isfinite(config.style.radiusPixels) ||
                !std::isfinite(config.style.textSizeSp))
            {
                return;
            }

            std::lock_guard<std::mutex> lock(mutex);
            removeMvtLayerLocked(config.layerId);
            MvtLayerRuntime runtime;
            runtime.config = config;
            runtime.generation = ++mvtLayerGenerationCounter;
            mvtLayers[config.layerId] = std::move(runtime);
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "registered native MVT layer id=%s source=%s sourceLayer=%s min=%d max=%d scheme=%s visible=%d",
                config.layerId.c_str(),
                config.sourceId.c_str(),
                config.sourceLayer.c_str(),
                config.sourceMinZoom,
                config.sourceMaxZoom,
                config.scheme.c_str(),
                config.style.visible ? 1 : 0);
            emitResourceStatusLocked("VECTOR", config.layerId, "LOADED");
            requestFrameLocked();
        }

        MvtSubmitResult queryMvtRenderedFeatures()
        {
            MvtSubmitResult result;
            std::lock_guard<std::mutex> lock(mutex);
            for (const auto& layerEntry : mvtLayers)
            {
                const auto& runtime = layerEntry.second;
                for (const auto& tileId : runtime.activeTiles)
                {
                    auto tile = runtime.loadedTiles.find(tileId);
                    if (tile != runtime.loadedTiles.end() && tile->second.active)
                    {
                        appendMvtQuerySnapshotForLayer(result.query, tile->second.query, runtime.config.layerId);
                    }
                }
            }
            return result;
        }

        void removeMvtLayer(const std::string& layerId)
        {
            if (layerId.empty())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(mutex);
            removeMvtLayerLocked(layerId);
        }

        void clearAnnotations()
        {
            std::lock_guard<std::mutex> lock(mutex);
            pointAnnotations.clear();
        }

        void addPointAnnotation(const std::string& id, double longitude, double latitude)
        {
            std::lock_guard<std::mutex> lock(mutex);
            pointAnnotations[id] = {longitude, latitude};
        }

        void clearDrawAnnotations()
        {
            std::lock_guard<std::mutex> lock(mutex);
            clearDrawEntitiesLocked();
        }

        void removeDrawAnnotation(const std::string& id)
        {
            std::lock_guard<std::mutex> lock(mutex);
            removeDrawAnnotationLocked(id);
        }

        void addDrawPointAnnotation(
            const std::string& id,
            double longitude,
            double latitude,
            const std::string& text,
            float textSize,
            int textColor,
            int textHaloColor,
            float textHaloWidth,
            int iconColor,
            float iconRadius)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!app || id.empty())
            {
                return;
            }
            removeDrawAnnotationLocked(id);

            auto [registryLock, registry] = app->registry.write();
            auto entity = registry.create();
            auto& style = registry.emplace<rocky::LabelStyle>(entity);
            style.fontName = resourcePath.empty()
                ? std::string("/system/fonts/DroidSans.ttf")
                : resourcePath + "/fonts/NotoSansSC-VF.ttf";
            style.textSize = std::clamp(textSize, 8.0f, 64.0f);
            style.textColor = colorFromAndroidArgb(textColor);
            style.textOutlineColor = colorFromAndroidArgb(textHaloColor);
            style.textOutlineSize = std::clamp(textHaloWidth, 0.0f, 8.0f);
            style.textPivot = {0.5f, 0.0f};
            style.textOffset = {0.0f, 20.0f};
            style.backgroundColor = rocky::StockColor::Transparent;
            style.borderSize = 0.0f;
            style.padding = {2.0f, 2.0f};
            style.icon = createCircleIcon(iconColor, iconRadius);
            style.iconSizePixels = std::max(2.0f, iconRadius * 2.0f);
            style.iconPivot = {0.5f, 0.5f};

            registry.emplace<rocky::Label>(entity, text, style);
            auto& transform = registry.emplace<rocky::Transform>(entity);
            transform.position = rocky::GeoPoint(rocky::SRS::WGS84, longitude, latitude, 1000.0);
            transform.radius = 1000.0;
            transform.horizonCulled = true;
            transform.frustumCulled = true;
            drawEntities[id].push_back(entity);
            requestFrameLocked();
        }

        void addDrawLineAnnotation(
            const std::string& id,
            const std::vector<glm::dvec3>& coordinates,
            int lineColor,
            float lineWidth)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!app || id.empty() || coordinates.size() < 2)
            {
                return;
            }
            removeDrawAnnotationLocked(id);
            addDrawLineEntityLocked(id, coordinates, lineColor, lineWidth, 2500.0f);
            requestFrameLocked();
        }

        void addDrawPolygonAnnotation(
            const std::string& id,
            const std::vector<std::vector<glm::dvec3>>& rings,
            int fillColor,
            float fillOpacity,
            int outlineColor,
            float outlineWidth)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!app || id.empty() || rings.empty() || rings.front().size() < 3)
            {
                return;
            }
            removeDrawAnnotationLocked(id);

            rocky::Feature polygon;
            polygon.geometry.type = rocky::Geometry::Type::Polygon;
            polygon.srs = rocky::SRS::WGS84;
            polygon.geometry.points = rings.front();
            for (std::size_t i = 1; i < rings.size(); ++i)
            {
                rocky::Geometry hole(rocky::Geometry::Type::LineString, rings[i]);
                polygon.geometry.parts.emplace_back(std::move(hole));
            }

            auto [registryLock, registry] = app->registry.write();
            auto fillEntity = registry.create();
            auto& meshStyle = registry.emplace<rocky::MeshStyle>(fillEntity);
            auto color = colorFromAndroidArgb(fillColor);
            meshStyle.color = rocky::Color(color, std::clamp(fillOpacity, 0.0f, 1.0f));
            meshStyle.depthOffset = 2000.0f;
            meshStyle.writeDepth = false;
            meshStyle.drawBackfaces = true;
            meshStyle.twoPassAlpha = true;
            meshStyle.transparencyBin = true;
            auto& meshGeom = registry.emplace<rocky::MeshGeometry>(fillEntity);
            rocky::FeatureBuilder builder;
            builder.buildMeshGeometry({polygon}, meshStyle, meshGeom);
            registry.emplace<rocky::Mesh>(fillEntity, meshGeom, meshStyle);
            drawEntities[id].push_back(fillEntity);

            rocky::Feature outline;
            outline.geometry.type = rocky::Geometry::Type::LineString;
            outline.interpolation = rocky::GeodeticInterpolation::RhumbLine;
            outline.srs = rocky::SRS::WGS84;
            outline.geometry.points = rings.front();
            auto outlineEntity = registry.create();
            auto& lineStyle = registry.emplace<rocky::LineStyle>(outlineEntity);
            lineStyle.color = colorFromAndroidArgb(outlineColor);
            lineStyle.width = std::max(1.0f, outlineWidth);
            lineStyle.depthOffset = 3500.0f;
            lineStyle.resolution = 100000.0f;
            lineStyle.transparencyBin = true;
            auto& lineGeom = registry.emplace<rocky::LineGeometry>(outlineEntity);
            builder.buildLineGeometry({outline}, lineStyle, lineGeom);
            registry.emplace<rocky::Line>(outlineEntity, lineGeom, lineStyle);
            drawEntities[id].push_back(outlineEntity);
            requestFrameLocked();
        }

        void clearLabelAnnotations()
        {
            std::lock_guard<std::mutex> lock(mutex);
            labelAnnotations.clear();
            clearLabelEntitiesLocked();
        }

        void addLabelAnnotation(const LabelAnnotationConfig& config)
        {
            std::lock_guard<std::mutex> lock(mutex);
            labelAnnotations[config.id] = config;
            applyLabelAnnotationLocked(config);
        }

        void setLocationIndicator(const LocationIndicatorConfig& config)
        {
            std::lock_guard<std::mutex> lock(mutex);
            locationIndicator = config;
            syncLocationIndicatorLocked();
        }

        void clearLocationIndicator()
        {
            std::lock_guard<std::mutex> lock(mutex);
            locationIndicator.reset();
            clearLocationEntitiesLocked();
        }

    private:
        std::string startRendererLocked()
        {
            if (!nativeWindow)
            {
                return "Android native window is not available for Vectorra renderer";
            }

            try
            {
                if (auto vulkanFailure = checkVulkanAvailability())
                {
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", vulkanFailure->c_str());
                    return *vulkanFailure;
                }

                const bool createApplication = !app;
                if (createApplication)
                {
                    app = std::make_unique<rocky::Application>();
                    app->autoCreateWindow = false;
                    app->renderContinuously = true;
                    if (!app->ok())
                    {
                        const auto message = app->vsgcontext && app->vsgcontext->status.failed()
                            ? app->vsgcontext->status.error().string()
                            : std::string("unknown initialization failure");
                        __android_log_print(ANDROID_LOG_ERROR, TAG, "rocky Application not ok: %s", message.c_str());
                        app.reset();
                        return "Vectorra renderer initialization failed: " + message;
                    }
                    if (app->systemsNode && !app->systemsNode->get<rocky::ModelSystemNode>())
                    {
                        app->systemsNode->add(rocky::ModelSystemNode::create(app->registry));
                        __android_log_print(ANDROID_LOG_INFO, TAG, "enabled rocky ModelSystemNode");
                    }
                    if (app->scene)
                    {
                        // Models loaded through vsgXchange (GLB/glTF, 3D Tiles
                        // content) use VSG's PBR shading, which renders black
                        // without at least one vsg::Light in the scene. The
                        // rocky terrain has its own shaders and is unaffected.
                        app->scene->addChild(vsg::createHeadlight());
                        auto ambient = vsg::AmbientLight::create();
                        ambient->color = { 0.25f, 0.25f, 0.25f };
                        app->scene->addChild(ambient);
                        __android_log_print(ANDROID_LOG_INFO, TAG, "added scene headlight + ambient light");
                    }
                    __android_log_print(
                        ANDROID_LOG_INFO,
                        TAG,
                        "created rocky Application ok=%d mapNode=%d map=%d",
                        app->ok() ? 1 : 0,
                        app->mapNode ? 1 : 0,
                        app->mapNode && app->mapNode->map ? 1 : 0);

                    if (app->mapNode)
                    {
                        auto& terrainSettings = app->mapNode->terrainSettings();
                        terrainSettings.tileSize = 33u;
                        terrainSettings.minLevel = 4u;
                        terrainSettings.tilePixelSize = 256.0f;
                        terrainSettings.pixelError = 96.0f;
                        terrainSettings.tileCacheSize = 512u;
                        terrainSettings.concurrency = 4u;
                        terrainSettings.terrainExaggeration = terrainExaggeration;
                        __android_log_print(
                            ANDROID_LOG_INFO,
                            TAG,
                            "terrain settings tileSize=%u minLevel=%u tilePixelSize=%.1f pixelError=%.1f cache=%u exaggeration=%.2f",
                            terrainSettings.tileSize.value(),
                            terrainSettings.minLevel.value(),
                            terrainSettings.tilePixelSize.value(),
                            terrainSettings.pixelError.value(),
                            terrainSettings.tileCacheSize.value(),
                            terrainSettings.terrainExaggeration.value());
                    }

                    if (rasterLayers.empty())
                    {
                        rasterLayerOrder.emplace_back("default-readymap");
                        rasterLayers.emplace(
                            "default-readymap",
                        RasterLayerConfig{
                            "http://readymap.org/readymap/tiles/1.0.0/7/",
                            0,
                            18,
                            true,
                            1.0f,
                            0.0f,
                            0.0f,
                            256,
                            "TMS",
                            ""});
                    }

                    for (const auto& id : rasterLayerOrder)
                    {
                        auto entry = rasterLayers.find(id);
                        if (entry != rasterLayers.end())
                        {
                            addRasterLayerLocked(entry->first, entry->second);
                        }
                    }

                    for (const auto& id : elevationLayerOrder)
                    {
                        auto entry = elevationLayers.find(id);
                        if (entry != elevationLayers.end())
                        {
                            addElevationLayerLocked(entry->first, entry->second);
                        }
                    }

                    syncLabelAnnotationsLocked();
                    syncLocationIndicatorLocked();
                    syncModelLayersLocked();
                    sync3DTilesRendererContentLocked();
                    syncMvtTilesLocked();
                }

                int renderWidth = surfaceWidth;
                int renderHeight = surfaceHeight;
                const int maxSurfaceExtent = std::max(surfaceWidth, surfaceHeight);
                if (maxSurfaceExtent > MAX_ANDROID_RENDER_EXTENT)
                {
                    const double scale = static_cast<double>(MAX_ANDROID_RENDER_EXTENT) /
                        static_cast<double>(maxSurfaceExtent);
                    renderWidth = std::max(1, static_cast<int>(surfaceWidth * scale));
                    renderHeight = std::max(1, static_cast<int>(surfaceHeight * scale));
                    ANativeWindow_setBuffersGeometry(
                        nativeWindow,
                        renderWidth,
                        renderHeight,
                        WINDOW_FORMAT_RGBA_8888);
                    __android_log_print(
                        ANDROID_LOG_INFO,
                        TAG,
                        "capped Android render surface from %dx%d to %dx%d",
                        surfaceWidth,
                        surfaceHeight,
                        renderWidth,
                        renderHeight);
                }

                auto traits = vsg::WindowTraits::create();
                traits->width = static_cast<uint32_t>(renderWidth);
                traits->height = static_cast<uint32_t>(renderHeight);
                traits->vulkanVersion = VK_API_VERSION_1_1;
                traits->instanceExtensionNames.clear();
                traits->setValue("nativeWindow", nativeWindow);
                __android_log_print(ANDROID_LOG_INFO, TAG, "creating VSG window %dx%d", renderWidth, renderHeight);
                logVulkanSurfaceProbe(nativeWindow);
                app->display.addWindow(traits);
                if (app->display.windows().empty())
                {
                    __android_log_print(
                        ANDROID_LOG_ERROR,
                        TAG,
                        "failed to create VSG window: display has no windows after addWindow");
                    if (createApplication)
                    {
                        app.reset();
                    }
                    return "Vectorra renderer startup failed: VSG Android window was not created";
                }
                if (createApplication)
                {
                    app->realize();
                }
                else
                {
                    auto& mainWindow = app->display.window(0);
                    auto computeCommandGraph = app->vsgcontext->getOrCreateComputeCommandGraph(
                        app->display.sharedDevice(),
                        mainWindow.commandGraph->queueFamily);
                    vsg::CommandGraphs commandGraphs{ computeCommandGraph };
                    for (auto& window : app->display.windows())
                    {
                        if (window.commandGraph)
                        {
                            commandGraphs.emplace_back(window.commandGraph);
                        }
                    }
                    app->viewer->assignRecordAndSubmitTaskAndPresentation(commandGraphs);
                }
                applyCameraNow(app.get());
                __android_log_print(ANDROID_LOG_INFO, TAG, "rocky Application realized");

                running = true;
                renderThread = std::thread([this]()
                {
                    int frameCount = 0;
                    while (running)
                    {
                        std::unique_lock<std::mutex> frameLock(mutex);
                        auto* activeApp = app.get();
                        frameLock.unlock();

                        if (activeApp)
                        {
                            try
                            {
                                {
                                    std::lock_guard<std::mutex> motionServiceLock(mutex);
                                    if (app.get() == activeApp)
                                    {
                                        servicePendingMotionOnRenderThread(activeApp);
                                    }
                                }
                                if (!activeApp->frame())
                                {
                                    __android_log_print(ANDROID_LOG_WARN, TAG, "rocky frame returned false");
                                    running = false;
                                }
                                else if (frameCount < 3)
                                {
                                    ++frameCount;
                                    __android_log_print(ANDROID_LOG_INFO, TAG, "rocky frame %d ok", frameCount);
                                    if (frameCount == 3)
                                    {
                                        logRenderState(activeApp);
                                    }
                                }
                                {
                                    std::lock_guard<std::mutex> statusLock(mutex);
                                    if (app.get() == activeApp)
                                    {
                                        updateProjectionSnapshotLocked(activeApp);
                                        const bool nativeChangedCamera =
                                            syncCameraStateFromManipulatorLocked(activeApp, false);
                                        if (nativeChangedCamera)
                                        {
                                            emitCameraStateLocked(currentCameraState());
                                        }
                                        serviceMvtLayersLocked(activeApp);
                                        logModelLayerStatusesLocked(activeApp);
                                    }
                                }
                            }
                            catch (const std::exception& e)
                            {
                                __android_log_print(ANDROID_LOG_ERROR, TAG, "rocky frame exception: %s", e.what());
                                running = false;
                            }
                            catch (const vsg::Exception& e)
                            {
                                __android_log_print(
                                    ANDROID_LOG_ERROR,
                                    TAG,
                                    "rocky frame vsg::Exception result=%d message=%s",
                                    e.result,
                                    e.message.c_str());
                                running = false;
                            }
                        }
                        else
                        {
                            running = false;
                        }

                        std::this_thread::sleep_for(std::chrono::milliseconds(16));
                    }
                });
                return {};
            }
            catch (const std::exception& e)
            {
                __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to start rocky renderer: %s", e.what());
                detachSurfaceLockedNoThread();
                return std::string("Vectorra renderer startup failed: ") + e.what();
            }
            catch (const vsg::Exception& e)
            {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    TAG,
                        "failed to start rocky renderer: vsg::Exception result=%d message=%s",
                        e.result,
                        e.message.c_str());
                detachSurfaceLockedNoThread();
                return "Vectorra renderer startup failed: vsg::Exception result=" +
                    std::to_string(e.result) + " message=" + e.message;
            }
            catch (...)
            {
                __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to start rocky renderer: unknown exception");
                detachSurfaceLockedNoThread();
                return "Vectorra renderer startup failed: unknown native exception";
            }
        }

        void detachSurfaceLocked(std::unique_lock<std::mutex>& lock)
        {
            running = false;
            terrainExaggerationUpdateQueued = false;
            clearPendingMotion();
            if (renderThread.joinable())
            {
                auto thread = std::move(renderThread);
                lock.unlock();
                thread.join();
                lock.lock();
            }
            detachSurfaceLockedNoThread();
        }

        void detachSurfaceLockedNoThread()
        {
            if (app)
            {
                while (!app->display.windows().empty())
                {
                    app->display.removeWindow(app->display.windows().back());
                }
                if (app->viewer)
                {
                    app->viewer->deviceWaitIdle();
                    app->viewer->recordAndSubmitTasks.clear();
                    app->viewer->presentations.clear();
                }
            }
            if (nativeWindow)
            {
                __android_log_print(ANDROID_LOG_INFO, TAG, "release native window after renderer stop");
                ANativeWindow_release(nativeWindow);
                nativeWindow = nullptr;
            }
        }

        void shutdownRendererLocked(std::unique_lock<std::mutex>& lock)
        {
            projectionSnapshot.ready = false;
            detachSurfaceLocked(lock);
            labelEntities.clear();
            drawEntities.clear();
            modelEntities.clear();
            modelLoggedRadii.clear();
            modelLoggedErrors.clear();
            tiles3DEntities.clear();
            tiles3DLoggedRadii.clear();
            tiles3DLoggedErrors.clear();
            tiles3DRendererContent.clear();
            tiles3DLayerMap.clear();
            for (auto& entry : mvtLayers)
            {
                for (auto& pending : entry.second.pendingTiles)
                {
                    pending.second.future.reset();
                }
            }
            mvtLayers.clear();
            mvtTiles.clear();
            mvtEntities.clear();
            mvtTileRevisions.clear();
            mvtTileRevisionCounter = 0;
            locationEntities.clear();
            app.reset();
        }

        void clearLabelEntitiesLocked()
        {
            if (app)
            {
                auto [registryLock, registry] = app->registry.write();
                for (const auto& entry : labelEntities)
                {
                    if (registry.valid(entry.second))
                    {
                        registry.destroy(entry.second);
                    }
                }
            }
            labelEntities.clear();
            if (app && app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void requestFrameLocked()
        {
            if (app && app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void clearResourceStatusCallback()
        {
            if (!javaVm || !statusCallback)
            {
                statusCallback = nullptr;
                statusCallbackMethod = nullptr;
                if (!cameraCallback)
                {
                    javaVm = nullptr;
                }
                return;
            }

            JNIEnv* env = nullptr;
            bool attached = false;
            jint envResult = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (envResult == JNI_EDETACHED)
            {
                if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
                {
                    statusCallback = nullptr;
                    statusCallbackMethod = nullptr;
                    if (!cameraCallback)
                    {
                        javaVm = nullptr;
                    }
                    return;
                }
                attached = true;
            }

            if (env)
            {
                env->DeleteGlobalRef(statusCallback);
            }
            statusCallback = nullptr;
            statusCallbackMethod = nullptr;

            if (attached)
            {
                javaVm->DetachCurrentThread();
            }
            if (!cameraCallback)
            {
                javaVm = nullptr;
            }
        }

        void clearCameraCallback()
        {
            if (!javaVm || !cameraCallback)
            {
                cameraCallback = nullptr;
                cameraCallbackMethod = nullptr;
                if (!statusCallback)
                {
                    javaVm = nullptr;
                }
                return;
            }

            JNIEnv* env = nullptr;
            bool attached = false;
            jint envResult = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (envResult == JNI_EDETACHED)
            {
                if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
                {
                    cameraCallback = nullptr;
                    cameraCallbackMethod = nullptr;
                    if (!statusCallback)
                    {
                        javaVm = nullptr;
                    }
                    return;
                }
                attached = true;
            }

            if (env)
            {
                env->DeleteGlobalRef(cameraCallback);
            }
            cameraCallback = nullptr;
            cameraCallbackMethod = nullptr;

            if (attached)
            {
                javaVm->DetachCurrentThread();
            }
            if (!statusCallback)
            {
                javaVm = nullptr;
            }
        }

        void emitResourceStatusLocked(
            const char* kind,
            const std::string& layerId,
            const char* state,
            const char* errorType = nullptr,
            const std::string& errorMessage = std::string())
        {
            if (!javaVm || !statusCallback || !statusCallbackMethod || layerId.empty())
            {
                return;
            }

            JNIEnv* env = nullptr;
            bool attached = false;
            jint envResult = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (envResult == JNI_EDETACHED)
            {
                if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
                {
                    return;
                }
                attached = true;
            }
            else if (envResult != JNI_OK || !env)
            {
                return;
            }

            jstring jKind = env->NewStringUTF(kind);
            jstring jLayerId = env->NewStringUTF(layerId.c_str());
            jstring jState = env->NewStringUTF(state);
            jstring jErrorType = errorType ? env->NewStringUTF(errorType) : nullptr;
            jstring jErrorMessage = !errorMessage.empty() ? env->NewStringUTF(errorMessage.c_str()) : nullptr;
            env->CallVoidMethod(statusCallback, statusCallbackMethod, jKind, jLayerId, jState, jErrorType, jErrorMessage);
            if (env->ExceptionCheck())
            {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            env->DeleteLocalRef(jKind);
            env->DeleteLocalRef(jLayerId);
            env->DeleteLocalRef(jState);
            if (jErrorType)
            {
                env->DeleteLocalRef(jErrorType);
            }
            if (jErrorMessage)
            {
                env->DeleteLocalRef(jErrorMessage);
            }

            if (attached)
            {
                javaVm->DetachCurrentThread();
            }
        }

        void emitCameraStateLocked(const NativeCameraState& state)
        {
            if (!javaVm || !cameraCallback || !cameraCallbackMethod)
            {
                return;
            }

            JNIEnv* env = nullptr;
            bool attached = false;
            jint envResult = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (envResult == JNI_EDETACHED)
            {
                if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
                {
                    return;
                }
                attached = true;
            }
            else if (envResult != JNI_OK || !env)
            {
                return;
            }

            env->CallVoidMethod(
                cameraCallback,
                cameraCallbackMethod,
                static_cast<jdouble>(state.longitude),
                static_cast<jdouble>(state.latitude),
                static_cast<jdouble>(state.zoom),
                static_cast<jdouble>(state.pitch),
                static_cast<jdouble>(state.bearing));
            if (env->ExceptionCheck())
            {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }

            if (attached)
            {
                javaVm->DetachCurrentThread();
            }
        }

        void queueApplyModelLayerLocked(const std::string& id, const ModelLayerConfig& config)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id, config]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                applyModelLayerLocked(id, config);
            });
            requestFrameLocked();
        }

        vsg::dvec2 normalizedPixelDelta(float deltaX, float deltaY, int viewWidth, int viewHeight) const
        {
            const double renderWidth = static_cast<double>(std::max(effectiveRenderWidth(), 1));
            const double renderHeight = static_cast<double>(std::max(effectiveRenderHeight(), 1));
            const double renderDeltaX = static_cast<double>(deltaX) * renderWidth /
                static_cast<double>(std::max(viewWidth, 1));
            const double renderDeltaY = static_cast<double>(deltaY) * renderHeight /
                static_cast<double>(std::max(viewHeight, 1));
            const double aspectRatio = renderWidth / renderHeight;
            return vsg::dvec2(
                renderDeltaX / renderWidth * 2.0 * aspectRatio,
                -renderDeltaY / renderHeight * 2.0);
        }

        bool zoomByScaleAtOnUpdate(
            rocky::Application* activeApp,
            float scale,
            float focusX,
            float focusY,
            int viewWidth,
            int viewHeight)
        {
            auto manipulator = manipulatorFor(activeApp);
            if (!manipulator || !activeApp || !activeApp->mapNode)
            {
                return false;
            }

            const double renderX = static_cast<double>(focusX) *
                static_cast<double>(std::max(effectiveRenderWidth(), 1)) /
                static_cast<double>(std::max(viewWidth, 1));
            const double renderY = static_cast<double>(focusY) *
                static_cast<double>(std::max(effectiveRenderHeight(), 1)) /
                static_cast<double>(std::max(viewHeight, 1));

            vsg::dvec3 targetWorld;
            if (!manipulator->viewportToWorld(static_cast<float>(renderX), static_cast<float>(renderY), targetWorld))
            {
                const double zoomDelta = 1.0 / static_cast<double>(scale) - 1.0;
                manipulator->zoom(0.0, zoomDelta);
                return true;
            }

            auto viewpoint = manipulator->viewpoint();
            if (!viewpoint.valid() || !viewpoint.range.has_value())
            {
                return false;
            }

            const double oldRange = viewpoint.range->as(rocky::Units::METERS);
            if (!std::isfinite(oldRange) || oldRange <= 0.0)
            {
                return false;
            }

            const double newRange = std::clamp(
                oldRange / static_cast<double>(scale),
                MIN_CAMERA_RANGE_METERS,
                MAX_CAMERA_RANGE_METERS);
            const double ratio = std::clamp((oldRange - newRange) / oldRange, -4.0, 4.0);

            auto centerWorldGeo = viewpoint.position().transform(activeApp->mapNode->srs());
            if (!centerWorldGeo)
            {
                return false;
            }

            vsg::dvec3 centerWorld(centerWorldGeo.x, centerWorldGeo.y, centerWorldGeo.z);
            vsg::dvec3 nextCenter = centerWorld + (targetWorld - centerWorld) * ratio;
            if (activeApp->mapNode->srs().isGeocentric())
            {
                const double centerLength = vsg::length(centerWorld);
                const double nextLength = vsg::length(nextCenter);
                if (centerLength > 0.0 && nextLength > 0.0)
                {
                    nextCenter = vsg::normalize(nextCenter) * centerLength;
                }
            }

            auto nextWgs84 = rocky::GeoPoint(activeApp->mapNode->srs(), nextCenter).transform(rocky::SRS::WGS84);
            if (!nextWgs84)
            {
                return false;
            }

            viewpoint.point = rocky::GeoPoint(
                rocky::SRS::WGS84,
                wrapLongitude(nextWgs84.x),
                clampLatitude(nextWgs84.y),
                std::isfinite(nextWgs84.z) ? nextWgs84.z : 0.0);
            viewpoint.range = rocky::Distance(newRange, rocky::Units::METERS);
            manipulator->setViewpoint(viewpoint, std::chrono::duration<float>(0.0f));
            return true;
        }

        void cancelFlingMotionLocked()
        {
            flingActive = false;
            flingVelocityX = 0.0;
            flingVelocityY = 0.0;
        }

        void clearPendingMotion()
        {
            std::lock_guard<std::mutex> lock(motionMutex);
            pendingGesture = PendingGestureMotion{};
            cancelFlingMotionLocked();
        }

        void enqueuePanGesture(float deltaX, float deltaY, int viewWidth, int viewHeight)
        {
            if (!running.load())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(motionMutex);
            pendingGesture.hasPan = true;
            pendingGesture.panDeltaX += deltaX;
            pendingGesture.panDeltaY += deltaY;
            pendingGesture.panViewWidth = viewWidth;
            pendingGesture.panViewHeight = viewHeight;
        }

        void enqueueZoomGesture(float scale)
        {
            if (!running.load())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(motionMutex);
            pendingGesture.hasZoom = true;
            pendingGesture.scale = std::clamp(
                pendingGesture.scale * static_cast<double>(scale),
                1.0e-6,
                1.0e6);
        }

        void enqueueZoomAtGesture(float scale, float focusX, float focusY, int viewWidth, int viewHeight)
        {
            if (!running.load())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(motionMutex);
            pendingGesture.hasZoom = true;
            pendingGesture.zoomAtFocus = true;
            pendingGesture.scale = std::clamp(
                pendingGesture.scale * static_cast<double>(scale),
                1.0e-6,
                1.0e6);
            pendingGesture.focusX = focusX;
            pendingGesture.focusY = focusY;
            pendingGesture.zoomViewWidth = viewWidth;
            pendingGesture.zoomViewHeight = viewHeight;
        }

        void enqueueRotateGesture(double deltaDegrees)
        {
            if (!running.load())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(motionMutex);
            pendingGesture.hasRotate = true;
            pendingGesture.rotateDegrees += deltaDegrees;
        }

        void enqueuePitchGesture(double deltaDegrees)
        {
            if (!running.load())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(motionMutex);
            pendingGesture.hasPitch = true;
            pendingGesture.pitchDegrees += deltaDegrees;
        }

        bool servicePendingMotionOnRenderThread(rocky::Application* activeApp)
        {
            PendingGestureMotion gesture;
            bool hasFlingDelta = false;
            float flingDeltaX = 0.0f;
            float flingDeltaY = 0.0f;
            int flingWidth = 1;
            int flingHeight = 1;

            {
                std::lock_guard<std::mutex> lock(motionMutex);
                if (!pendingGesture.empty())
                {
                    gesture = pendingGesture;
                    pendingGesture = PendingGestureMotion{};
                }

                if (flingActive)
                {
                    const auto now = std::chrono::steady_clock::now();
                    double dt = std::chrono::duration<double>(now - flingLastTick).count();
                    flingLastTick = now;
                    dt = std::clamp(dt, 0.0, 0.05);
                    if (dt > 0.0)
                    {
                        flingDeltaX = static_cast<float>(flingVelocityX * dt);
                        flingDeltaY = static_cast<float>(flingVelocityY * dt);
                        flingWidth = flingViewWidth;
                        flingHeight = flingViewHeight;
                        hasFlingDelta = true;

                        const double decay = std::exp(-FLING_DECAY_PER_SECOND * dt);
                        flingVelocityX *= decay;
                        flingVelocityY *= decay;
                        if (std::hypot(flingVelocityX, flingVelocityY) <= FLING_STOP_VELOCITY_PIXELS_PER_SECOND)
                        {
                            cancelFlingMotionLocked();
                        }
                    }
                }
            }

            if (gesture.empty() && !hasFlingDelta)
            {
                return false;
            }

            auto manipulator = manipulatorFor(activeApp);
            if (!manipulator)
            {
                std::lock_guard<std::mutex> lock(motionMutex);
                cancelFlingMotionLocked();
                return false;
            }

            bool changed = false;
            if (gesture.hasPan)
            {
                const auto delta = normalizedPixelDelta(
                    static_cast<float>(gesture.panDeltaX),
                    static_cast<float>(gesture.panDeltaY),
                    gesture.panViewWidth,
                    gesture.panViewHeight);
                manipulator->pan(delta.x, delta.y);
                changed = true;
            }
            if (gesture.hasZoom)
            {
                if (gesture.zoomAtFocus)
                {
                    changed = zoomByScaleAtOnUpdate(
                        activeApp,
                        static_cast<float>(gesture.scale),
                        static_cast<float>(gesture.focusX),
                        static_cast<float>(gesture.focusY),
                        gesture.zoomViewWidth,
                        gesture.zoomViewHeight) || changed;
                }
                else
                {
                    const double zoomDelta = 1.0 / gesture.scale - 1.0;
                    manipulator->zoom(0.0, zoomDelta);
                    changed = true;
                }
            }
            if (gesture.hasRotate)
            {
                manipulator->rotate((-gesture.rotateDegrees * PI / 180.0), 0.0);
                changed = true;
            }
            if (gesture.hasPitch)
            {
                manipulator->rotate(0.0, gesture.pitchDegrees * PI / 180.0);
                changed = true;
            }
            if (hasFlingDelta)
            {
                const auto delta = normalizedPixelDelta(flingDeltaX, flingDeltaY, flingWidth, flingHeight);
                manipulator->pan(delta.x, delta.y);
                changed = true;
            }
            if (changed && activeApp && activeApp->vsgcontext)
            {
                activeApp->vsgcontext->requestFrame();
            }
            return changed;
        }

        bool cameraStatesClose(const NativeCameraState& lhs, const NativeCameraState& rhs) const
        {
            return std::abs(lhs.longitude - rhs.longitude) <= CAMERA_STATE_EPSILON &&
                std::abs(lhs.latitude - rhs.latitude) <= CAMERA_STATE_EPSILON &&
                std::abs(lhs.zoom - rhs.zoom) <= CAMERA_STATE_EPSILON &&
                std::abs(lhs.pitch - rhs.pitch) <= CAMERA_STATE_EPSILON &&
                std::abs(lhs.bearing - rhs.bearing) <= CAMERA_STATE_EPSILON;
        }

        bool syncCameraStateFromManipulatorLocked(rocky::Application* activeApp, bool force)
        {
            auto manipulator = manipulatorFor(activeApp);
            if (!manipulator || !activeApp || !activeApp->mapNode)
            {
                return false;
            }

            const auto viewpoint = manipulator->viewpoint();
            if (!viewpoint.valid())
            {
                return false;
            }

            auto wgs84 = viewpoint.position().transform(rocky::SRS::WGS84);
            if (!wgs84 || !std::isfinite(wgs84.x) || !std::isfinite(wgs84.y))
            {
                return false;
            }

            const double rangeMeters = viewpoint.range.has_value()
                ? viewpoint.range->as(rocky::Units::METERS)
                : cameraRangeMeters();
            const double pitchDegrees = viewpoint.pitch.has_value()
                ? viewpoint.pitch->as(rocky::Units::DEGREES)
                : (cameraState.pitch - 90.0);
            const double headingDegrees = viewpoint.heading.has_value()
                ? viewpoint.heading->as(rocky::Units::DEGREES)
                : cameraState.bearing;

            NativeCameraState next{
                wrapLongitude(wgs84.x),
                clampLatitude(wgs84.y),
                cameraZoomForRange(rangeMeters, wgs84.y, effectiveRenderHeight()),
                clampPitch(pitchDegrees + 90.0),
                normalizeBearing(headingDegrees),
                std::isfinite(wgs84.z) ? wgs84.z : 0.0};

            if (!force && cameraStatesClose(cameraState, next))
            {
                return false;
            }

            setCachedCameraStateLocked(next);
            return true;
        }

        void queueRemoveModelLayerLocked(const std::string& id)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                removeModelEntityLocked(id);
            });
            requestFrameLocked();
        }

        void queueSetModelLayerVisibleLocked(const std::string& id, bool visible)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id, visible]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                setModelEntityVisibleLocked(id, visible);
            });
            requestFrameLocked();
        }

        void queueApply3DTilesRendererContentLocked(
            const std::string& id,
            const Tiles3DRendererContentConfig& config)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id, config]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                apply3DTilesRendererContentLocked(id, config);
            });
            requestFrameLocked();
        }

        void queueRemove3DTilesRendererContentLocked(const std::string& id)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, id]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                remove3DTilesRendererContentEntityLocked(id);
            });
            requestFrameLocked();
        }

        void syncModelLayersLocked()
        {
            if (!app)
            {
                return;
            }

            for (const auto& entry : modelLayers)
            {
                applyModelLayerLocked(entry.first, entry.second);
            }
        }

        void sync3DTilesRendererContentLocked()
        {
            if (!app)
            {
                return;
            }

            for (const auto& entry : tiles3DRendererContent)
            {
                apply3DTilesRendererContentLocked(entry.first, entry.second);
            }
        }

        void syncMvtTilesLocked()
        {
            if (!app)
            {
                return;
            }

            mvtEntities.clear();
            for (const auto& entry : mvtTiles)
            {
                applyMvtTileLocked(entry.first, entry.second);
            }
        }

        std::set<MvtTileId> loadedMvtTileIds(const MvtLayerRuntime& runtime) const
        {
            std::set<MvtTileId> result;
            for (const auto& entry : runtime.loadedTiles)
            {
                result.insert(entry.first);
            }
            return result;
        }

        std::set<MvtTileId> visibleMvtTilesAtZoomLocked(
            rocky::Application* activeApp,
            const MvtLayerConfig& config,
            int tileZoom,
            int tilePadding)
        {
            std::set<MvtTileId> result;
            const int z = std::clamp(tileZoom, 0, MVT_MAX_COVER_ZOOM);
            const int tileCount = 1 << z;
            const double centerTileX = mvtMercatorTileX(cameraState.longitude, z);
            const double centerTileY = mvtMercatorTileY(cameraState.latitude, z);

            struct CoverPoint
            {
                double x = 0.0;
                double y = 0.0;
            };
            std::vector<CoverPoint> points;
            const int renderWidth = std::max(effectiveRenderWidth(), 1);
            const int renderHeight = std::max(effectiveRenderHeight(), 1);
            for (int sampleY = 0; sampleY < MVT_COVER_SAMPLE_GRID; ++sampleY)
            {
                for (int sampleX = 0; sampleX < MVT_COVER_SAMPLE_GRID; ++sampleX)
                {
                    const int x = MVT_COVER_SAMPLE_GRID <= 1
                        ? renderWidth / 2
                        : static_cast<int>(std::lround(
                            static_cast<double>(sampleX) * static_cast<double>(renderWidth - 1) /
                            static_cast<double>(MVT_COVER_SAMPLE_GRID - 1)));
                    const int y = MVT_COVER_SAMPLE_GRID <= 1
                        ? renderHeight / 2
                        : static_cast<int>(std::lround(
                            static_cast<double>(sampleY) * static_cast<double>(renderHeight - 1) /
                            static_cast<double>(MVT_COVER_SAMPLE_GRID - 1)));
                    const auto hit = screenToCoordinateOnUpdateLocked(activeApp, x, y);
                    if (!hit.hit)
                    {
                        continue;
                    }
                    double tileX = mvtMercatorTileX(hit.longitude, z);
                    while (tileX - centerTileX > static_cast<double>(tileCount) * 0.5)
                    {
                        tileX -= static_cast<double>(tileCount);
                    }
                    while (tileX - centerTileX < -static_cast<double>(tileCount) * 0.5)
                    {
                        tileX += static_cast<double>(tileCount);
                    }
                    points.push_back(CoverPoint{
                        tileX,
                        mvtMercatorTileY(hit.latitude, z)});
                }
            }

            if (points.empty())
            {
                points.push_back(CoverPoint{centerTileX, centerTileY});
            }

            double minX = points.front().x;
            double maxX = points.front().x;
            double minY = points.front().y;
            double maxY = points.front().y;
            for (const auto& point : points)
            {
                minX = std::min(minX, point.x);
                maxX = std::max(maxX, point.x);
                minY = std::min(minY, point.y);
                maxY = std::max(maxY, point.y);
            }

            const int minTileY = std::max(0, static_cast<int>(std::floor(minY)) - tilePadding);
            const int maxTileY = std::min(tileCount - 1, static_cast<int>(std::floor(maxY - 1e-9)) + tilePadding);
            if (minTileY > maxTileY)
            {
                return result;
            }

            const int minTileX = static_cast<int>(std::floor(minX)) - tilePadding;
            const int maxTileX = static_cast<int>(std::floor(maxX - 1e-9)) + tilePadding;
            const long long xSpan = static_cast<long long>(maxTileX) - static_cast<long long>(minTileX) + 1LL;

            struct Candidate
            {
                MvtTileId tileId;
                double distanceSquared = 0.0;
            };
            std::vector<Candidate> candidates;
            const auto addCandidate = [&](int rawX, int y)
            {
                const int wrappedX = mvtFloorMod(rawX, tileCount);
                const double dx = static_cast<double>(rawX) + 0.5 - centerTileX;
                const double dy = static_cast<double>(y) + 0.5 - centerTileY;
                candidates.push_back(Candidate{
                    MvtTileId{z, wrappedX, y},
                    dx * dx + dy * dy});
            };
            if (xSpan >= static_cast<long long>(tileCount))
            {
                for (int x = 0; x < tileCount; ++x)
                {
                    for (int y = minTileY; y <= maxTileY; ++y)
                    {
                        addCandidate(x, y);
                    }
                }
            }
            else
            {
                for (int x = minTileX; x <= maxTileX; ++x)
                {
                    for (int y = minTileY; y <= maxTileY; ++y)
                    {
                        addCandidate(x, y);
                    }
                }
            }
            std::sort(candidates.begin(), candidates.end(), [](const Candidate& lhs, const Candidate& rhs)
            {
                return lhs.distanceSquared < rhs.distanceSquared;
            });
            for (const auto& candidate : candidates)
            {
                result.insert(candidate.tileId);
            }
            return result;
        }

        std::set<MvtTileId> visibleMvtTilesLocked(
            rocky::Application* activeApp,
            const MvtLayerConfig& config,
            int tilePadding)
        {
            if (!mvtLayerVisibleAtZoom(config, cameraState.zoom))
            {
                return {};
            }
            return visibleMvtTilesAtZoomLocked(
                activeApp,
                config,
                mvtIdealTileZoom(config, cameraState.zoom),
                tilePadding);
        }

        std::set<MvtTileId> prefetchMvtTilesLocked(
            rocky::Application* activeApp,
            const MvtLayerConfig& config)
        {
            if (!mvtLayerVisibleAtZoom(config, cameraState.zoom))
            {
                return {};
            }
            const int idealZoom = mvtIdealTileZoom(config, cameraState.zoom);
            const int prefetchZoom = std::max(config.sourceMinZoom, idealZoom - MVT_PREFETCH_ZOOM_DELTA);
            if (prefetchZoom >= idealZoom)
            {
                return {};
            }
            return visibleMvtTilesAtZoomLocked(activeApp, config, prefetchZoom, MVT_PREFETCH_PADDING_TILES);
        }

        void setMvtLayerActiveTilesLocked(MvtLayerRuntime& runtime, const std::set<MvtTileId>& nextActiveTiles)
        {
            for (const auto& tileId : runtime.activeTiles)
            {
                if (nextActiveTiles.find(tileId) != nextActiveTiles.end())
                {
                    continue;
                }
                auto loaded = runtime.loadedTiles.find(tileId);
                if (loaded == runtime.loadedTiles.end())
                {
                    continue;
                }
                loaded->second.active = false;
                loaded->second.config.rendered = false;
                const auto handle = mvtTileHandle(runtime.config.layerId, tileId);
                mvtTiles[handle] = loaded->second.config;
                removeMvtTileEntitiesLocked(handle);
            }

            for (const auto& tileId : nextActiveTiles)
            {
                auto loaded = runtime.loadedTiles.find(tileId);
                if (loaded == runtime.loadedTiles.end())
                {
                    continue;
                }
                if (loaded->second.active && runtime.activeTiles.find(tileId) != runtime.activeTiles.end())
                {
                    loaded->second.lastTouched = ++runtime.touchCounter;
                    continue;
                }
                loaded->second.active = true;
                loaded->second.lastTouched = ++runtime.touchCounter;
                loaded->second.config.rendered = true;
                const auto handle = mvtTileHandle(runtime.config.layerId, tileId);
                loaded->second.config.revision = nextMvtTileRevisionLocked(handle);
                mvtTiles[handle] = loaded->second.config;
                applyMvtTileLocked(handle, loaded->second.config);
            }
            runtime.activeTiles = nextActiveTiles;
        }

        void removeMvtLoadedTileLocked(MvtLayerRuntime& runtime, const MvtTileId& tileId)
        {
            const auto handle = mvtTileHandle(runtime.config.layerId, tileId);
            removeMvtTileEntitiesLocked(handle);
            mvtTiles.erase(handle);
            mvtTileRevisions.erase(handle);
            runtime.activeTiles.erase(tileId);
            runtime.loadedTiles.erase(tileId);
        }

        void trimMvtLayerLoadedTilesLocked(MvtLayerRuntime& runtime)
        {
            std::set<MvtTileId> retain = runtime.idealTiles;
            retain.insert(runtime.prefetchTiles.begin(), runtime.prefetchTiles.end());
            retain.insert(runtime.activeTiles.begin(), runtime.activeTiles.end());
            for (const auto& pending : runtime.pendingTiles)
            {
                retain.insert(pending.first);
            }

            while (runtime.loadedTiles.size() > MVT_DECODED_TILE_CACHE_SIZE)
            {
                auto evict = runtime.loadedTiles.end();
                for (auto itr = runtime.loadedTiles.begin(); itr != runtime.loadedTiles.end(); ++itr)
                {
                    if (itr->second.active || retain.find(itr->first) != retain.end())
                    {
                        continue;
                    }
                    if (evict == runtime.loadedTiles.end() || itr->second.lastTouched < evict->second.lastTouched)
                    {
                        evict = itr;
                    }
                }
                if (evict == runtime.loadedTiles.end())
                {
                    return;
                }
                removeMvtLoadedTileLocked(runtime, evict->first);
            }
        }

        void integrateMvtLayerLoadsLocked(MvtLayerRuntime& runtime)
        {
            for (auto itr = runtime.pendingTiles.begin(); itr != runtime.pendingTiles.end();)
            {
                if (!itr->second.future.available())
                {
                    ++itr;
                    continue;
                }

                auto result = itr->second.future.release();
                const auto tileId = itr->first;
                const bool idealAtRequest = itr->second.ideal;
                itr = runtime.pendingTiles.erase(itr);
                if (result.generation != runtime.generation)
                {
                    continue;
                }
                if (!result.success)
                {
                    if (idealAtRequest)
                    {
                        emitResourceStatusLocked(
                            "VECTOR",
                            runtime.config.layerId,
                            "FAILED",
                            "RESOURCE",
                            result.errorMessage.empty() ? "MVT tile load failed." : result.errorMessage);
                    }
                    continue;
                }

                result.config.revision = 0;
                MvtLoadedTile loaded;
                loaded.config = std::move(result.config);
                loaded.query = std::move(result.query);
                loaded.lastTouched = ++runtime.touchCounter;
                loaded.active = false;
                runtime.loadedTiles[tileId] = std::move(loaded);
                mvtTiles[mvtTileHandle(runtime.config.layerId, tileId)] = runtime.loadedTiles[tileId].config;
            }
        }

        void dispatchMvtTileLoadLocked(
            rocky::Application* activeApp,
            MvtLayerRuntime& runtime,
            const MvtTileId& tileId,
            bool ideal)
        {
            if (!activeApp || !activeApp->vsgcontext)
            {
                return;
            }
            if (runtime.loadedTiles.find(tileId) != runtime.loadedTiles.end())
            {
                return;
            }
            auto pending = runtime.pendingTiles.find(tileId);
            if (pending != runtime.pendingTiles.end())
            {
                pending->second.ideal = pending->second.ideal || ideal;
                return;
            }

            const auto layer = runtime.config;
            const auto generation = runtime.generation;
            auto io = activeApp->vsgcontext->io;
            io.maxNetworkAttempts = 1u;
            auto& jobs = activeApp->vsgcontext->io.services().jobs;
            MvtPendingTileLoad pendingLoad;
            pendingLoad.generation = generation;
            pendingLoad.ideal = ideal;
            pendingLoad.future = jobs.dispatch(
                [layer, tileId, generation, ideal, io](rocky::Cancelable& cancelable)
                {
                    return loadMvtTileJob(layer, tileId, generation, ideal, io, cancelable);
                },
                WEEJOBS_NAMESPACE::context{
                    "load mvt " + layer.layerId + " " +
                        std::to_string(tileId.z) + "/" +
                        std::to_string(tileId.x) + "/" +
                        std::to_string(tileId.y)});
            runtime.pendingTiles[tileId] = std::move(pendingLoad);
            requestFrameLocked();
        }

        void serviceMvtLayersLocked(rocky::Application* activeApp)
        {
            for (auto& entry : mvtLayers)
            {
                auto& runtime = entry.second;
                integrateMvtLayerLoadsLocked(runtime);

                runtime.idealTiles = visibleMvtTilesLocked(activeApp, runtime.config, 0);
                runtime.prefetchTiles = prefetchMvtTilesLocked(activeApp, runtime.config);
                const auto loadedIds = loadedMvtTileIds(runtime);
                const int pyramidMinZoom = std::clamp(runtime.config.sourceMinZoom, 0, MVT_MAX_COVER_ZOOM);
                const int pyramidMaxZoom = std::clamp(
                    std::max(runtime.config.sourceMinZoom, runtime.config.sourceMaxZoom),
                    pyramidMinZoom,
                    MVT_MAX_COVER_ZOOM);
                const auto renderTiles = mvtRenderTileIds(
                    runtime.idealTiles,
                    loadedIds,
                    pyramidMinZoom,
                    pyramidMaxZoom);
                setMvtLayerActiveTilesLocked(runtime, renderTiles);
                trimMvtLayerLoadedTilesLocked(runtime);

                for (const auto& tileId : runtime.idealTiles)
                {
                    dispatchMvtTileLoadLocked(activeApp, runtime, tileId, true);
                }
                for (const auto& tileId : runtime.prefetchTiles)
                {
                    if (runtime.idealTiles.find(tileId) == runtime.idealTiles.end())
                    {
                        dispatchMvtTileLoadLocked(activeApp, runtime, tileId, false);
                    }
                }
            }
        }

        void removeMvtLayerLocked(const std::string& layerId)
        {
            if (layerId.empty())
            {
                return;
            }

            auto runtime = mvtLayers.find(layerId);
            if (runtime != mvtLayers.end())
            {
                for (auto& pending : runtime->second.pendingTiles)
                {
                    pending.second.future.reset();
                }
                for (const auto& tile : runtime->second.loadedTiles)
                {
                    const auto handle = mvtTileHandle(layerId, tile.first);
                    removeMvtTileEntitiesLocked(handle);
                    mvtTiles.erase(handle);
                    mvtTileRevisions.erase(handle);
                }
                mvtLayers.erase(runtime);
            }

            int removed = 0;
            for (auto itr = mvtTiles.begin(); itr != mvtTiles.end();)
            {
                if (itr->second.layerId == layerId)
                {
                    removeMvtTileEntitiesLocked(itr->first);
                    mvtTileRevisions.erase(itr->first);
                    itr = mvtTiles.erase(itr);
                    ++removed;
                }
                else
                {
                    ++itr;
                }
            }
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "removed native MVT layer id=%s tiles=%d",
                layerId.c_str(),
                removed);
        }

        void removeModelEntityLocked(const std::string& id)
        {
            auto existing = modelEntities.find(id);
            if (existing == modelEntities.end())
            {
                return;
            }
            if (app)
            {
                auto [registryLock, registry] = app->registry.write();
                if (registry.valid(existing->second))
                {
                    registry.destroy(existing->second);
                }
            }
            modelEntities.erase(existing);
            modelLoggedRadii.erase(id);
            modelLoggedErrors.erase(id);
            requestFrameLocked();
        }

        void remove3DTilesRendererContentEntityLocked(const std::string& id)
        {
            auto existing = tiles3DEntities.find(id);
            if (existing == tiles3DEntities.end())
            {
                return;
            }
            if (app)
            {
                auto [registryLock, registry] = app->registry.write();
                if (registry.valid(existing->second))
                {
                    registry.destroy(existing->second);
                }
            }
            tiles3DEntities.erase(existing);
            tiles3DLoggedRadii.erase(id);
            tiles3DLoggedErrors.erase(id);
            requestFrameLocked();
        }

        void setModelEntityVisibleLocked(const std::string& id, bool visible)
        {
            auto existing = modelEntities.find(id);
            if (existing == modelEntities.end() || !app)
            {
                return;
            }

            auto [registryLock, registry] = app->registry.write();
            if (registry.valid(existing->second) && registry.all_of<rocky::Visibility>(existing->second))
            {
                rocky::setVisible(registry, existing->second, visible);
                requestFrameLocked();
            }
        }

        void applyModelLayerLocked(const std::string& id, const ModelLayerConfig& config)
        {
            if (!app || id.empty() || config.uri.empty())
            {
                return;
            }

            removeModelEntityLocked(id);

            auto [registryLock, registry] = app->registry.write();
            auto entity = registry.create();

            modelLoggedRadii.erase(id);
            modelLoggedErrors.erase(id);

            auto& model = registry.emplace<rocky::Model>(entity);
            model.uri = rocky::URI(config.uri);
            model.radius = 0.0f;
            model.error = std::nullopt;

            auto& transform = registry.emplace<rocky::Transform>(entity);
            transform.position = rocky::GeoPoint(
                rocky::SRS::WGS84,
                config.longitude,
                config.latitude,
                config.heightMeters);
            const auto yawRadians = glm::radians(config.yawDegrees);
            transform.localMatrix =
                glm::rotate(glm::dmat4(1.0), yawRadians, glm::dvec3(0.0, 0.0, 1.0)) *
                glm::scale(glm::dmat4(1.0), glm::dvec3(config.scale));
            transform.topocentric = true;
            transform.radius = static_cast<float>(std::max(1.0, config.scale));
            transform.horizonCulled = true;
            transform.frustumCulled = true;

            (void)registry.get_or_emplace<rocky::Visibility>(entity);
            rocky::setVisible(registry, entity, config.visible);
            modelEntities[id] = entity;

            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "applied native model id=%s uri=%s entity=%u",
                id.c_str(),
                config.uri.c_str(),
                static_cast<unsigned>(entity));
            requestFrameLocked();
        }

        std::uint64_t nextMvtTileRevisionLocked(const std::string& handle)
        {
            const auto revision = ++mvtTileRevisionCounter;
            mvtTileRevisions[handle] = revision;
            return revision;
        }

        void removeMvtTileEntitiesLocked(const std::string& handle)
        {
            auto existing = mvtEntities.find(handle);
            if (existing == mvtEntities.end())
            {
                return;
            }
            if (app)
            {
                auto [registryLock, registry] = app->registry.write();
                for (auto entity : existing->second)
                {
                    if (registry.valid(entity))
                    {
                        registry.destroy(entity);
                    }
                }
            }
            mvtEntities.erase(existing);
            requestFrameLocked();
        }

        std::vector<glm::dvec3> mvtFeatureCoordinates(
            const MvtRenderTileConfig& config,
            std::size_t featureIndex) const
        {
            if (featureIndex + 1 >= config.coordinateOffsets.size())
            {
                return {};
            }
            const int start = config.coordinateOffsets[featureIndex];
            const int end = config.coordinateOffsets[featureIndex + 1];
            if (start < 0 ||
                end < start ||
                end > static_cast<int>(config.coordinates.size()) ||
                ((end - start) % 2) != 0)
            {
                return {};
            }

            std::vector<glm::dvec3> points;
            points.reserve(static_cast<std::size_t>((end - start) / 2));
            for (int cursor = start; cursor < end; cursor += 2)
            {
                points.emplace_back(
                    config.coordinates[static_cast<std::size_t>(cursor)],
                    config.coordinates[static_cast<std::size_t>(cursor + 1)],
                    0.0);
            }
            return points;
        }

        std::vector<std::vector<glm::dvec3>> mvtPolygonRings(
            const MvtRenderTileConfig& config,
            std::size_t featureIndex,
            const std::vector<glm::dvec3>& points) const
        {
            if (points.empty() || featureIndex + 1 >= config.ringOffsets.size())
            {
                return {};
            }

            std::vector<std::vector<glm::dvec3>> rings;
            const int ringStart = config.ringOffsets[featureIndex];
            const int ringEnd = config.ringOffsets[featureIndex + 1];
            if (ringStart < 0 ||
                ringEnd < ringStart ||
                ringEnd > static_cast<int>(config.ringEnds.size()))
            {
                return {};
            }

            if (ringStart == ringEnd)
            {
                rings.push_back(points);
                return rings;
            }

            int previous = 0;
            for (int ringIndex = ringStart; ringIndex < ringEnd; ++ringIndex)
            {
                const int next = config.ringEnds[static_cast<std::size_t>(ringIndex)];
                if (next <= previous || next > static_cast<int>(points.size()))
                {
                    return {};
                }
                rings.emplace_back(
                    points.begin() + previous,
                    points.begin() + next);
                previous = next;
            }
            return rings;
        }

        int colorWithStyleOpacity(int androidArgb, float opacity) const
        {
            const auto raw = static_cast<std::uint32_t>(androidArgb);
            const auto alpha = static_cast<unsigned>(
                std::round(static_cast<float>((raw >> 24u) & 0xffu) * std::clamp(opacity, 0.0f, 1.0f)));
            return static_cast<int>((raw & 0x00ffffffu) | ((alpha & 0xffu) << 24u));
        }

        void addMvtLineEntityLocked(
            const std::string& handle,
            const std::vector<std::vector<glm::dvec3>>& lines,
            const MvtRenderStyleConfig& style)
        {
            if (!app || lines.empty())
            {
                return;
            }

            std::vector<rocky::Feature> features;
            features.reserve(lines.size());
            for (const auto& coordinates : lines)
            {
                if (coordinates.size() < 2)
                {
                    continue;
                }
                rocky::Feature line;
                line.geometry.type = rocky::Geometry::Type::LineString;
                line.interpolation = rocky::GeodeticInterpolation::RhumbLine;
                line.srs = rocky::SRS::WGS84;
                line.geometry.points = coordinates;
                features.emplace_back(std::move(line));
            }
            if (features.empty())
            {
                return;
            }

            auto [registryLock, registry] = app->registry.write();
            auto entity = registry.create();
            auto& lineStyle = registry.emplace<rocky::LineStyle>(entity);
            lineStyle.color = rocky::Color(colorFromAndroidArgb(style.color), std::clamp(style.opacity, 0.0f, 1.0f));
            lineStyle.width = std::max(1.0f, style.widthPixels);
            lineStyle.depthOffset = 3000.0f;
            lineStyle.resolution = 100000.0f;
            lineStyle.transparencyBin = true;
            auto& lineGeom = registry.emplace<rocky::LineGeometry>(entity);
            rocky::FeatureBuilder builder;
            builder.buildLineGeometry(features, lineStyle, lineGeom);
            registry.emplace<rocky::Line>(entity, lineGeom, lineStyle);
            mvtEntities[handle].push_back(entity);
        }

        void addMvtFillEntityLocked(
            const std::string& handle,
            const std::vector<std::vector<glm::dvec3>>& rings,
            const MvtRenderStyleConfig& style)
        {
            if (!app || rings.empty() || rings.front().size() < 3)
            {
                return;
            }

            rocky::Feature polygon;
            polygon.geometry.type = rocky::Geometry::Type::Polygon;
            polygon.srs = rocky::SRS::WGS84;
            polygon.geometry.points = rings.front();
            for (std::size_t i = 1; i < rings.size(); ++i)
            {
                if (rings[i].size() >= 3)
                {
                    rocky::Geometry hole(rocky::Geometry::Type::LineString, rings[i]);
                    polygon.geometry.parts.emplace_back(std::move(hole));
                }
            }

            auto [registryLock, registry] = app->registry.write();
            auto entity = registry.create();
            auto& meshStyle = registry.emplace<rocky::MeshStyle>(entity);
            auto color = colorFromAndroidArgb(style.color);
            meshStyle.color = rocky::Color(color, std::clamp(style.opacity, 0.0f, 1.0f));
            meshStyle.depthOffset = 2500.0f;
            meshStyle.writeDepth = false;
            meshStyle.drawBackfaces = true;
            meshStyle.twoPassAlpha = true;
            meshStyle.transparencyBin = true;
            auto& meshGeom = registry.emplace<rocky::MeshGeometry>(entity);
            rocky::FeatureBuilder builder;
            builder.buildMeshGeometry({polygon}, meshStyle, meshGeom);
            registry.emplace<rocky::Mesh>(entity, meshGeom, meshStyle);
            mvtEntities[handle].push_back(entity);
        }

        void addMvtPointEntityLocked(
            const std::string& handle,
            const glm::dvec3& point,
            const MvtRenderStyleConfig& style,
            const std::string& text)
        {
            if (!app)
            {
                return;
            }

            auto [registryLock, registry] = app->registry.write();
            auto entity = registry.create();
            auto& labelStyle = registry.emplace<rocky::LabelStyle>(entity);
            labelStyle.fontName = resourcePath.empty()
                ? std::string("/system/fonts/DroidSans.ttf")
                : resourcePath + "/fonts/NotoSansSC-VF.ttf";
            labelStyle.textSize = std::clamp(style.textSizeSp, 8.0f, 64.0f);
            labelStyle.textColor = colorFromAndroidArgb(colorWithStyleOpacity(style.color, style.opacity));
            labelStyle.textOutlineColor = colorFromAndroidArgb(static_cast<int>(0xccffffffu));
            labelStyle.textOutlineSize = 2.0f;
            labelStyle.textPivot = {0.5f, 0.0f};
            labelStyle.textOffset = {0.0f, 18.0f};
            labelStyle.backgroundColor = rocky::StockColor::Transparent;
            labelStyle.borderSize = 0.0f;
            labelStyle.padding = {2.0f, 2.0f};
            if (style.kind == "CIRCLE")
            {
                labelStyle.icon = createCircleIcon(
                    colorWithStyleOpacity(style.color, style.opacity),
                    style.radiusPixels);
                labelStyle.iconSizePixels = std::max(2.0f, style.radiusPixels * 2.0f);
                labelStyle.iconPivot = {0.5f, 0.5f};
            }

            registry.emplace<rocky::Label>(entity, text, labelStyle);
            auto& transform = registry.emplace<rocky::Transform>(entity);
            transform.position = rocky::GeoPoint(rocky::SRS::WGS84, point.x, point.y, 1000.0);
            transform.radius = 1000.0;
            transform.horizonCulled = true;
            transform.frustumCulled = true;
            mvtEntities[handle].push_back(entity);
        }

        void applyMvtTileLocked(
            const std::string& handle,
            const MvtRenderTileConfig& config)
        {
            if (!app || handle.empty())
            {
                return;
            }
            removeMvtTileEntitiesLocked(handle);
            if (!config.rendered || !config.style.visible)
            {
                return;
            }

            std::vector<std::vector<glm::dvec3>> lineFeatures;
            for (std::size_t index = 0; index < config.featureIds.size(); ++index)
            {
                const auto points = mvtFeatureCoordinates(config, index);
                const auto geometryType = config.geometryTypes[index];
                if (config.style.kind == "LINE" && geometryType == 1)
                {
                    if (points.size() >= 2)
                    {
                        lineFeatures.push_back(points);
                    }
                }
                else if (config.style.kind == "FILL" && geometryType == 2)
                {
                    addMvtFillEntityLocked(handle, mvtPolygonRings(config, index, points), config.style);
                }
                else if (config.style.kind == "CIRCLE" && geometryType == 0 && !points.empty())
                {
                    addMvtPointEntityLocked(handle, points.front(), config.style, "");
                }
                else if (config.style.kind == "SYMBOL" && geometryType == 0 && !points.empty())
                {
                    addMvtPointEntityLocked(handle, points.front(), config.style, config.featureIds[index]);
                }
            }
            if (!lineFeatures.empty())
            {
                addMvtLineEntityLocked(handle, lineFeatures, config.style);
            }

            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "applied MVT render tile handle=%s entities=%zu",
                handle.c_str(),
                mvtEntities[handle].size());
            requestFrameLocked();
        }

        void apply3DTilesRendererContentLocked(
            const std::string& id,
            const Tiles3DRendererContentConfig& config)
        {
            if (!app || id.empty() || config.renderUri.empty())
            {
                return;
            }

            remove3DTilesRendererContentEntityLocked(id);

            auto [registryLock, registry] = app->registry.write();
            auto entity = registry.create();

            tiles3DLoggedRadii.erase(id);
            tiles3DLoggedErrors.erase(id);

            auto& model = registry.emplace<rocky::Model>(entity);
            model.uri = rocky::URI(config.renderUri);
            model.radius = 0.0f;
            model.error = std::nullopt;

            auto& transform = registry.emplace<rocky::Transform>(entity);
            transform.localMatrix = matrixFromColumnMajorArray(config.transformMatrix);
            transform.topocentric = false;
            transform.horizonCulled = false;
            transform.frustumCulled = false;
            transform.radius = 0.0;

            if (config.transformKind == "ECEF")
            {
                transform.position = rocky::GeoPoint(
                    rocky::SRS::ECEF,
                    config.ecefX,
                    config.ecefY,
                    config.ecefZ);
            }
            else
            {
                transform.position = rocky::GeoPoint(rocky::SRS::ECEF, 0.0, 0.0, 0.0);
            }

            (void)registry.get_or_emplace<rocky::Visibility>(entity);
            rocky::setVisible(registry, entity, config.visible);
            tiles3DEntities[id] = entity;

            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "applied native 3D Tiles content id=%s uri=%s entity=%u transform=%s",
                id.c_str(),
                config.renderUri.c_str(),
                static_cast<unsigned>(entity),
                config.transformKind.c_str());
            requestFrameLocked();
        }

        void logModelLayerStatusesLocked(rocky::Application* activeApp)
        {
            if (!activeApp)
            {
                return;
            }

            auto [registryLock, registry] = activeApp->registry.read();
            for (const auto& entry : modelEntities)
            {
                const auto& id = entry.first;
                const auto entity = entry.second;
                if (!registry.valid(entity) || !registry.all_of<rocky::Model>(entity))
                {
                    continue;
                }

                const auto& model = registry.get<rocky::Model>(entity);
                if (model.error)
                {
                    const auto message = model.error->string();
                    auto errorItr = modelLoggedErrors.find(id);
                    if (errorItr == modelLoggedErrors.end() || errorItr->second != message)
                    {
                        modelLoggedErrors[id] = message;
                        __android_log_print(
                            ANDROID_LOG_ERROR,
                            TAG,
                            "model load error id=%s uri=%s error=%s",
                            id.c_str(),
                            model.uri.full().c_str(),
                            message.c_str());
                        emitResourceStatusLocked("MODEL", id, "FAILED", "NATIVE_RENDERER", message);
                    }
                    continue;
                }

                if (model.radius > 0.0f)
                {
                    auto radiusItr = modelLoggedRadii.find(id);
                    if (radiusItr == modelLoggedRadii.end() || radiusItr->second != model.radius)
                    {
                        modelLoggedRadii[id] = model.radius;
                        __android_log_print(
                            ANDROID_LOG_INFO,
                            TAG,
                            "model loaded id=%s uri=%s radius=%.2f",
                            id.c_str(),
                            model.uri.full().c_str(),
                            model.radius);
                        emitResourceStatusLocked("MODEL", id, "LOADED");
                    }
                }
            }

            for (const auto& entry : tiles3DEntities)
            {
                const auto& id = entry.first;
                const auto& entity = entry.second;
                if (!registry.valid(entity) || !registry.all_of<rocky::Model>(entity))
                {
                    continue;
                }

                const auto& model = registry.get<rocky::Model>(entity);
                if (model.error)
                {
                    const auto message = model.error->string();
                    auto errorItr = tiles3DLoggedErrors.find(id);
                    if (errorItr == tiles3DLoggedErrors.end() || errorItr->second != message)
                    {
                        tiles3DLoggedErrors[id] = message;
                        __android_log_print(
                            ANDROID_LOG_ERROR,
                            TAG,
                            "3D Tiles model load error id=%s uri=%s error=%s",
                            id.c_str(),
                            model.uri.full().c_str(),
                            message.c_str());
                    }
                }

                if (model.radius > 0.0f)
                {
                    auto radiusItr = tiles3DLoggedRadii.find(id);
                    if (radiusItr == tiles3DLoggedRadii.end() || radiusItr->second != model.radius)
                    {
                        tiles3DLoggedRadii[id] = model.radius;
                        __android_log_print(
                            ANDROID_LOG_INFO,
                            TAG,
                            "3D Tiles model loaded id=%s uri=%s radius=%.2f",
                            id.c_str(),
                            model.uri.full().c_str(),
                            model.radius);
                    }
                }
            }
        }

        void clearDrawEntitiesLocked()
        {
            if (app)
            {
                auto [registryLock, registry] = app->registry.write();
                for (const auto& entry : drawEntities)
                {
                    for (auto entity : entry.second)
                    {
                        if (registry.valid(entity))
                        {
                            registry.destroy(entity);
                        }
                    }
                }
            }
            drawEntities.clear();
            requestFrameLocked();
        }

        void removeDrawAnnotationLocked(const std::string& id)
        {
            auto existing = drawEntities.find(id);
            if (existing == drawEntities.end())
            {
                return;
            }
            if (app)
            {
                auto [registryLock, registry] = app->registry.write();
                for (auto entity : existing->second)
                {
                    if (registry.valid(entity))
                    {
                        registry.destroy(entity);
                    }
                }
            }
            drawEntities.erase(existing);
            requestFrameLocked();
        }

        void addDrawLineEntityLocked(
            const std::string& id,
            const std::vector<glm::dvec3>& coordinates,
            int lineColor,
            float lineWidth,
            float depthOffset)
        {
            if (!app || coordinates.size() < 2)
            {
                return;
            }

            rocky::Feature line;
            line.geometry.type = rocky::Geometry::Type::LineString;
            line.interpolation = rocky::GeodeticInterpolation::RhumbLine;
            line.srs = rocky::SRS::WGS84;
            line.geometry.points = coordinates;

            auto [registryLock, registry] = app->registry.write();
            auto entity = registry.create();
            auto& lineStyle = registry.emplace<rocky::LineStyle>(entity);
            lineStyle.color = colorFromAndroidArgb(lineColor);
            lineStyle.width = std::max(1.0f, lineWidth);
            lineStyle.depthOffset = depthOffset;
            lineStyle.resolution = 100000.0f;
            lineStyle.transparencyBin = true;
            auto& lineGeom = registry.emplace<rocky::LineGeometry>(entity);
            rocky::FeatureBuilder builder;
            builder.buildLineGeometry({line}, lineStyle, lineGeom);
            registry.emplace<rocky::Line>(entity, lineGeom, lineStyle);
            drawEntities[id].push_back(entity);
        }

        void syncLabelAnnotationsLocked()
        {
            if (!app)
            {
                return;
            }
            labelEntities.clear();
            for (const auto& entry : labelAnnotations)
            {
                applyLabelAnnotationLocked(entry.second);
            }
        }

        void applyLabelAnnotationLocked(const LabelAnnotationConfig& config)
        {
            if (!app || config.id.empty())
            {
                return;
            }

            auto [registryLock, registry] = app->registry.write();
            auto existing = labelEntities.find(config.id);
            if (existing != labelEntities.end() && registry.valid(existing->second))
            {
                registry.destroy(existing->second);
            }

            auto entity = registry.create();
            auto& style = registry.emplace<rocky::LabelStyle>(entity);
            style.fontName = resourcePath.empty()
                ? std::string("/system/fonts/DroidSans.ttf")
                : resourcePath + "/fonts/NotoSansSC-VF.ttf";
            style.textSize = std::clamp(config.textSize, 8.0f, 64.0f);
            style.textColor = colorFromAndroidArgb(config.textColor);
            style.textOutlineColor = colorFromAndroidArgb(config.textHaloColor);
            style.textOutlineSize = std::clamp(config.textHaloWidth, 0.0f, 8.0f);
            style.textPivot = {0.5f, 0.0f};
            style.textOffset = {config.textOffsetX, config.textOffsetY};
            style.backgroundColor = rocky::StockColor::Transparent;
            style.borderSize = 0.0f;
            style.padding = {2.0f, 2.0f};

            if (config.hasIcon)
            {
                style.icon = createCircleIcon(config.iconColor, config.iconRadius);
                style.iconSizePixels = std::max(2.0f, config.iconRadius * 2.0f);
                style.iconPivot = {0.5f, 0.5f};
            }

            registry.emplace<rocky::Label>(entity, config.text, style);
            auto& transform = registry.emplace<rocky::Transform>(entity);
            transform.position = rocky::GeoPoint(rocky::SRS::WGS84, config.longitude, config.latitude, 1000.0);
            transform.radius = 1000.0;
            transform.horizonCulled = true;
            transform.frustumCulled = true;
            labelEntities[config.id] = entity;

            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "added native label id=%s lon=%.6f lat=%.6f text=%s overlap=%d",
                config.id.c_str(),
                config.longitude,
                config.latitude,
                config.text.c_str(),
                config.allowOverlap ? 1 : 0);

            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void clearLocationEntitiesLocked()
        {
            if (app)
            {
                auto [registryLock, registry] = app->registry.write();
                for (const auto& entity : locationEntities)
                {
                    if (registry.valid(entity))
                    {
                        registry.destroy(entity);
                    }
                }
            }
            locationEntities.clear();
            if (app && app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void syncLocationIndicatorLocked()
        {
            clearLocationEntitiesLocked();
            if (!app || !locationIndicator || !locationIndicator->enabled)
            {
                return;
            }

            const auto config = *locationIndicator;
            auto [registryLock, registry] = app->registry.write();

            if (config.showAccuracyRing && config.accuracyRadiusPixels > 0.0f)
            {
                auto accuracyEntity = registry.create();
                auto& accuracyStyle = registry.emplace<rocky::LabelStyle>(accuracyEntity);
                accuracyStyle.fontName = resourcePath.empty()
                    ? std::string("/system/fonts/DroidSans.ttf")
                    : resourcePath + "/fonts/NotoSansSC-VF.ttf";
                accuracyStyle.icon = createAccuracyRingIcon(config.accuracyRadiusPixels);
                accuracyStyle.iconSizePixels = std::max(8.0f, config.accuracyRadiusPixels * 2.0f + 4.0f);
                accuracyStyle.iconPivot = {0.5f, 0.5f};
                accuracyStyle.textSize = 1.0f;
                accuracyStyle.textColor = rocky::StockColor::Transparent;
                accuracyStyle.backgroundColor = rocky::StockColor::Transparent;
                accuracyStyle.borderSize = 0.0f;
                accuracyStyle.padding = {0.0f, 0.0f};
                registry.emplace<rocky::Label>(accuracyEntity, "", accuracyStyle);
                auto& transform = registry.emplace<rocky::Transform>(accuracyEntity);
                transform.position = rocky::GeoPoint(rocky::SRS::WGS84, config.longitude, config.latitude, 0.0);
                transform.radius = 1000.0;
                transform.horizonCulled = true;
                transform.frustumCulled = true;
                locationEntities.emplace_back(accuracyEntity);
            }

            auto puckEntity = registry.create();
            auto& puckStyle = registry.emplace<rocky::LabelStyle>(puckEntity);
            puckStyle.fontName = resourcePath.empty()
                ? std::string("/system/fonts/DroidSans.ttf")
                : resourcePath + "/fonts/NotoSansSC-VF.ttf";
            puckStyle.icon = createLocationPuckIcon(static_cast<float>(config.bearingDegrees));
            puckStyle.iconSizePixels = 32.0f;
            puckStyle.iconPivot = {0.5f, 0.5f};
            puckStyle.textSize = 1.0f;
            puckStyle.textColor = rocky::StockColor::Transparent;
            puckStyle.backgroundColor = rocky::StockColor::Transparent;
            puckStyle.borderSize = 0.0f;
            puckStyle.padding = {0.0f, 0.0f};
            registry.emplace<rocky::Label>(puckEntity, "", puckStyle);
            auto& puckTransform = registry.emplace<rocky::Transform>(puckEntity);
            puckTransform.position = rocky::GeoPoint(rocky::SRS::WGS84, config.longitude, config.latitude, 0.0);
            puckTransform.radius = 1000.0;
            puckTransform.horizonCulled = true;
            puckTransform.frustumCulled = true;
            locationEntities.emplace_back(puckEntity);

            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "updated location indicator lon=%.6f lat=%.6f accuracy=%.1f radiusPx=%.1f bearing=%.1f",
                config.longitude,
                config.latitude,
                config.accuracyMeters,
                config.accuracyRadiusPixels,
                config.bearingDegrees);

            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
        }

        void addRasterLayerLocked(const std::string& id, const RasterLayerConfig& config)
        {
            if (!config.visible)
            {
                __android_log_print(ANDROID_LOG_INFO, TAG, "skip raster layer id=%s visible=0", id.c_str());
                return;
            }
            if (!app)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "skip raster layer id=%s app=null", id.c_str());
                return;
            }
            if (!app->mapNode)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "skip raster layer id=%s mapNode=null", id.c_str());
                return;
            }
            if (!app->mapNode->map)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "skip raster layer id=%s map=null", id.c_str());
                return;
            }

            removeLayerFromMapLocked(id, false);

            auto layer = rocky::TMSImageLayer::create();
            layer->name = id;
            rocky::URI::Context uriContext;
            uriContext.bypassDiskCache = config.disableNativeDiskCache;
            for (const auto& header : config.headers)
            {
                uriContext.headers.emplace_back(header.first, header.second);
            }
            layer->uri = rocky::URI(config.templateUrl, uriContext);
            const bool xyzSource = isTemplateTileUrl(config.templateUrl);
            if (xyzSource)
            {
                layer->profile = rocky::Profile("spherical-mercator");
                layer->format = "png";
                layer->invertY = config.scheme == "TMS";
            }
            layer->tileSize = static_cast<unsigned>(std::max(1, config.tileSize));
            layer->minLevel = static_cast<unsigned>(std::max(0, config.minZoom));
            layer->maxLevel = static_cast<unsigned>(std::max(config.minZoom, config.maxZoom));
            layer->maxDataLevel = layer->maxLevel;
            layer->opacity = config.opacity;
            layer->saturation = config.saturation;
            layer->contrast = config.contrast;
            if (app->vsgcontext)
            {
                auto openResult = layer->open(app->vsgcontext->io);
                if (openResult.failed())
                {
                    const auto message = openResult.error().string();
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        TAG,
                        "raster layer open failed id=%s error=%s",
                        id.c_str(),
                        message.c_str());
                    emitResourceStatusLocked("RASTER", id, "FAILED", "NATIVE_RENDERER", message);
                    return;
                }
            }
            app->mapNode->map->add(layer);
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
            emitResourceStatusLocked("RASTER", id, "LOADED");
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "added %s raster layer id=%s min=%d max=%d tileSize=%d opacity=%.2f saturation=%.2f contrast=%.2f",
                config.scheme.c_str(),
                id.c_str(),
                config.minZoom,
                config.maxZoom,
                config.tileSize,
                config.opacity,
                config.saturation,
                config.contrast);
        }

        void removeLayerFromMapLocked(const std::string& id, bool logRemoval = true)
        {
            if (!app || !app->mapNode || !app->mapNode->map)
            {
                return;
            }

            auto layers = app->mapNode->map->layers();
            const auto before = layers.size();
            layers.erase(
                std::remove_if(layers.begin(), layers.end(), [&](const rocky::Layer::Ptr& layer)
                {
                    return layer && layer->name == id;
                }),
                layers.end());

            if (layers.size() != before)
            {
                app->mapNode->map->setLayers(std::move(layers));
                if (logRemoval)
                {
                    __android_log_print(ANDROID_LOG_INFO, TAG, "removed layer id=%s", id.c_str());
                }
            }
        }

        void moveLayerToTopLocked(const std::string& id)
        {
            if (!app || !app->mapNode || !app->mapNode->map)
            {
                return;
            }

            auto layers = app->mapNode->map->layers();
            auto itr = std::find_if(layers.begin(), layers.end(), [&](const rocky::Layer::Ptr& layer)
            {
                return layer && layer->name == id;
            });
            if (itr == layers.end())
            {
                return;
            }

            auto layer = *itr;
            layers.erase(itr);
            layers.emplace_back(layer);
            app->mapNode->map->setLayers(std::move(layers));
            __android_log_print(ANDROID_LOG_INFO, TAG, "moved layer to top id=%s", id.c_str());
        }

        void applyRasterLayerStyleLocked(const std::string& id, const RasterLayerConfig& config)
        {
            if (!app || !app->mapNode || !app->mapNode->map)
            {
                return;
            }

            auto layer = app->mapNode->map->layer<rocky::ImageLayer>([&](auto imageLayer)
            {
                return imageLayer->name == id;
            });
            if (!layer)
            {
                return;
            }

            layer->opacity = config.visible ? config.opacity : 0.0f;
            layer->saturation = config.saturation;
            layer->contrast = config.contrast;
            layer->dirty();
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "updated raster style id=%s visible=%d opacity=%.2f saturation=%.2f contrast=%.2f",
                id.c_str(),
                config.visible ? 1 : 0,
                layer->opacity.value(),
                config.saturation,
                config.contrast);
        }

        void addElevationLayerLocked(const std::string& id, const ElevationLayerConfig& config)
        {
            if (!config.visible)
            {
                __android_log_print(ANDROID_LOG_INFO, TAG, "skip elevation layer id=%s visible=0", id.c_str());
                return;
            }
            if (!app || !app->mapNode || !app->mapNode->map)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "skip elevation layer id=%s map unavailable", id.c_str());
                return;
            }
            if (app->mapNode->map->layer<rocky::ElevationLayer>([&](auto layer) { return layer->name == id; }))
            {
                __android_log_print(ANDROID_LOG_INFO, TAG, "skip duplicate elevation layer id=%s", id.c_str());
                return;
            }

            auto layer = rocky::TMSElevationLayer::create();
            layer->name = id;
            rocky::URI::Context uriContext;
            uriContext.bypassDiskCache = config.disableNativeDiskCache;
            for (const auto& header : config.headers)
            {
                uriContext.headers.emplace_back(header.first, header.second);
            }
            layer->uri = rocky::URI(config.templateUrl, uriContext);
            const bool xyzSource = isTemplateTileUrl(config.templateUrl);
            if (xyzSource)
            {
                layer->profile = rocky::Profile("spherical-mercator");
                layer->format = "png";
                layer->invertY = false;
            }
            layer->encoding = rocky::ElevationLayer::Encoding::MapboxRGB;
            layer->tileSize = 512u;
            layer->minLevel = static_cast<unsigned>(std::max(0, config.minZoom));
            layer->maxLevel = 23u;
            layer->maxDataLevel = static_cast<unsigned>(std::max(config.minZoom, config.maxZoom));
            if (app->vsgcontext)
            {
                auto openResult = layer->open(app->vsgcontext->io);
                if (openResult.failed())
                {
                    const auto message = openResult.error().string();
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        TAG,
                        "elevation layer open failed id=%s error=%s",
                        id.c_str(),
                        message.c_str());
                    emitResourceStatusLocked("DEM", id, "FAILED", "NATIVE_RENDERER", message);
                    return;
                }
            }
            app->mapNode->map->add(layer);
            if (app->vsgcontext)
            {
                app->vsgcontext->requestFrame();
            }
            emitResourceStatusLocked("DEM", id, "LOADED");
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "added %s elevation layer id=%s min=%d max=%d url=%s",
                xyzSource ? "XYZ" : "TMS",
                id.c_str(),
                config.minZoom,
                config.maxZoom,
                config.templateUrl.c_str());
        }

        void logRenderState(rocky::Application* activeApp)
        {
            if (!activeApp || !activeApp->mapNode)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "render state unavailable app/mapNode missing");
                return;
            }

            const auto windowCount = activeApp->viewer ? activeApp->viewer->windows().size() : 0;
            const auto viewCount = activeApp->display.windows().empty() ? 0 : activeApp->display.window(0).views().size();
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "render state windows=%zu views=%zu mapRevision=%llu",
                windowCount,
                viewCount,
                static_cast<unsigned long long>(activeApp->mapNode->map->revision()));

            activeApp->mapNode->map->each<rocky::Layer>([](auto layer)
            {
                if (layer->status().ok())
                {
                    __android_log_print(
                        ANDROID_LOG_INFO,
                        TAG,
                        "layer status name=%s type=%s open=%d ok=1",
                        layer->name.c_str(),
                        layer->getLayerTypeName().c_str(),
                        layer->isOpen() ? 1 : 0);
                }
                else
                {
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        TAG,
                        "layer status name=%s type=%s open=%d error=%s",
                        layer->name.c_str(),
                        layer->getLayerTypeName().c_str(),
                        layer->isOpen() ? 1 : 0,
                        layer->status().error().string().c_str());
                }
            });

            auto terrain = activeApp->mapNode->terrainNode;
            if (!terrain)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "terrain missing");
                return;
            }

            if (terrain->status.ok())
            {
                const auto stats = terrain->stats();
                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "terrain status ok residentTiles=%zu geometryPool=%zu",
                    stats.numResidentTiles,
                    stats.geometryPoolSize);
            }
            else
            {
                __android_log_print(
                    ANDROID_LOG_WARN,
                    TAG,
                    "terrain status error=%s",
                    terrain->status.error().string().c_str());
            }
        }

        double cameraRangeMeters() const
        {
            return cameraRangeMeters(cameraState.zoom, cameraState.latitude, effectiveRenderHeight());
        }

        static double cameraRangeMeters(double zoom, double latitude, int viewportHeight)
        {
            const double clampedZoom = clampZoom(zoom);
            const double latitudeScale = std::max(std::cos(latitude * PI / 180.0), 0.05);
            const double metersPerPixel =
                EARTH_CIRCUMFERENCE_METERS * latitudeScale /
                (WEB_MERCATOR_TILE_SIZE * std::pow(2.0, clampedZoom));
            const double visibleGroundMeters = metersPerPixel * static_cast<double>(std::max(viewportHeight, 1));
            const double halfFovyRadians = (VECTORRA_CAMERA_FOVY_DEGREES * PI / 180.0) * 0.5;

            return std::clamp(
                visibleGroundMeters / (2.0 * std::tan(halfFovyRadians)),
                MIN_CAMERA_RANGE_METERS,
                MAX_CAMERA_RANGE_METERS);
        }

        static double cameraZoomForRange(double rangeMeters, double latitude, int viewportHeight)
        {
            if (!std::isfinite(rangeMeters) || rangeMeters <= 0.0)
            {
                return MIN_CAMERA_ZOOM;
            }
            const double halfFovyRadians = (VECTORRA_CAMERA_FOVY_DEGREES * PI / 180.0) * 0.5;
            const double visibleGroundMeters =
                rangeMeters * 2.0 * std::tan(halfFovyRadians);
            const double metersPerPixel =
                visibleGroundMeters / static_cast<double>(std::max(viewportHeight, 1));
            if (!std::isfinite(metersPerPixel) || metersPerPixel <= 0.0)
            {
                return MIN_CAMERA_ZOOM;
            }
            const double latitudeScale = std::max(std::cos(latitude * PI / 180.0), 0.05);
            const double denominator = WEB_MERCATOR_TILE_SIZE * metersPerPixel;
            if (denominator <= 0.0)
            {
                return MIN_CAMERA_ZOOM;
            }
            return clampZoom(std::log2(EARTH_CIRCUMFERENCE_METERS * latitudeScale / denominator));
        }

        int effectiveRenderWidth() const
        {
            const int maxSurfaceExtent = std::max(surfaceWidth, surfaceHeight);
            if (maxSurfaceExtent <= MAX_ANDROID_RENDER_EXTENT)
            {
                return std::max(surfaceWidth, 1);
            }

            const double scale = static_cast<double>(MAX_ANDROID_RENDER_EXTENT) /
                static_cast<double>(maxSurfaceExtent);
            return std::max(1, static_cast<int>(surfaceWidth * scale));
        }

        int effectiveRenderHeight() const
        {
            const int maxSurfaceExtent = std::max(surfaceWidth, surfaceHeight);
            if (maxSurfaceExtent <= MAX_ANDROID_RENDER_EXTENT)
            {
                return std::max(surfaceHeight, 1);
            }

            const double scale = static_cast<double>(MAX_ANDROID_RENDER_EXTENT) /
                static_cast<double>(maxSurfaceExtent);
            return std::max(1, static_cast<int>(surfaceHeight * scale));
        }

        void updateProjectionSnapshotLocked(rocky::Application* activeApp)
        {
            projectionSnapshot.ready = false;
            if (!activeApp ||
                activeApp->display.windows().empty() ||
                !activeApp->mapNode ||
                !activeApp->mapNode->terrainNode)
            {
                return;
            }

            auto& window = activeApp->display.window(0);
            if (window.views().empty())
            {
                return;
            }

            auto& view = window.view(0);
            if (!view.vsgView ||
                !view.vsgView->camera ||
                !view.vsgView->camera->viewMatrix ||
                !view.vsgView->camera->projectionMatrix)
            {
                return;
            }

            const auto viewport = view.vsgView->camera->getViewport();
            projectionSnapshot.viewMatrix = view.vsgView->camera->viewMatrix->transform();
            projectionSnapshot.projectionMatrix = view.vsgView->camera->projectionMatrix->transform();
            projectionSnapshot.viewportX = static_cast<double>(viewport.x);
            projectionSnapshot.viewportY = static_cast<double>(viewport.y);
            projectionSnapshot.viewportWidth = static_cast<double>(std::max(viewport.width, 1.0f));
            projectionSnapshot.viewportHeight = static_cast<double>(std::max(viewport.height, 1.0f));
            projectionSnapshot.viewWidth = std::max(surfaceWidth, 1);
            projectionSnapshot.viewHeight = std::max(surfaceHeight, 1);
            projectionSnapshot.renderWidth = effectiveRenderWidth();
            projectionSnapshot.renderHeight = effectiveRenderHeight();
            projectionSnapshot.renderingSRS = activeApp->mapNode->terrainNode->renderingSRS;
            projectionSnapshot.ready = projectionSnapshot.renderingSRS.valid();
        }

        ScreenToCoordinateResult screenToCoordinateOnUpdateLocked(
            rocky::Application* activeApp,
            int32_t renderX,
            int32_t renderY)
        {
            if (!activeApp ||
                activeApp->display.windows().empty() ||
                !activeApp->mapNode ||
                !activeApp->mapNode->terrainNode)
            {
                return {};
            }

            auto& window = activeApp->display.window(0);
            if (window.views().empty())
            {
                return {};
            }

            auto& view = window.view(0);
            if (!view.vsgView || !view.vsgView->camera)
            {
                return {};
            }

            auto* terrain = activeApp->mapNode->terrainNode.get();
            vsg::LineSegmentIntersector intersector(*view.vsgView->camera, renderX, renderY);
            terrain->accept(intersector);
            if (intersector.intersections.empty())
            {
                return {};
            }

            const auto closest = std::min_element(
                intersector.intersections.begin(),
                intersector.intersections.end(),
                [](const auto& lhs, const auto& rhs)
                {
                    return lhs->ratio < rhs->ratio;
                });

            auto wgs84 = rocky::GeoPoint(
                terrain->renderingSRS,
                closest->get()->worldIntersection).transform(rocky::SRS::WGS84);
            if (!wgs84 ||
                !std::isfinite(wgs84.x) ||
                !std::isfinite(wgs84.y))
            {
                return {};
            }

            return ScreenToCoordinateResult{
                true,
                wgs84.x,
                wgs84.y};
        }

        static vsg::ref_ptr<rocky::MapManipulator> manipulatorFor(rocky::Application* activeApp)
        {
            if (!activeApp || activeApp->display.windows().empty())
            {
                return {};
            }

            auto& window = activeApp->display.window(0);
            if (window.views().empty())
            {
                return {};
            }

            auto& view = window.view(0);
            if (!view.vsgView)
            {
                return {};
            }
            return rocky::MapManipulator::get(view.vsgView);
        }

        static double wrapLongitude(double longitude)
        {
            if (!std::isfinite(longitude))
            {
                return 0.0;
            }
            double result = std::fmod(longitude + 180.0, 360.0);
            if (result < 0.0)
            {
                result += 360.0;
            }
            return result - 180.0;
        }

        static double clampLatitude(double latitude)
        {
            return std::isfinite(latitude) ? std::clamp(latitude, MIN_CAMERA_LATITUDE, MAX_CAMERA_LATITUDE) : 0.0;
        }

        static double clampZoom(double zoom)
        {
            return std::isfinite(zoom) ? std::clamp(zoom, MIN_CAMERA_ZOOM, MAX_CAMERA_ZOOM) : MIN_CAMERA_ZOOM;
        }

        static double clampPitch(double pitch)
        {
            return std::isfinite(pitch) ? std::clamp(pitch, MIN_CAMERA_PITCH, MAX_CAMERA_PITCH) : MIN_CAMERA_PITCH;
        }

        static double normalizeBearing(double bearing)
        {
            if (!std::isfinite(bearing))
            {
                return 0.0;
            }
            double result = std::fmod(bearing, 360.0);
            if (result < 0.0)
            {
                result += 360.0;
            }
            return result;
        }

        NativeCameraState currentCameraState() const
        {
            return cameraState;
        }

        void setCachedCameraStateLocked(const NativeCameraState& state)
        {
            cameraState.longitude = wrapLongitude(state.longitude);
            cameraState.latitude = clampLatitude(state.latitude);
            cameraState.zoom = clampZoom(state.zoom);
            cameraState.pitch = clampPitch(state.pitch);
            cameraState.bearing = normalizeBearing(state.bearing);
            cameraState.targetHeightMeters = std::isfinite(state.targetHeightMeters) ? state.targetHeightMeters : 0.0;
        }

        rocky::Viewpoint viewpointForCameraState(const NativeCameraState& state, int viewportHeight) const
        {
            rocky::Viewpoint viewpoint;
            viewpoint.point = rocky::GeoPoint(
                rocky::SRS::WGS84,
                wrapLongitude(state.longitude),
                clampLatitude(state.latitude),
                std::isfinite(state.targetHeightMeters) ? state.targetHeightMeters : 0.0);
            viewpoint.range = rocky::Distance(cameraRangeMeters(state.zoom, state.latitude, viewportHeight), rocky::Units::METERS);
            viewpoint.heading = rocky::Angle(normalizeBearing(state.bearing), rocky::Units::DEGREES);
            viewpoint.pitch = rocky::Angle(clampPitch(state.pitch) - 90.0, rocky::Units::DEGREES);
            return viewpoint;
        }

        void applyCameraNow(rocky::Application* activeApp)
        {
            auto manipulator = manipulatorFor(activeApp);
            if (!manipulator)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "camera update skipped: manipulator missing");
                return;
            }

            clearPendingMotion();
            const auto viewpoint = viewpointForCameraState(cameraState, effectiveRenderHeight());
            manipulator->setViewpoint(viewpoint, std::chrono::duration<float>(0.0f));
            syncCameraStateFromManipulatorLocked(activeApp, true);
            updateProjectionSnapshotLocked(activeApp);
            if (activeApp && activeApp->vsgcontext)
            {
                activeApp->vsgcontext->requestFrame();
            }
            emitCameraStateLocked(currentCameraState());
        }

        void queueSetCameraLocked(float durationSeconds)
        {
            queueCameraCommandLocked([this, durationSeconds](rocky::Application* activeApp)
            {
                auto manipulator = manipulatorFor(activeApp);
                if (!manipulator)
                {
                    __android_log_print(ANDROID_LOG_WARN, TAG, "queued camera update skipped: manipulator missing");
                    return false;
                }
                const auto viewpoint = viewpointForCameraState(cameraState, effectiveRenderHeight());
                manipulator->setViewpoint(viewpoint, std::chrono::duration<float>(std::max(durationSeconds, 0.0f)));
                return true;
            }, true);
        }

        using CameraCommand = std::function<bool(rocky::Application*)>;

        void queueCameraCommandLocked(CameraCommand command, bool forceEmit = false)
        {
            if (!app)
            {
                return;
            }

            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, command = std::move(command), forceEmit]()
            {
                bool changed = false;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (app.get() != activeApp)
                    {
                        return;
                    }
                    changed = command(activeApp);
                    if (changed)
                    {
                        updateProjectionSnapshotLocked(activeApp);
                        syncCameraStateFromManipulatorLocked(activeApp, forceEmit);
                    }
                    if (activeApp->vsgcontext)
                    {
                        activeApp->vsgcontext->requestFrame();
                    }
                }
                if (changed)
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    emitCameraStateLocked(currentCameraState());
                }
            });
            requestFrameLocked();
        }

        std::mutex mutex;
        ANativeWindow* nativeWindow = nullptr;
        std::unique_ptr<rocky::Application> app;
        std::string resourcePath;
        std::atomic_bool running = false;
        std::thread renderThread;
        int surfaceWidth = 1;
        int surfaceHeight = 1;
        ProjectionSnapshot projectionSnapshot;

        NativeCameraState cameraState;
        float terrainExaggeration = 1.0f;
        bool terrainExaggerationUpdateQueued = false;

        std::mutex motionMutex;
        PendingGestureMotion pendingGesture;
        bool flingActive = false;
        double flingVelocityX = 0.0;
        double flingVelocityY = 0.0;
        int flingViewWidth = 1;
        int flingViewHeight = 1;
        std::chrono::steady_clock::time_point flingLastTick = std::chrono::steady_clock::now();

        std::unordered_map<std::string, RasterLayerConfig> rasterLayers;
        std::vector<std::string> rasterLayerOrder;
        std::unordered_map<std::string, ElevationLayerConfig> elevationLayers;
        std::vector<std::string> elevationLayerOrder;
        std::unordered_map<std::string, ModelLayerConfig> modelLayers;
        std::unordered_map<std::string, entt::entity> modelEntities;
        std::unordered_map<std::string, float> modelLoggedRadii;
        std::unordered_map<std::string, std::string> modelLoggedErrors;
        std::unordered_map<std::string, Tiles3DRendererContentConfig> tiles3DRendererContent;
        std::unordered_map<std::string, entt::entity> tiles3DEntities;
        std::unordered_map<std::string, float> tiles3DLoggedRadii;
        std::unordered_map<std::string, std::string> tiles3DLoggedErrors;
        // Layer-level 3D Tiles (new C++ pipeline)
        std::unordered_map<std::string, std::shared_ptr<rocky::Tiles3DLayer>> tiles3DLayerMap;
        std::unordered_map<std::string, MvtLayerRuntime> mvtLayers;
        std::uint64_t mvtLayerGenerationCounter = 0;
        std::unordered_map<std::string, MvtRenderTileConfig> mvtTiles;
        std::unordered_map<std::string, std::vector<entt::entity>> mvtEntities;
        std::unordered_map<std::string, std::uint64_t> mvtTileRevisions;
        std::uint64_t mvtTileRevisionCounter = 0;
        std::unordered_map<std::string, std::pair<double, double>> pointAnnotations;
        std::unordered_map<std::string, LabelAnnotationConfig> labelAnnotations;
        std::unordered_map<std::string, entt::entity> labelEntities;
        std::unordered_map<std::string, std::vector<entt::entity>> drawEntities;
        std::optional<LocationIndicatorConfig> locationIndicator;
        std::vector<entt::entity> locationEntities;
        JavaVM* javaVm = nullptr;
        jobject statusCallback = nullptr;
        jmethodID statusCallbackMethod = nullptr;
        jobject cameraCallback = nullptr;
        jmethodID cameraCallbackMethod = nullptr;
    };

    VectorraNativeEngine* fromHandle(jlong handle)
    {
        return reinterpret_cast<VectorraNativeEngine*>(handle);
    }

    std::string jstringToString(JNIEnv* env, jstring value)
    {
        if (!value)
        {
            return {};
        }
        const char* chars = env->GetStringUTFChars(value, nullptr);
        std::string result(chars ? chars : "");
        if (chars)
        {
            env->ReleaseStringUTFChars(value, chars);
        }
        return result;
    }

    std::vector<std::pair<std::string, std::string>> headersFromJArrays(
        JNIEnv* env,
        jobjectArray names,
        jobjectArray values)
    {
        std::vector<std::pair<std::string, std::string>> headers;
        if (!names || !values)
        {
            return headers;
        }
        const auto nameCount = env->GetArrayLength(names);
        const auto valueCount = env->GetArrayLength(values);
        const auto count = std::min(nameCount, valueCount);
        headers.reserve(static_cast<std::size_t>(count));
        for (jsize i = 0; i < count; ++i)
        {
            auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
            auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
            std::string headerName = jstringToString(env, name);
            std::string headerValue = jstringToString(env, value);
            if (!headerName.empty())
            {
                headers.emplace_back(std::move(headerName), std::move(headerValue));
            }
            if (name)
            {
                env->DeleteLocalRef(name);
            }
            if (value)
            {
                env->DeleteLocalRef(value);
            }
        }
        return headers;
    }

    jobjectArray stringArrayFromVector(JNIEnv* env, const std::vector<std::string>& values)
    {
        jclass stringClass = env->FindClass("java/lang/String");
        auto result = env->NewObjectArray(
            static_cast<jsize>(values.size()),
            stringClass,
            nullptr);
        for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i)
        {
            jstring value = env->NewStringUTF(values[static_cast<std::size_t>(i)].c_str());
            env->SetObjectArrayElement(result, i, value);
            env->DeleteLocalRef(value);
        }
        env->DeleteLocalRef(stringClass);
        return result;
    }

    jintArray intArrayFromVector(JNIEnv* env, const std::vector<int>& values)
    {
        auto result = env->NewIntArray(static_cast<jsize>(values.size()));
        if (!values.empty())
        {
            std::vector<jint> jValues;
            jValues.reserve(values.size());
            for (int value : values)
            {
                jValues.push_back(static_cast<jint>(value));
            }
            env->SetIntArrayRegion(result, 0, static_cast<jsize>(jValues.size()), jValues.data());
        }
        return result;
    }

    jdoubleArray doubleArrayFromVector(JNIEnv* env, const std::vector<double>& values)
    {
        auto result = env->NewDoubleArray(static_cast<jsize>(values.size()));
        if (!values.empty())
        {
            env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        }
        return result;
    }

    jobject mvtSubmitResultToJObject(JNIEnv* env, const MvtSubmitResult& result)
    {
        jclass resultClass = env->FindClass("com/vectorra/maps/internal/VectorraNative$MvtTileResult");
        if (!resultClass)
        {
            return nullptr;
        }
        jmethodID constructor = env->GetMethodID(
            resultClass,
            "<init>",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I[I[D[I[I[I[Ljava/lang/String;[Ljava/lang/String;)V");
        if (!constructor)
        {
            env->DeleteLocalRef(resultClass);
            return nullptr;
        }

        jstring jHandle = env->NewStringUTF(result.nativeTileHandle.c_str());
        jstring jError = result.errorMessage.empty() ? nullptr : env->NewStringUTF(result.errorMessage.c_str());
        jobjectArray jFeatureIds = stringArrayFromVector(env, result.query.featureIds);
        jobjectArray jSourceLayers = stringArrayFromVector(env, result.query.sourceLayers);
        jintArray jGeometryTypes = intArrayFromVector(env, result.query.geometryTypes);
        jintArray jCoordinateOffsets = intArrayFromVector(env, result.query.coordinateOffsets);
        jdoubleArray jCoordinates = doubleArrayFromVector(env, result.query.coordinates);
        jintArray jRingOffsets = intArrayFromVector(env, result.query.ringOffsets);
        jintArray jRingEnds = intArrayFromVector(env, result.query.ringEnds);
        jintArray jPropertyOffsets = intArrayFromVector(env, result.query.propertyOffsets);
        jobjectArray jPropertyKeys = stringArrayFromVector(env, result.query.propertyKeys);
        jobjectArray jPropertyValues = stringArrayFromVector(env, result.query.propertyValues);

        jobject object = env->NewObject(
            resultClass,
            constructor,
            jHandle,
            jError,
            jFeatureIds,
            jSourceLayers,
            jGeometryTypes,
            jCoordinateOffsets,
            jCoordinates,
            jRingOffsets,
            jRingEnds,
            jPropertyOffsets,
            jPropertyKeys,
            jPropertyValues);

        env->DeleteLocalRef(jHandle);
        if (jError)
        {
            env->DeleteLocalRef(jError);
        }
        env->DeleteLocalRef(jFeatureIds);
        env->DeleteLocalRef(jSourceLayers);
        env->DeleteLocalRef(jGeometryTypes);
        env->DeleteLocalRef(jCoordinateOffsets);
        env->DeleteLocalRef(jCoordinates);
        env->DeleteLocalRef(jRingOffsets);
        env->DeleteLocalRef(jRingEnds);
        env->DeleteLocalRef(jPropertyOffsets);
        env->DeleteLocalRef(jPropertyKeys);
        env->DeleteLocalRef(jPropertyValues);
        env->DeleteLocalRef(resultClass);
        return object;
    }

    std::optional<std::array<double, 16>> matrixFromJDoubleArray(JNIEnv* env, jdoubleArray array)
    {
        if (!array)
        {
            return std::nullopt;
        }
        std::array<double, 16> result{};
        if (env->GetArrayLength(array) != static_cast<jsize>(result.size()))
        {
            return std::nullopt;
        }

        env->GetDoubleArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
        return result;
    }

    std::vector<glm::dvec3> coordinatesFromJDoubleArray(JNIEnv* env, jdoubleArray array)
    {
        std::vector<glm::dvec3> result;
        if (!array)
        {
            return result;
        }
        const auto length = env->GetArrayLength(array);
        if (length < 2)
        {
            return result;
        }
        std::vector<jdouble> values(static_cast<std::size_t>(length));
        env->GetDoubleArrayRegion(array, 0, length, values.data());
        result.reserve(static_cast<std::size_t>(length / 2));
        for (jsize i = 0; i + 1 < length; i += 2)
        {
            const double longitude = values[static_cast<std::size_t>(i)];
            const double latitude = values[static_cast<std::size_t>(i + 1)];
            if (std::isfinite(longitude) && std::isfinite(latitude))
            {
                result.emplace_back(longitude, latitude, 0.0);
            }
        }
        return result;
    }

    std::vector<std::vector<glm::dvec3>> ringsFromJArrays(
        JNIEnv* env,
        jdoubleArray coordinatesArray,
        jintArray ringEndsArray)
    {
        std::vector<std::vector<glm::dvec3>> rings;
        const auto coordinates = coordinatesFromJDoubleArray(env, coordinatesArray);
        if (!ringEndsArray || coordinates.empty())
        {
            return rings;
        }
        const auto ringCount = env->GetArrayLength(ringEndsArray);
        std::vector<jint> ringEnds(static_cast<std::size_t>(ringCount));
        env->GetIntArrayRegion(ringEndsArray, 0, ringCount, ringEnds.data());

        int start = 0;
        for (jint rawEnd : ringEnds)
        {
            const int end = std::clamp(static_cast<int>(rawEnd), start, static_cast<int>(coordinates.size()));
            if (end - start >= 3)
            {
                rings.emplace_back(coordinates.begin() + start, coordinates.begin() + end);
            }
            start = end;
        }
        return rings;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vectorra_maps_internal_VectorraNative_create(JNIEnv*, jobject)
{
    return reinterpret_cast<jlong>(new VectorraNativeEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_destroy(JNIEnv*, jobject, jlong handle)
{
    delete fromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setResourceStatusCallback(JNIEnv* env, jobject, jlong handle, jobject callback)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setResourceStatusCallback(env, callback);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setCameraCallback(JNIEnv* env, jobject, jlong handle, jobject callback)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setCameraCallback(env, callback);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setResourcePath(JNIEnv* env, jobject, jlong handle, jstring path)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setResourcePath(jstringToString(env, path));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setCachePath(JNIEnv* env, jobject, jlong handle, jstring path)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setCachePath(jstringToString(env, path));
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setSurface(JNIEnv* env, jobject, jlong handle, jobject surface, jint width, jint height)
{
    if (auto* engine = fromHandle(handle))
    {
        const std::string error = engine->setSurface(env, surface, width, height);
        if (!error.empty())
        {
            return env->NewStringUTF(error.c_str());
        }
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_resize(JNIEnv*, jobject, jlong handle, jint width, jint height)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->resize(width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setCamera(
    JNIEnv*,
    jobject,
    jlong handle,
    jdouble longitude,
    jdouble latitude,
    jdouble zoom,
    jdouble pitch,
    jdouble bearing,
    jdouble targetHeightMeters,
    jlong durationMillis)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setCamera(longitude, latitude, zoom, pitch, bearing, targetHeightMeters, durationMillis);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_panByPixels(
    JNIEnv*, jobject, jlong handle, jfloat deltaX, jfloat deltaY, jint viewWidth, jint viewHeight)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->panByPixels(deltaX, deltaY, viewWidth, viewHeight);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_zoomByScale(JNIEnv*, jobject, jlong handle, jfloat scale)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->zoomByScale(scale);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_zoomByScaleAt(
    JNIEnv*, jobject, jlong handle, jfloat scale, jfloat focusX, jfloat focusY, jint viewWidth, jint viewHeight)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->zoomByScaleAt(scale, focusX, focusY, viewWidth, viewHeight);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_rotateByDegrees(JNIEnv*, jobject, jlong handle, jdouble deltaDegrees)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->rotateByDegrees(deltaDegrees);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_pitchByDegrees(JNIEnv*, jobject, jlong handle, jdouble deltaDegrees)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->pitchByDegrees(deltaDegrees);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_flingByVelocity(
    JNIEnv*, jobject, jlong handle, jfloat velocityX, jfloat velocityY, jint viewWidth, jint viewHeight)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->flingByVelocity(velocityX, velocityY, viewWidth, viewHeight);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_cancelCameraMotion(JNIEnv*, jobject, jlong handle)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->cancelCameraMotion();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_cancelFling(JNIEnv*, jobject, jlong handle)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->cancelFling();
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_vectorra_maps_internal_VectorraNative_projectCoordinates(
    JNIEnv* env,
    jobject,
    jlong handle,
    jdoubleArray lonLatHeight)
{
    if (!lonLatHeight)
    {
        return env->NewDoubleArray(0);
    }

    const auto length = env->GetArrayLength(lonLatHeight);
    if (length % COORDINATE_PROJECTION_INPUT_STRIDE != 0)
    {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass)
        {
            env->ThrowNew(exceptionClass, "projectCoordinates input must be lon/lat/height triples.");
        }
        return nullptr;
    }

    std::vector<jdouble> input(static_cast<std::size_t>(length));
    if (length > 0)
    {
        env->GetDoubleArrayRegion(lonLatHeight, 0, length, input.data());
    }

    std::vector<double> output;
    if (auto* engine = fromHandle(handle))
    {
        output = engine->projectCoordinates(input);
    }

    auto result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    if (result && !output.empty())
    {
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_vectorra_maps_internal_VectorraNative_screenToCoordinate(
    JNIEnv* env,
    jobject,
    jlong handle,
    jdouble x,
    jdouble y)
{
    std::array<double, SCREEN_TO_COORDINATE_OUTPUT_STRIDE> output{0.0, 0.0, 0.0};
    if (auto* engine = fromHandle(handle))
    {
        const auto result = engine->screenToCoordinate(x, y);
        if (result.hit)
        {
            output[0] = result.longitude;
            output[1] = result.latitude;
            output[2] = 1.0;
        }
    }

    auto nativeResult = env->NewDoubleArray(SCREEN_TO_COORDINATE_OUTPUT_STRIDE);
    if (nativeResult)
    {
        env->SetDoubleArrayRegion(
            nativeResult,
            0,
            SCREEN_TO_COORDINATE_OUTPUT_STRIDE,
            output.data());
    }
    return nativeResult;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addRasterLayer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jstring templateUrl,
    jint minZoom,
    jint maxZoom,
    jboolean visible,
    jdouble opacity,
    jdouble saturation,
    jdouble contrast,
    jint tileSize,
    jstring scheme,
    jstring matrixSet,
    jboolean disableNativeDiskCache,
    jobjectArray headerNames,
    jobjectArray headerValues)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->addRasterLayer(
            jstringToString(env, id),
            jstringToString(env, templateUrl),
            minZoom,
            maxZoom,
            visible == JNI_TRUE,
            opacity,
            saturation,
            contrast,
            tileSize,
            jstringToString(env, scheme),
            jstringToString(env, matrixSet),
            disableNativeDiskCache == JNI_TRUE,
            headersFromJArrays(env, headerNames, headerValues));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_removeLayer(JNIEnv* env, jobject, jlong handle, jstring id)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->removeLayer(jstringToString(env, id));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_moveLayerToTop(JNIEnv* env, jobject, jlong handle, jstring id)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->moveLayerToTop(jstringToString(env, id));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setRasterLayerStyle(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jboolean visible,
    jdouble opacity,
    jdouble saturation,
    jdouble contrast)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setRasterLayerStyle(jstringToString(env, id), visible == JNI_TRUE, opacity, saturation, contrast);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addElevationLayer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jstring templateUrl,
    jint minZoom,
    jint maxZoom,
    jboolean disableNativeDiskCache,
    jobjectArray headerNames,
    jobjectArray headerValues)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->addElevationLayer(
            jstringToString(env, id),
            jstringToString(env, templateUrl),
            minZoom,
            maxZoom,
            disableNativeDiskCache == JNI_TRUE,
            headersFromJArrays(env, headerNames, headerValues));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setTerrainExaggeration(JNIEnv*, jobject, jlong handle, jdouble value)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setTerrainExaggeration(value);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setLayerVisible(JNIEnv* env, jobject, jlong handle, jstring id, jboolean visible)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setLayerVisible(jstringToString(env, id), visible == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addModelLayer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jstring uri,
    jdouble longitude,
    jdouble latitude,
    jdouble heightMeters,
    jdouble scale,
    jdouble yawDegrees,
    jboolean visible)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->addModelLayer(
            jstringToString(env, id),
            jstringToString(env, uri),
            longitude,
            latitude,
            heightMeters,
            scale,
            yawDegrees,
            visible == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_removeModelLayer(JNIEnv* env, jobject, jlong handle, jstring id)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->removeModelLayer(jstringToString(env, id));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setModelLayerVisible(JNIEnv* env, jobject, jlong handle, jstring id, jboolean visible)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setModelLayerVisible(jstringToString(env, id), visible == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_add3DTilesRendererContent(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jstring renderUri,
    jstring transformKind,
    jdoubleArray transformMatrix,
    jdouble ecefX,
    jdouble ecefY,
    jdouble ecefZ,
    jboolean visible)
{
    if (auto* engine = fromHandle(handle))
    {
        const auto matrix = matrixFromJDoubleArray(env, transformMatrix);
        if (!matrix)
        {
            __android_log_print(
                ANDROID_LOG_ERROR,
                TAG,
                "rejecting 3D Tiles renderer content with missing or invalid matrix array");
            return;
        }

        engine->add3DTilesRendererContent(
            jstringToString(env, id),
            jstringToString(env, renderUri),
            jstringToString(env, transformKind),
            *matrix,
            ecefX,
            ecefY,
            ecefZ,
            visible == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_remove3DTilesRendererContent(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->remove3DTilesRendererContent(jstringToString(env, id));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addTileset3DLayer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring layerId,
    jstring tilesetUri,
    jobjectArray headerKeys,
    jobjectArray headerValues,
    jfloat maxSSE,
    jint maxTiles)
{
    if (auto* engine = fromHandle(handle))
    {
        std::vector<std::pair<std::string, std::string>> headers;
        if (headerKeys && headerValues)
        {
            const jsize n = env->GetArrayLength(headerKeys);
            for (jsize i = 0; i < n; ++i)
            {
                auto k = static_cast<jstring>(env->GetObjectArrayElement(headerKeys, i));
                auto v = static_cast<jstring>(env->GetObjectArrayElement(headerValues, i));
                if (k && v)
                    headers.emplace_back(jstringToString(env, k), jstringToString(env, v));
                env->DeleteLocalRef(k);
                env->DeleteLocalRef(v);
            }
        }
        engine->addTileset3DLayer(
            jstringToString(env, layerId),
            jstringToString(env, tilesetUri),
            headers,
            static_cast<float>(maxSSE),
            static_cast<int>(maxTiles));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_removeTileset3DLayer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring layerId)
{
    if (auto* engine = fromHandle(handle))
        engine->removeTileset3DLayer(jstringToString(env, layerId));
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setTileset3DLayerViewportHeight(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat height)
{
    if (auto* engine = fromHandle(handle))
        engine->setTileset3DLayerViewportHeight(static_cast<float>(height));
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addMvtLayer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring sourceId,
    jstring layerId,
    jstring sourceLayer,
    jstring templateUrl,
    jint sourceMinZoom,
    jint sourceMaxZoom,
    jint layerMinZoom,
    jint layerMaxZoom,
    jint tileSize,
    jstring scheme,
    jstring styleKind,
    jboolean visible,
    jint color,
    jfloat opacity,
    jfloat widthPixels,
    jfloat radiusPixels,
    jfloat textSizeSp,
    jobjectArray headerNames,
    jobjectArray headerValues)
{
    if (auto* engine = fromHandle(handle))
    {
        MvtLayerConfig config;
        config.sourceId = jstringToString(env, sourceId);
        config.layerId = jstringToString(env, layerId);
        config.sourceLayer = jstringToString(env, sourceLayer);
        config.templateUrl = jstringToString(env, templateUrl);
        config.sourceMinZoom = static_cast<int>(sourceMinZoom);
        config.sourceMaxZoom = static_cast<int>(sourceMaxZoom);
        config.layerMinZoom = static_cast<int>(layerMinZoom);
        config.layerMaxZoom = static_cast<int>(layerMaxZoom);
        config.tileSize = static_cast<int>(tileSize);
        config.scheme = jstringToString(env, scheme);
        config.style = MvtRenderStyleConfig{
            jstringToString(env, styleKind),
            visible == JNI_TRUE,
            static_cast<int>(color),
            opacity,
            widthPixels,
            radiusPixels,
            textSizeSp};
        config.headers = headersFromJArrays(env, headerNames, headerValues);
        engine->addMvtLayer(config);
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_vectorra_maps_internal_VectorraNative_queryMvtRenderedFeatures(
    JNIEnv* env,
    jobject,
    jlong handle)
{
    MvtSubmitResult result;
    if (auto* engine = fromHandle(handle))
    {
        result = engine->queryMvtRenderedFeatures();
    }
    else
    {
        result.errorMessage = "MVT native renderer handle is invalid.";
    }
    return mvtSubmitResultToJObject(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_removeMvtLayer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring layerId)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->removeMvtLayer(jstringToString(env, layerId));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_clearAnnotations(JNIEnv*, jobject, jlong handle)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->clearAnnotations();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addPointAnnotation(
    JNIEnv* env, jobject, jlong handle, jstring id, jdouble longitude, jdouble latitude)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->addPointAnnotation(jstringToString(env, id), longitude, latitude);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_clearDrawAnnotations(JNIEnv*, jobject, jlong handle)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->clearDrawAnnotations();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_removeDrawAnnotation(JNIEnv* env, jobject, jlong handle, jstring id)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->removeDrawAnnotation(jstringToString(env, id));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addDrawPointAnnotation(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jdouble longitude,
    jdouble latitude,
    jstring text,
    jfloat textSize,
    jint textColor,
    jint textHaloColor,
    jfloat textHaloWidth,
    jint iconColor,
    jfloat iconRadius)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->addDrawPointAnnotation(
            jstringToString(env, id),
            longitude,
            latitude,
            jstringToString(env, text),
            textSize,
            textColor,
            textHaloColor,
            textHaloWidth,
            iconColor,
            iconRadius);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addDrawLineAnnotation(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jdoubleArray coordinates,
    jint lineColor,
    jfloat lineWidth)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->addDrawLineAnnotation(
            jstringToString(env, id),
            coordinatesFromJDoubleArray(env, coordinates),
            lineColor,
            lineWidth);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addDrawPolygonAnnotation(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jdoubleArray coordinates,
    jintArray ringEnds,
    jint fillColor,
    jfloat fillOpacity,
    jint outlineColor,
    jfloat outlineWidth)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->addDrawPolygonAnnotation(
            jstringToString(env, id),
            ringsFromJArrays(env, coordinates, ringEnds),
            fillColor,
            fillOpacity,
            outlineColor,
            outlineWidth);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_clearLabelAnnotations(JNIEnv*, jobject, jlong handle)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->clearLabelAnnotations();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_addLabelAnnotation(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring id,
    jdouble longitude,
    jdouble latitude,
    jstring text,
    jdouble textSize,
    jint textColor,
    jint textHaloColor,
    jdouble textHaloWidth,
    jdouble textOffsetX,
    jdouble textOffsetY,
    jboolean hasIcon,
    jint iconColor,
    jdouble iconRadius,
    jboolean allowOverlap)
{
    if (auto* engine = fromHandle(handle))
    {
        LabelAnnotationConfig config;
        config.id = jstringToString(env, id);
        config.longitude = longitude;
        config.latitude = latitude;
        config.text = jstringToString(env, text);
        config.textSize = static_cast<float>(textSize);
        config.textColor = textColor;
        config.textHaloColor = textHaloColor;
        config.textHaloWidth = static_cast<float>(textHaloWidth);
        config.textOffsetX = static_cast<int>(std::round(textOffsetX));
        config.textOffsetY = static_cast<int>(std::round(textOffsetY));
        config.hasIcon = hasIcon == JNI_TRUE;
        config.iconColor = iconColor;
        config.iconRadius = static_cast<float>(iconRadius);
        config.allowOverlap = allowOverlap == JNI_TRUE;
        engine->addLabelAnnotation(config);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_setLocationIndicator(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled,
    jdouble longitude,
    jdouble latitude,
    jdouble accuracyMeters,
    jdouble bearingDegrees,
    jboolean showAccuracyRing,
    jdouble accuracyRadiusPixels)
{
    if (auto* engine = fromHandle(handle))
    {
        LocationIndicatorConfig config;
        config.enabled = enabled == JNI_TRUE;
        config.longitude = longitude;
        config.latitude = latitude;
        config.accuracyMeters = accuracyMeters;
        config.bearingDegrees = bearingDegrees;
        config.showAccuracyRing = showAccuracyRing == JNI_TRUE;
        config.accuracyRadiusPixels = static_cast<float>(accuracyRadiusPixels);
        engine->setLocationIndicator(config);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_clearLocationIndicator(JNIEnv*, jobject, jlong handle)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->clearLocationIndicator();
    }
}
