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
#include <rocky/TMSImageLayer.h>
#include <rocky/TMSElevationLayer.h>
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

#include <glm/gtc/matrix_transform.hpp>

#include <vsg/core/Exception.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <cmath>
#include <optional>
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
        std::vector<std::pair<std::string, std::string>> headers;
    };

    struct ElevationLayerConfig
    {
        std::string templateUrl;
        int minZoom = 0;
        int maxZoom = 14;
        bool visible = true;
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
        int tileZ = 0;
        int tileX = 0;
        int tileY = 0;
        MvtRenderStyleConfig style;
        std::vector<std::string> featureIds;
        std::vector<std::string> sourceLayers;
        std::vector<int> geometryTypes;
        std::vector<int> coordinateOffsets;
        std::vector<double> coordinates;
        std::vector<int> ringOffsets;
        std::vector<int> ringEnds;
    };

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
            javaVm = nullptr;

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
                javaVm = nullptr;
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

        void detachSurface()
        {
            std::unique_lock<std::mutex> lock(mutex);
            shutdownRendererLocked(lock);
            __android_log_print(ANDROID_LOG_INFO, TAG, "detachSurface");
        }

        void resize(int width, int height)
        {
            std::lock_guard<std::mutex> lock(mutex);
            surfaceWidth = width > 0 ? width : 1;
            surfaceHeight = height > 0 ? height : 1;
            __android_log_print(ANDROID_LOG_INFO, TAG, "resize %dx%d", surfaceWidth, surfaceHeight);
        }

        void setCamera(
            double longitude,
            double latitude,
            double zoom,
            double pitch,
            double bearing,
            double targetHeightMeters)
        {
            std::lock_guard<std::mutex> lock(mutex);
            cameraLongitude = longitude;
            cameraLatitude = latitude;
            cameraZoom = zoom;
            cameraPitch = pitch;
            cameraBearing = bearing;
            cameraTargetHeightMeters = std::isfinite(targetHeightMeters) ? targetHeightMeters : 0.0;
            if (app)
            {
                queueLatestCameraUpdateLocked();
            }
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
            std::vector<std::pair<std::string, std::string>> headers)
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (elevationLayers.find(id) == elevationLayers.end())
            {
                elevationLayerOrder.emplace_back(id);
            }
            elevationLayers[id] = ElevationLayerConfig{templateUrl, minZoom, maxZoom, true, std::move(headers)};
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

        std::string renderMvtTile(const MvtRenderTileConfig& config)
        {
            if (config.sourceId.empty() || config.layerId.empty())
            {
                return "";
            }
            if (config.tileZ < 0 || config.tileX < 0 || config.tileY < 0)
            {
                return "";
            }
            const auto featureCount = config.featureIds.size();
            if (featureCount != config.sourceLayers.size() ||
                featureCount != config.geometryTypes.size() ||
                config.coordinateOffsets.size() != featureCount + 1 ||
                config.ringOffsets.size() != featureCount + 1)
            {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    TAG,
                    "rejecting MVT tile layer=%s z=%d x=%d y=%d with inconsistent feature arrays",
                    config.layerId.c_str(),
                    config.tileZ,
                    config.tileX,
                    config.tileY);
                return "";
            }
            if (!std::all_of(config.coordinates.begin(), config.coordinates.end(), [](double value)
                {
                    return std::isfinite(value);
                }) ||
                !std::isfinite(config.style.opacity) ||
                !std::isfinite(config.style.widthPixels) ||
                !std::isfinite(config.style.radiusPixels) ||
                !std::isfinite(config.style.textSizeSp))
            {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    TAG,
                    "rejecting MVT tile layer=%s z=%d x=%d y=%d with non-finite values",
                    config.layerId.c_str(),
                    config.tileZ,
                    config.tileX,
                    config.tileY);
                return "";
            }

            const std::string handle =
                config.layerId + ":" +
                std::to_string(config.tileZ) + "/" +
                std::to_string(config.tileX) + "/" +
                std::to_string(config.tileY);

            std::lock_guard<std::mutex> lock(mutex);
            mvtTiles[handle] = config;
            if (app)
            {
                queueApplyMvtTileLocked(handle, config);
            }
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "registered MVT render tile handle=%s source=%s style=%s features=%zu coordinates=%zu visible=%d",
                handle.c_str(),
                config.sourceId.c_str(),
                config.style.kind.c_str(),
                featureCount,
                config.coordinates.size() / 2,
                config.style.visible ? 1 : 0);
            return handle;
        }

        void removeMvtTile(const std::string& handle)
        {
            if (handle.empty())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(mutex);
            mvtTiles.erase(handle);
            if (app)
            {
                queueRemoveMvtTileLocked(handle);
            }
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "removed MVT render tile handle=%s",
                handle.c_str());
        }

        void removeMvtLayer(const std::string& layerId)
        {
            if (layerId.empty())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(mutex);
            int removed = 0;
            std::vector<std::string> removedHandles;
            for (auto itr = mvtTiles.begin(); itr != mvtTiles.end();)
            {
                if (itr->second.layerId == layerId)
                {
                    removedHandles.push_back(itr->first);
                    itr = mvtTiles.erase(itr);
                    ++removed;
                }
                else
                {
                    ++itr;
                }
            }
            if (app)
            {
                for (const auto& handle : removedHandles)
                {
                    queueRemoveMvtTileLocked(handle);
                }
            }
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "removed MVT render layer id=%s tiles=%d",
                layerId.c_str(),
                removed);
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

        void onTouch(int action, int pointerCount, float x0, float y0, float x1, float y1)
        {
            std::lock_guard<std::mutex> lock(mutex);
            lastTouchAction = action;
            lastPointerCount = pointerCount;
            lastTouchX0 = x0;
            lastTouchY0 = y0;
            lastTouchX1 = x1;
            lastTouchY1 = y1;
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
            cameraUpdateQueued = false;
            terrainExaggerationUpdateQueued = false;
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
            mvtTiles.clear();
            mvtEntities.clear();
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
                javaVm = nullptr;
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
                    javaVm = nullptr;
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
            javaVm = nullptr;
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

        void queueApplyMvtTileLocked(
            const std::string& handle,
            const MvtRenderTileConfig& config)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, handle, config]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                applyMvtTileLocked(handle, config);
            });
            requestFrameLocked();
        }

        void queueRemoveMvtTileLocked(const std::string& handle)
        {
            auto* activeApp = app.get();
            activeApp->onNextUpdate([this, activeApp, handle]()
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (app.get() != activeApp)
                {
                    return;
                }
                removeMvtTileEntitiesLocked(handle);
            });
            requestFrameLocked();
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
            if (!config.style.visible)
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
            return cameraRangeMeters(cameraZoom, cameraLatitude, effectiveRenderHeight());
        }

        static double cameraRangeMeters(double zoom, double latitude, int viewportHeight)
        {
            const double clampedZoom = std::clamp(zoom, 0.0, 22.0);
            const double latitudeScale = std::max(std::cos(latitude * PI / 180.0), 0.05);
            const double metersPerPixel =
                EARTH_CIRCUMFERENCE_METERS * latitudeScale /
                (WEB_MERCATOR_TILE_SIZE * std::pow(2.0, clampedZoom));
            const double visibleGroundMeters = metersPerPixel * static_cast<double>(std::max(viewportHeight, 1));
            const double halfFovyRadians = (VECTORRA_CAMERA_FOVY_DEGREES * PI / 180.0) * 0.5;

            return std::clamp(
                visibleGroundMeters / (2.0 * std::tan(halfFovyRadians)),
                100.0,
                30000000.0);
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

        rocky::Viewpoint currentViewpoint() const
        {
            rocky::Viewpoint viewpoint;
            viewpoint.point = rocky::GeoPoint(
                rocky::SRS::WGS84,
                cameraLongitude,
                cameraLatitude,
                cameraTargetHeightMeters);
            viewpoint.range = rocky::Distance(cameraRangeMeters(), rocky::Units::METERS);
            viewpoint.heading = rocky::Angle(cameraBearing, rocky::Units::DEGREES);
            viewpoint.pitch = rocky::Angle(std::clamp(cameraPitch - 90.0, -90.0, -5.0), rocky::Units::DEGREES);
            return viewpoint;
        }

        void applyCameraNow(rocky::Application* activeApp)
        {
            if (!activeApp || activeApp->display.windows().empty())
            {
                return;
            }

            auto& window = activeApp->display.window(0);
            if (window.views().empty())
            {
                return;
            }

            auto& view = window.view(0);
            auto manipulator = rocky::MapManipulator::get(view.vsgView);
            if (!manipulator)
            {
                __android_log_print(ANDROID_LOG_WARN, TAG, "camera update skipped: manipulator missing");
                return;
            }

            const auto viewpoint = currentViewpoint();
            manipulator->setViewpoint(viewpoint, std::chrono::duration<float>(0.0f));
            if (activeApp->vsgcontext)
            {
                activeApp->vsgcontext->requestFrame();
            }
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "camera applied lon=%.6f lat=%.6f height=%.1f zoom=%.2f range=%.1f pitch=%.1f bearing=%.1f",
                cameraLongitude,
                cameraLatitude,
                cameraTargetHeightMeters,
                cameraZoom,
                cameraRangeMeters(),
                cameraPitch - 90.0,
                cameraBearing);
        }

        void queueLatestCameraUpdateLocked()
        {
            if (cameraUpdateQueued)
            {
                return;
            }

            auto* activeApp = app.get();
            cameraUpdateQueued = true;
            activeApp->onNextUpdate([this, activeApp]()
            {
                double longitude = 0.0;
                double latitude = 0.0;
                double zoom = 0.0;
                double pitch = 0.0;
                double bearing = 0.0;
                double targetHeightMeters = 0.0;
                int viewportHeight = 1;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    cameraUpdateQueued = false;
                    longitude = cameraLongitude;
                    latitude = cameraLatitude;
                    zoom = cameraZoom;
                    pitch = cameraPitch;
                    bearing = cameraBearing;
                    targetHeightMeters = cameraTargetHeightMeters;
                    viewportHeight = effectiveRenderHeight();
                }

                if (!activeApp || activeApp->display.windows().empty())
                {
                    return;
                }

                auto& window = activeApp->display.window(0);
                if (window.views().empty())
                {
                    return;
                }

                auto& view = window.view(0);
                auto manipulator = rocky::MapManipulator::get(view.vsgView);
                if (!manipulator)
                {
                    __android_log_print(ANDROID_LOG_WARN, TAG, "queued camera update skipped: manipulator missing");
                    return;
                }

                const double rangeMeters = cameraRangeMeters(zoom, latitude, viewportHeight);

                rocky::Viewpoint viewpoint;
                viewpoint.point = rocky::GeoPoint(rocky::SRS::WGS84, longitude, latitude, targetHeightMeters);
                viewpoint.range = rocky::Distance(rangeMeters, rocky::Units::METERS);
                viewpoint.heading = rocky::Angle(bearing, rocky::Units::DEGREES);
                viewpoint.pitch = rocky::Angle(std::clamp(pitch - 90.0, -90.0, -5.0), rocky::Units::DEGREES);
                manipulator->setViewpoint(viewpoint, std::chrono::duration<float>(0.0f));
                if (activeApp->vsgcontext)
                {
                    activeApp->vsgcontext->requestFrame();
                }
                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "camera applied lon=%.6f lat=%.6f height=%.1f zoom=%.2f range=%.1f pitch=%.1f bearing=%.1f",
                    longitude,
                    latitude,
                    targetHeightMeters,
                    zoom,
                    rangeMeters,
                    pitch - 90.0,
                    bearing);
            });
        }

        std::mutex mutex;
        ANativeWindow* nativeWindow = nullptr;
        std::unique_ptr<rocky::Application> app;
        std::string resourcePath;
        std::atomic_bool running = false;
        std::thread renderThread;
        int surfaceWidth = 1;
        int surfaceHeight = 1;

        double cameraLongitude = 104.293174;
        double cameraLatitude = 32.2857965;
        double cameraZoom = 2.0;
        double cameraPitch = 0.0;
        double cameraBearing = 0.0;
        double cameraTargetHeightMeters = 0.0;
        bool cameraUpdateQueued = false;
        float terrainExaggeration = 1.0f;
        bool terrainExaggerationUpdateQueued = false;

        int lastTouchAction = 0;
        int lastPointerCount = 0;
        float lastTouchX0 = 0.0f;
        float lastTouchY0 = 0.0f;
        float lastTouchX1 = 0.0f;
        float lastTouchY1 = 0.0f;

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
        std::unordered_map<std::string, MvtRenderTileConfig> mvtTiles;
        std::unordered_map<std::string, std::vector<entt::entity>> mvtEntities;
        std::unordered_map<std::string, std::pair<double, double>> pointAnnotations;
        std::unordered_map<std::string, LabelAnnotationConfig> labelAnnotations;
        std::unordered_map<std::string, entt::entity> labelEntities;
        std::unordered_map<std::string, std::vector<entt::entity>> drawEntities;
        std::optional<LocationIndicatorConfig> locationIndicator;
        std::vector<entt::entity> locationEntities;
        JavaVM* javaVm = nullptr;
        jobject statusCallback = nullptr;
        jmethodID statusCallbackMethod = nullptr;
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

    std::vector<std::string> stringsFromJArray(JNIEnv* env, jobjectArray array)
    {
        std::vector<std::string> result;
        if (!array)
        {
            return result;
        }
        const auto count = env->GetArrayLength(array);
        result.reserve(static_cast<std::size_t>(count));
        for (jsize i = 0; i < count; ++i)
        {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
            result.push_back(jstringToString(env, value));
            if (value)
            {
                env->DeleteLocalRef(value);
            }
        }
        return result;
    }

    std::vector<int> intsFromJArray(JNIEnv* env, jintArray array)
    {
        std::vector<int> result;
        if (!array)
        {
            return result;
        }
        const auto count = env->GetArrayLength(array);
        std::vector<jint> values(static_cast<std::size_t>(count));
        env->GetIntArrayRegion(array, 0, count, values.data());
        result.reserve(static_cast<std::size_t>(count));
        for (jint value : values)
        {
            result.push_back(static_cast<int>(value));
        }
        return result;
    }

    std::vector<double> doublesFromJArray(JNIEnv* env, jdoubleArray array)
    {
        std::vector<double> result;
        if (!array)
        {
            return result;
        }
        const auto count = env->GetArrayLength(array);
        result.resize(static_cast<std::size_t>(count));
        env->GetDoubleArrayRegion(array, 0, count, result.data());
        return result;
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
Java_com_vectorra_maps_internal_VectorraNative_setResourcePath(JNIEnv* env, jobject, jlong handle, jstring path)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setResourcePath(jstringToString(env, path));
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
    jdouble targetHeightMeters)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->setCamera(longitude, latitude, zoom, pitch, bearing, targetHeightMeters);
    }
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_vectorra_maps_internal_VectorraNative_renderMvtTile(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring sourceId,
    jstring layerId,
    jint tileZ,
    jint tileX,
    jint tileY,
    jstring styleKind,
    jboolean visible,
    jint color,
    jfloat opacity,
    jfloat widthPixels,
    jfloat radiusPixels,
    jfloat textSizeSp,
    jobjectArray featureIds,
    jobjectArray sourceLayers,
    jintArray geometryTypes,
    jintArray coordinateOffsets,
    jdoubleArray coordinates,
    jintArray ringOffsets,
    jintArray ringEnds)
{
    std::string nativeHandle;
    if (auto* engine = fromHandle(handle))
    {
        MvtRenderTileConfig config;
        config.sourceId = jstringToString(env, sourceId);
        config.layerId = jstringToString(env, layerId);
        config.tileZ = static_cast<int>(tileZ);
        config.tileX = static_cast<int>(tileX);
        config.tileY = static_cast<int>(tileY);
        config.style = MvtRenderStyleConfig{
            jstringToString(env, styleKind),
            visible == JNI_TRUE,
            static_cast<int>(color),
            opacity,
            widthPixels,
            radiusPixels,
            textSizeSp};
        config.featureIds = stringsFromJArray(env, featureIds);
        config.sourceLayers = stringsFromJArray(env, sourceLayers);
        config.geometryTypes = intsFromJArray(env, geometryTypes);
        config.coordinateOffsets = intsFromJArray(env, coordinateOffsets);
        config.coordinates = doublesFromJArray(env, coordinates);
        config.ringOffsets = intsFromJArray(env, ringOffsets);
        config.ringEnds = intsFromJArray(env, ringEnds);
        nativeHandle = engine->renderMvtTile(config);
    }
    return env->NewStringUTF(nativeHandle.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_removeMvtTile(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring nativeTileHandle)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->removeMvtTile(jstringToString(env, nativeTileHandle));
    }
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

extern "C" JNIEXPORT void JNICALL
Java_com_vectorra_maps_internal_VectorraNative_onTouch(
    JNIEnv*, jobject, jlong handle, jint action, jint pointerCount, jfloat x0, jfloat y0, jfloat x1, jfloat y1)
{
    if (auto* engine = fromHandle(handle))
    {
        engine->onTouch(action, pointerCount, x0, y0, x1, y1);
    }
}
