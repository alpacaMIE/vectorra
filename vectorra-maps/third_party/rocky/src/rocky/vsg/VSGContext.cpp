/**
 * rocky c++
 * Copyright 2025 Pelican Mapping
 * MIT License
 */
#include "VSGContext.h"
#include "VSGUtils.h"
#include <rocky/DiskCache.h>
#include <rocky/Image.h>
#include <rocky/URI.h>
#include <rocky/GeoExtent.h>
#include <vsg/app/CompileTraversal.h>
#include <vsg/io/mem_stream.h>
#include <zlib.h>
#include <cstring>
#include <cstdlib>
#include <array>
#include <filesystem>

#include <spdlog/sinks/stdout_color_sinks.h>

#ifdef __ANDROID__
#include <android/log.h>
#include <atomic>
#ifdef ROCKY_ANDROID_WEBP
#include <webp/decode.h>
#endif
#ifdef ROCKY_ANDROID_TURBOJPEG
#include <turbojpeg.h>
#endif
#endif

ROCKY_ABOUT(vulkanscenegraph, VSG_VERSION_STRING)

#ifdef ROCKY_HAS_VSGXCHANGE
#include <vsgXchange/all.h>
ROCKY_ABOUT(vsgxchange, VSGXCHANGE_VERSION_STRING)
#endif

#ifdef ROCKY_HAS_GDAL
#include <rocky/GDAL.h>
#endif

using namespace ROCKY_NAMESPACE;
using namespace ROCKY_NAMESPACE::detail;

namespace
{
    uint32_t readPngUint32(const unsigned char* p)
    {
        return
            (static_cast<uint32_t>(p[0]) << 24) |
            (static_cast<uint32_t>(p[1]) << 16) |
            (static_cast<uint32_t>(p[2]) << 8) |
            static_cast<uint32_t>(p[3]);
    }

    unsigned char paethPredictor(unsigned char a, unsigned char b, unsigned char c)
    {
        const int p = static_cast<int>(a) + static_cast<int>(b) - static_cast<int>(c);
        const int pa = std::abs(p - static_cast<int>(a));
        const int pb = std::abs(p - static_cast<int>(b));
        const int pc = std::abs(p - static_cast<int>(c));

        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    Result<std::shared_ptr<Image>> readPngWithZlib(std::istream& stream)
    {
        stream.clear();
        stream.seekg(0, std::ios::end);
        const auto len = stream.tellg();
        if (len <= 0)
        {
            stream.clear();
            stream.seekg(0, std::ios::beg);
            return Failure(Failure::ResourceUnavailable, "png stream is empty");
        }

        std::vector<unsigned char> data(static_cast<std::size_t>(len));
        stream.seekg(0, std::ios::beg);
        stream.read(reinterpret_cast<char*>(data.data()), len);
        stream.clear();
        stream.seekg(0, std::ios::beg);

        static constexpr unsigned char signature[8] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
        if (data.size() < 33 || std::memcmp(data.data(), signature, sizeof(signature)) != 0)
        {
            return Failure(Failure::ResourceUnavailable, "invalid png signature");
        }

        uint32_t width = 0u;
        uint32_t height = 0u;
        unsigned char bitDepth = 0u;
        unsigned char colorType = 0u;
        unsigned char compression = 0u;
        unsigned char filter = 0u;
        unsigned char interlace = 0u;
        std::vector<unsigned char> idat;
        std::vector<std::array<unsigned char, 4>> palette;
        std::vector<unsigned char> paletteAlpha;

        std::size_t offset = 8u;
        while (offset + 12u <= data.size())
        {
            const uint32_t chunkLength = readPngUint32(data.data() + offset);
            offset += 4u;
            if (offset + 4u > data.size())
                break;

            const char* type = reinterpret_cast<const char*>(data.data() + offset);
            offset += 4u;
            if (offset + chunkLength + 4u > data.size())
            {
                return Failure(Failure::ResourceUnavailable, "png chunk exceeds stream length");
            }

            const unsigned char* chunk = data.data() + offset;
            if (std::memcmp(type, "IHDR", 4) == 0 && chunkLength >= 13u)
            {
                width = readPngUint32(chunk);
                height = readPngUint32(chunk + 4);
                bitDepth = chunk[8];
                colorType = chunk[9];
                compression = chunk[10];
                filter = chunk[11];
                interlace = chunk[12];
            }
            else if (std::memcmp(type, "IDAT", 4) == 0)
            {
                idat.insert(idat.end(), chunk, chunk + chunkLength);
            }
            else if (std::memcmp(type, "PLTE", 4) == 0)
            {
                if (chunkLength % 3u != 0u)
                    return Failure(Failure::ResourceUnavailable, "invalid png palette");

                const auto entries = chunkLength / 3u;
                palette.clear();
                palette.reserve(entries);
                for (uint32_t i = 0u; i < entries; ++i)
                {
                    palette.push_back({
                        chunk[i * 3u + 0u],
                        chunk[i * 3u + 1u],
                        chunk[i * 3u + 2u],
                        255u
                    });
                }
            }
            else if (std::memcmp(type, "tRNS", 4) == 0)
            {
                paletteAlpha.assign(chunk, chunk + chunkLength);
            }
            else if (std::memcmp(type, "IEND", 4) == 0)
            {
                break;
            }

            offset += chunkLength + 4u; // skip data and CRC
        }

        if (width == 0u || height == 0u || idat.empty())
            return Failure(Failure::ResourceUnavailable, "png missing IHDR or IDAT");

        if (bitDepth != 8u || compression != 0u || filter != 0u || interlace != 0u)
            return Failure(Failure::ResourceUnavailable, "unsupported png encoding");

        Image::PixelFormat imageFormat = Image::UNDEFINED;
        unsigned inputComponents = 0u;
        unsigned outputComponents = 0u;
        switch (colorType)
        {
        case 0u:
            imageFormat = Image::R8_UNORM;
            inputComponents = 1u;
            outputComponents = 1u;
            break;
        case 2u:
            imageFormat = Image::R8G8B8_UNORM;
            inputComponents = 3u;
            outputComponents = 3u;
            break;
        case 3u:
            if (palette.empty())
                return Failure(Failure::ResourceUnavailable, "png palette missing");

            for (std::size_t i = 0u; i < paletteAlpha.size() && i < palette.size(); ++i)
            {
                palette[i][3] = paletteAlpha[i];
            }
            imageFormat = Image::R8G8B8A8_UNORM;
            inputComponents = 1u;
            outputComponents = 4u;
            break;
        case 6u:
            imageFormat = Image::R8G8B8A8_UNORM;
            inputComponents = 4u;
            outputComponents = 4u;
            break;
        default:
            return Failure(Failure::ResourceUnavailable, "unsupported png color type");
        }

        const std::size_t inputRowBytes = static_cast<std::size_t>(width) * inputComponents;
        const std::size_t outputRowBytes = static_cast<std::size_t>(width) * outputComponents;
        const std::size_t inflatedBytes = static_cast<std::size_t>(height) * (inputRowBytes + 1u);
        if (inputRowBytes == 0u || outputRowBytes == 0u || inflatedBytes == 0u)
            return Failure(Failure::ResourceUnavailable, "invalid png dimensions");

        std::vector<unsigned char> inflated(inflatedBytes);
        uLongf destinationLength = static_cast<uLongf>(inflated.size());
        const int zResult = uncompress(
            inflated.data(),
            &destinationLength,
            idat.data(),
            static_cast<uLong>(idat.size()));

        if (zResult != Z_OK || destinationLength < inflatedBytes)
            return Failure(Failure::ResourceUnavailable, "png zlib inflate failed");

        std::vector<unsigned char> decoded(static_cast<std::size_t>(height) * inputRowBytes);
        auto image = Image::create(imageFormat, width, height);

        std::size_t src = 0u;
        for (uint32_t y = 0u; y < height; ++y)
        {
            const unsigned char filterType = inflated[src++];
            auto* row = decoded.data() + static_cast<std::size_t>(y) * inputRowBytes;
            const auto* previousRow = y > 0u ? row - inputRowBytes : nullptr;

            for (std::size_t x = 0u; x < inputRowBytes; ++x)
            {
                const unsigned char raw = inflated[src++];
                const unsigned char left = x >= inputComponents ? row[x - inputComponents] : 0u;
                const unsigned char up = previousRow ? previousRow[x] : 0u;
                const unsigned char upLeft = previousRow && x >= inputComponents ? previousRow[x - inputComponents] : 0u;

                switch (filterType)
                {
                case 0u:
                    row[x] = raw;
                    break;
                case 1u:
                    row[x] = static_cast<unsigned char>(raw + left);
                    break;
                case 2u:
                    row[x] = static_cast<unsigned char>(raw + up);
                    break;
                case 3u:
                    row[x] = static_cast<unsigned char>(raw + static_cast<unsigned char>((static_cast<unsigned>(left) + static_cast<unsigned>(up)) / 2u));
                    break;
                case 4u:
                    row[x] = static_cast<unsigned char>(raw + paethPredictor(left, up, upLeft));
                    break;
                default:
                    return Failure(Failure::ResourceUnavailable, "unsupported png filter type");
                }
            }
        }

        auto* out = image->data<unsigned char>();
        if (colorType == 3u)
        {
            for (std::size_t i = 0u; i < decoded.size(); ++i)
            {
                const auto index = decoded[i];
                if (index >= palette.size())
                    return Failure(Failure::ResourceUnavailable, "png palette index out of range");

                const auto& rgba = palette[index];
                out[i * 4u + 0u] = rgba[0];
                out[i * 4u + 1u] = rgba[1];
                out[i * 4u + 2u] = rgba[2];
                out[i * 4u + 3u] = rgba[3];
            }
        }
        else
        {
            std::memcpy(out, decoded.data(), decoded.size());
        }

        return image;
    }

    // custom VSG logger that redirects to spdlog.
    class VSG_to_Spdlog_Logger : public vsg::Inherit<vsg::Logger, VSG_to_Spdlog_Logger>
    {
    public:
        std::shared_ptr<spdlog::logger> vsg_logger;

        VSG_to_Spdlog_Logger()
        {
            auto sink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
            vsg_logger = std::make_shared<spdlog::logger>("vsg", sink);
            vsg_logger->set_pattern("%^[%n %l]%$ %v");
        }

    protected:
        const char* ignore = "[rocky.ignore]";

        void debug_implementation(const std::string_view& message) override {
            if (message.rfind(ignore, 0) != 0) {
                vsg_logger->set_level(Log()->level());
                vsg_logger->debug(message);
            }
        }
        void info_implementation(const std::string_view& message) override {
            if (message.rfind(ignore, 0) != 0) {
                vsg_logger->set_level(Log()->level());
                vsg_logger->info(message);
            }
        }
        void warn_implementation(const std::string_view& message) override {
            if (message.rfind(ignore, 0) != 0) {
                vsg_logger->set_level(Log()->level());
                vsg_logger->warn(message);
            }
        }
        void error_implementation(const std::string_view& message) override {
            if (message.rfind(ignore, 0) != 0) {
                vsg_logger->set_level(Log()->level());
                vsg_logger->error(message);
            }
        }
        void fatal_implementation(const std::string_view& message) override {
            if (message.rfind(ignore, 0) != 0) {
                vsg_logger->set_level(Log()->level());
                vsg_logger->critical(message);
            }
        }
    };

    // recursive search for a vsg::ReaderWriters that matches the extension
    // TODO: expand to include 'protocols' I guess
    vsg::ref_ptr<vsg::ReaderWriter> findReaderWriter(const std::string& extension, const vsg::ReaderWriters& readerWriters)
    {
        vsg::ref_ptr<vsg::ReaderWriter> output;

        for (auto& rw : readerWriters)
        {
            vsg::ReaderWriter::Features features;
            auto crw = dynamic_cast<vsg::CompositeReaderWriter*>(rw.get());
            if (crw)
            {
                output = findReaderWriter(extension, crw->readerWriters);
            }
            else if (rw->getFeatures(features))
            {
                auto j = features.extensionFeatureMap.find(extension);

                if (j != features.extensionFeatureMap.end())
                {
                    if (j->second & vsg::ReaderWriter::FeatureMask::READ_ISTREAM)
                    {
                        output = rw;
                    }
                }
            }

            if (output)
                break;
        }

        return output;
    }

#ifdef ROCKY_HAS_GDAL
    /**
    * VSG reader-writer that uses GDAL to read some image formats that are
    * not supported by vsgXchange
    */
    class GDAL_VSG_ReaderWriter : public vsg::Inherit<vsg::ReaderWriter, GDAL_VSG_ReaderWriter>
    {
    public:
        Features _features;

        GDAL_VSG_ReaderWriter()
        {
            _features.extensionFeatureMap[vsg::Path(".webp")] = READ_ISTREAM;
            _features.extensionFeatureMap[vsg::Path(".tif")] = READ_ISTREAM;
            _features.extensionFeatureMap[vsg::Path(".jpg")] = READ_ISTREAM;
            _features.extensionFeatureMap[vsg::Path(".png")] = READ_ISTREAM;
        }

        bool getFeatures(Features& out) const override
        {
            out = _features;
            return true;
        }

        //! Memory-pointer overload: used by loaders that read embedded data
        //! (e.g. vsgXchange::gltf decoding textures from a glTF bufferView).
        //! Without it the base class returns null and embedded webp/png data
        //! would silently skip GDAL.
        vsg::ref_ptr<vsg::Object> read(const uint8_t* ptr, size_t size, vsg::ref_ptr<const vsg::Options> options = {}) const override
        {
            vsg::mem_stream in(ptr, size);
            return read(in, options);
        }

        vsg::ref_ptr<vsg::Object> read(std::istream& in, vsg::ref_ptr<const vsg::Options> options = {}) const override
        {
            if (!options || _features.extensionFeatureMap.count(options->extensionHint) == 0)
                return {};

            std::stringstream buf;
            buf << in.rdbuf() << std::flush;
            std::string data = buf.str();

            std::string gdal_driver =
                options->extensionHint.string() == ".webp" ? "webp" :
                options->extensionHint.string() == ".tif" ? "gtiff" :
                options->extensionHint.string() == ".jpg" ? "jpeg" :
                options->extensionHint.string() == ".png" ? "png" :
                "";

            auto result = GDAL_detail::readImage((unsigned char*)data.c_str(), data.length(), gdal_driver);

            if (result.ok())
            {
                auto image = result.value();

                // Expand 24-bit RGB to RGBA: VK_FORMAT_R8G8B8_* is not a
                // sampleable texture format on most GPUs, so a 3-channel
                // image would fail VkImage creation downstream (seen with
                // WebP textures embedded in glTF tile content).
                if (image && (image->pixelFormat() == Image::R8G8B8_UNORM ||
                              image->pixelFormat() == Image::R8G8B8_SRGB))
                {
                    auto rgba = std::make_shared<Image>(
                        image->pixelFormat() == Image::R8G8B8_SRGB
                            ? Image::R8G8B8A8_SRGB : Image::R8G8B8A8_UNORM,
                        image->width(), image->height(), image->depth());
                    for (unsigned r = 0; r < image->depth(); ++r)
                        for (unsigned t = 0; t < image->height(); ++t)
                            for (unsigned s = 0; s < image->width(); ++s)
                                rgba->write(image->read(s, t, r), s, t, r);
                    image = rgba;
                }

                return moveImageToVSG(image);
            }
            else
                return { };
        }
    };
#endif

#if defined(__ANDROID__) && defined(ROCKY_ANDROID_WEBP)
    /**
    * VSG reader-writer that decodes WebP images with libwebp. The Android
    * build carries no GDAL, and neither vsgXchange nor stb decodes WebP —
    * without this, WebP textures embedded in glTF/b3dm tile content would
    * fall back to raw bytes and fail VkImage creation.
    */
    class WebP_VSG_ReaderWriter : public vsg::Inherit<vsg::ReaderWriter, WebP_VSG_ReaderWriter>
    {
    public:
        Features _features;

        WebP_VSG_ReaderWriter()
        {
            _features.extensionFeatureMap[vsg::Path(".webp")] = READ_ISTREAM;
        }

        bool getFeatures(Features& out) const override
        {
            out = _features;
            return true;
        }

        //! Memory-pointer overload: used by loaders reading embedded data
        //! (e.g. vsgXchange::gltf decoding textures from a glTF bufferView).
        vsg::ref_ptr<vsg::Object> read(const uint8_t* ptr, size_t size, vsg::ref_ptr<const vsg::Options> options = {}) const override
        {
            if (!options || options->extensionHint != vsg::Path(".webp"))
                return {};

            int width = 0, height = 0;
            if (!WebPGetInfo(ptr, size, &width, &height) || width <= 0 || height <= 0)
                return {};

            // RGBA output: 24-bit RGB is not a sampleable texture format on
            // most GPUs, so always expand to 4 channels.
            auto image = Image::create(
                Image::R8G8B8A8_UNORM,
                static_cast<unsigned>(width), static_cast<unsigned>(height));

            const size_t outBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
            if (!WebPDecodeRGBAInto(ptr, size, image->data<uint8_t>(), outBytes, width * 4))
                return {};

            return moveImageToVSG(image);
        }

        vsg::ref_ptr<vsg::Object> read(std::istream& in, vsg::ref_ptr<const vsg::Options> options = {}) const override
        {
            std::stringstream buf;
            buf << in.rdbuf() << std::flush;
            const std::string data = buf.str();
            return read(reinterpret_cast<const uint8_t*>(data.data()), data.size(), options);
        }
    };
#endif

    std::string inferContentTypeFromStream(std::istream& stream)
    {
        // Get the length of the stream
        stream.seekg(0, std::ios::end);
        unsigned int len = stream.tellg();
        stream.seekg(0, std::ios::beg);

        if (len < 16) return {};

        // Read a 16 byte header
        char data[16];
        stream.read(data, 16);

        // Reset reading
        stream.seekg(0, std::ios::beg);

        return URI::inferContentType(std::string(data, 16));
    }

#if defined(__ANDROID__) && defined(ROCKY_ANDROID_TURBOJPEG)
    Result<std::shared_ptr<Image>> readJpegWithTurboJpeg(std::istream& stream)
    {
        stream.seekg(0, std::ios::end);
        auto len = stream.tellg();
        stream.seekg(0, std::ios::beg);
        if (len <= 0)
        {
            return Failure(Failure::ResourceUnavailable, "JPEG stream is empty");
        }

        std::string data(static_cast<std::size_t>(len), '\0');
        stream.read(data.data(), len);
        stream.seekg(0, std::ios::beg);

        tjhandle handle = tjInitDecompress();
        if (!handle)
        {
            return Failure(Failure::ServiceUnavailable, "turbojpeg init failed");
        }

        int width = 0;
        int height = 0;
        int jpegSubsamp = 0;
        int jpegColorspace = 0;
        int result = tjDecompressHeader3(
            handle,
            reinterpret_cast<const unsigned char*>(data.data()),
            static_cast<unsigned long>(data.size()),
            &width,
            &height,
            &jpegSubsamp,
            &jpegColorspace);
        if (result != 0 || width <= 0 || height <= 0)
        {
            std::string error = tjGetErrorStr2(handle);
            tjDestroy(handle);
            return Failure(Failure::ResourceUnavailable, "turbojpeg header failed: " + error);
        }

        auto image = Image::create(Image::R8G8B8A8_UNORM, static_cast<unsigned>(width), static_cast<unsigned>(height));
        result = tjDecompress2(
            handle,
            reinterpret_cast<const unsigned char*>(data.data()),
            static_cast<unsigned long>(data.size()),
            image->data<unsigned char>(),
            width,
            0,
            height,
            TJPF_RGBA,
            TJFLAG_FASTDCT);

        if (result != 0)
        {
            std::string error = tjGetErrorStr2(handle);
            tjDestroy(handle);
            return Failure(Failure::ResourceUnavailable, "turbojpeg decode failed: " + error);
        }

        tjDestroy(handle);
        static std::atomic_int decodeLogs = 0;
        int count = decodeLogs.fetch_add(1);
        if (count < 12)
        {
            __android_log_print(
                ANDROID_LOG_INFO,
                "rocky_jni",
                "turbojpeg decoded image=%dx%d bytes=%zu",
                width,
                height,
                data.size());
        }
        return image;
    }
#endif

    bool foundShaders(const vsg::Paths& searchPaths)
    {
        auto options = vsg::Options::create();
        options->paths = searchPaths;
        auto found = vsg::findFile(vsg::Path("shaders/rocky.terrain.vert"), options);
        return !found.empty();
    }    
    
    /**
    * An update operation that maintains a priroity queue for update tasks.
    * This sits in the VSG viewer's update operations queue indefinitely
    * and runs once per frame. It chooses the highest priority task in its
    * queue and runs it. It will run one task per frame so that we do not
    * risk frame drops. It will automatically discard any tasks that have
    * been abandoned (no Future exists).
    */
    struct PriorityUpdateQueue : public vsg::Inherit<vsg::Operation, PriorityUpdateQueue>
    {
        std::mutex _mutex;

        struct Task {
            Task() = default;
            Task(vsg::Operation* a, std::function<float()> b) : function(a), get_priority(b) {}
            vsg::ref_ptr<vsg::Operation> function;
            std::function<float()> get_priority;
        };
        std::vector<Task> _queue;

        // runs one task per frame.
        void run() override
        {
            if (!_queue.empty())
            {
                Task task;
                {
                    std::scoped_lock lock(_mutex);

                    // sort from low to high priority
                    std::sort(_queue.begin(), _queue.end(),
                        [](const Task& lhs, const Task& rhs)
                        {
                            if (lhs.get_priority == nullptr)
                                return false;
                            else if (rhs.get_priority == nullptr)
                                return true;
                            else
                                return lhs.get_priority() < rhs.get_priority();
                        }
                    );

                    while (!_queue.empty())
                    {
                        // pop the highest priority off the back.
                        task = _queue.back();
                        _queue.pop_back();

                        // check for cancelation - if the task is already canceled, 
                        // discard it and fetch the next one.
                        auto po = dynamic_cast<Cancelable*>(task.function.get());
                        if (po == nullptr || !po->canceled())
                            break;
                        else
                            task = { };
                    }
                }

                if (task.function)
                {
                    task.function->run();
                }
            }
        }
    };

    struct SimpleUpdateOperation : public vsg::Inherit<vsg::Operation, SimpleUpdateOperation>
    {
        std::function<void(VSGContext)> _function;
        VSGContext _vsgcontext;

        SimpleUpdateOperation(std::function<void(VSGContext)> function, VSGContext vsgcontext) :
            _function(function),
            _vsgcontext(vsgcontext) { }

        void run() override
        {
            _function(_vsgcontext);
        };
    };
}




VSGContextImpl::VSGContextImpl(vsg::ref_ptr<vsg::Viewer> viewer) :
    rocky::ContextImpl(),
    _viewer(viewer)
{
    if (!_viewer) _viewer = vsg::Viewer::create();
    int argc = 0;
    const char* argv[1] = { "rocky" };
    ctor(argc, (char**)argv);
}

VSGContextImpl::VSGContextImpl(vsg::ref_ptr<vsg::Viewer> viewer, int& argc, char** argv) :
    rocky::ContextImpl(),
    _viewer(viewer)
{
    if (!_viewer) _viewer = vsg::Viewer::create();
    ctor(argc, argv);
}

void
VSGContextImpl::ctor(int& argc, char** argv)
{
    vsg::CommandLine args(&argc, argv);

    readerWriterOptions = vsg::Options::create();

    shaderCompileSettings = vsg::ShaderCompileSettings::create();

    _priorityUpdateQueue = PriorityUpdateQueue::create();

    // initialize the deferred deletion collection.
    // a large number of frames ensures objects will be safely destroyed and
    // and we won't have too many deletions per frame.
    _gc.resize(8);

    // big capacity for this so we can copy it without worry about reallocating.
    activeViewIDs.reserve(128);

    args.read(readerWriterOptions);

    // redirect the VSG logger to our spdlog
    vsg::Logger::instance() = new VSG_to_Spdlog_Logger();

    // set the logging level from the command line
    std::string log_level;
    if (args.read("--log-level", log_level))
    {
        if (log_level == "debug") Log()->set_level(spdlog::level::debug);
        else if (log_level == "info") Log()->set_level(spdlog::level::info);
        else if (log_level == "warn") Log()->set_level(spdlog::level::warn);
        else if (log_level == "error") Log()->set_level(spdlog::level::err);
        else if (log_level == "critical") Log()->set_level(spdlog::level::critical);
        else if (log_level == "off") Log()->set_level(spdlog::level::off);
    }

#ifdef ROCKY_HAS_GDAL
    readerWriterOptions->add(GDAL_VSG_ReaderWriter::create());
#endif

#if defined(__ANDROID__) && defined(ROCKY_ANDROID_WEBP)
    readerWriterOptions->add(WebP_VSG_ReaderWriter::create());
#endif

#ifdef ROCKY_HAS_VSGXCHANGE
    // Adds all the readerwriters in vsgxchange to the options data.
    readerWriterOptions->add(vsgXchange::all::create());
#ifdef __ANDROID__
    __android_log_print(
        ANDROID_LOG_INFO,
        "rocky_jni",
        "VSGContext registered vsgXchange::all readerWriters=%zu",
        readerWriterOptions->readerWriters.size());
#endif
#elif defined(__ANDROID__)
    __android_log_print(
        ANDROID_LOG_WARN,
        "rocky_jni",
        "VSGContext built without ROCKY_HAS_VSGXCHANGE");
#endif

    // For system fonts
    readerWriterOptions->paths.push_back("C:/Windows/Fonts");
    readerWriterOptions->paths.push_back("/usr/share/fonts/truetype");
    readerWriterOptions->paths.push_back("/etc/fonts");
    readerWriterOptions->paths.push_back("/usr/local/share/rocky/data");

    // establish search paths for shaders and data:
    auto vsgPaths = vsg::getEnvPaths("VSG_FILE_PATH");
    searchPaths.insert(searchPaths.end(), vsgPaths.begin(), vsgPaths.end());

    auto rockyPaths = vsg::getEnvPaths("ROCKY_FILE_PATH");
    searchPaths.insert(searchPaths.end(), rockyPaths.begin(), rockyPaths.end());

    // add some default places to look for shaders and resources, relative to the executable.
    const char* relative_paths_to_add[] = {
        "../share/rocky",                        // running from standard install location
        "../../../../../src/rocky/vsg",          // running from visual studio with build folder inside repo
        "../../../../../repo/src/rocky/vsg",     // running from visual studio with a repo folder :)
        "../../../../src/rocky/vsg"              // running from visual studio with an in-source build :(
    };

    auto exec_path = std::filesystem::path(getExecutableLocation());
    Log()->debug("Running from: {}", exec_path.string());

    for (auto& relative_path : relative_paths_to_add)
    {
        auto path = (exec_path.remove_filename() / relative_path).lexically_normal();
        if (!path.empty())
            searchPaths.push_back(vsg::Path(path.generic_string()));
    }

    searchPaths.emplace_back("/usr/local/share/rocky");

    if (!foundShaders(searchPaths))
    {
        Log()->warn("Trouble: Rocky may not be able to find its shaders. "
            "Consider setting one of the environment variables VSG_FILE_PATH or ROCKY_FILE_PATH.");

        status = Failure(Failure::ResourceUnavailable, "Cannot find shaders - check your ROCKY_FILE_PATH");
        return;
    }

    Log()->debug("Search paths:");
    for (auto& path : searchPaths)
        Log()->debug("  {}", path.string());

    // Install a readImage function that uses the VSG facility
    // for reading data. We may want to subclass Image with something like
    // NativeImage that just hangs on to the vsg::Data instead of
    // stripping it out and later converting it back; or that only transcodes
    // it if it needs to. vsg::read_cast() might do some internal caching
    // as well -- need to look into that.
    io.services().readImageFromURI = [](const std::string& location, const rocky::IOOptions& io)
    {
        auto result = URI(location).read(io);
        if (result.ok())
        {
            std::istringstream buf(result.value().content.data);
            return io.services().readImageFromStream(buf, result.value().content.type, io);
        }
        return Result<std::shared_ptr<Image>>(Failure(Failure::ResourceUnavailable, "Data is null"));
    };

    // map of mime-types to extensions that VSG can understand
    static const std::unordered_map<std::string, std::string> ext_for_mime_type = {
        { "image/bmp", ".bmp" },
        { "image/gif", ".gif" },
        { "image/jpg", ".jpg" },
        { "image/jpeg", ".jpg" },
        { "image/png", ".png" },
        { "image/tga", ".tga" },
        { "image/tif", ".tif" },
        { "image/tiff", ".tif" },
        { "image/webp", ".webp" }
    };

    // To read from a stream, we have to search all the VS readerwriters to
    // find one that matches the 'extension' we want. We also have to put that
    // extension in the options structure as a hint.
    io.services().readImageFromStream = [options(readerWriterOptions)](std::istream& location, std::string contentType, const rocky::IOOptions& io) -> Result<std::shared_ptr<Image>>
        {
            // try the mime-type mapping:
            auto i = ext_for_mime_type.find(contentType);
            if (i != ext_for_mime_type.end())
            {
                auto rw = findReaderWriter(i->second, options->readerWriters);
                if (rw != nullptr)
                {
#ifdef __ANDROID__
                    static std::atomic_int mimeReaderLogs = 0;
                    int count = mimeReaderLogs.fetch_add(1);
                    if (count < 12)
                    {
                        __android_log_print(
                            ANDROID_LOG_INFO,
                            "rocky_jni",
                            "VSG image reader contentType=%s extension=%s",
                            contentType.c_str(),
                            i->second.c_str());
                    }
#endif
                    auto local_options = vsg::Options::create(*options);
                    local_options->extensionHint = i->second;
                    auto result = rw->read_cast<vsg::Data>(location, local_options);
                    return makeImageFromVSG(result);
                }
#ifdef __ANDROID__
                else
                {
                    static std::atomic_int missingReaderLogs = 0;
                    int count = missingReaderLogs.fetch_add(1);
                    if (count < 12)
                    {
                        __android_log_print(
                            ANDROID_LOG_WARN,
                            "rocky_jni",
                            "VSG image reader missing contentType=%s extension=%s",
                            contentType.c_str(),
                            i->second.c_str());
                    }
                }
#endif
            }

            // mime-type didn't work; try the content type directly as an extension
            if (!contentType.empty())
            {
                auto contentTypeAsExtension = contentType[0] != '.' ? ("." + contentType) : contentType;
                auto rw = findReaderWriter(contentTypeAsExtension, options->readerWriters);
                if (rw != nullptr)
                {
                    auto local_options = vsg::Options::create(*options);
                    local_options->extensionHint = contentTypeAsExtension;
                    auto result = rw->read_cast<vsg::Data>(location, local_options);
                    return makeImageFromVSG(result);
                }
            }

            // last resort, try checking the data itself
            // TODO: maybe this should be a FIRST resort?
            auto inferredContentType = inferContentTypeFromStream(location);
            if (!inferredContentType.empty())
            {
                auto i = ext_for_mime_type.find(inferredContentType);
                if (i != ext_for_mime_type.end())
                {
                    auto rw = findReaderWriter(i->second, options->readerWriters);
                    if (rw != nullptr)
                    {
                        auto local_options = vsg::Options::create(*options);
                        local_options->extensionHint = i->second;
                        auto result = rw->read_cast<vsg::Data>(location, local_options);
                        return makeImageFromVSG(result);
                    }
                }
            }

#if defined(__ANDROID__) && defined(ROCKY_ANDROID_TURBOJPEG)
            if (contentType == "image/jpg" ||
                contentType == "image/jpeg" ||
                inferredContentType == "image/jpg" ||
                inferredContentType == "image/jpeg")
            {
                auto result = readJpegWithTurboJpeg(location);
                if (result.ok())
                {
                    return result;
                }

                static std::atomic_int turboJpegFailures = 0;
                int count = turboJpegFailures.fetch_add(1);
                if (count < 12)
                {
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        "rocky_jni",
                        "turbojpeg fallback failed: %s",
                        result.error().string().c_str());
                }
            }
#endif

#ifdef __ANDROID__
            if (contentType == "image/png" || inferredContentType == "image/png")
            {
                auto result = readPngWithZlib(location);
                if (result.ok())
                {
                    return result;
                }

                static std::atomic_int pngFailures = 0;
                int count = pngFailures.fetch_add(1);
                if (count < 12)
                {
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        "rocky_jni",
                        "png fallback failed: %s",
                        result.error().string().c_str());
                }
            }
#endif

            return Failure(Failure::ServiceUnavailable, "No image reader for \"" + contentType + "\"");
        };

    // caches URI request results. Bounded by entry count AND total bytes:
    // entry-count alone lets MB-scale payloads pin gigabytes of raw data.
    io.services().contentCache = std::make_shared<ContentCache>(1024);
    io.services().contentCache->sizeOf = [](const Result<Content>& r) -> size_t {
        return r.ok() ? r.value().data.size() + r.value().type.size() : 64;
    };
    io.services().contentCache->setCapacityBytes(96 * 1024 * 1024);

    // optional persistent disk cache for remote content (set by the host
    // app, e.g. the Android cache directory)
    if (const char* cachePath = ::getenv("ROCKY_CACHE_PATH"); cachePath && cachePath[0])
    {
        auto diskCache = std::make_shared<rocky::detail::DiskContentCache>(cachePath);
        if (diskCache->valid())
        {
            io.services().diskCache = diskCache;
            Log()->info("Disk content cache: {}", cachePath);
        }
    }

    // weak cache of resident image (and elevation) rasters
    io.services().residentImageCache = std::make_shared<ResidentCache<std::string, Image, GeoExtent>>();

    // remembers failed URI requests so we don't repeat them
    io.services().deadpool = std::make_shared<DealpoolService>(4096);


    ROCKY_SOFT_ASSERT_AND_RETURN(_viewer && _viewer->updateOperations, void());

    // install an update operation on the viewer that invokes update() on this object.
    _updateOperation = LambdaOperation::create([this]() { this->update(); });
    _viewer->updateOperations->add(_updateOperation, vsg::UpdateOperations::ALL_FRAMES);
}

VSGContextImpl::~VSGContextImpl()
{
    if (_viewer && _updateOperation)
    {
        _viewer->updateOperations->remove(_updateOperation);
    }

#ifdef ROCKY_DEBUG_MEMCHECK
    Log()->debug("~VSGContextImpl");
#endif
}

vsg::ref_ptr<vsg::Device>
VSGContextImpl::device()
{
    return _viewer->windows().size() > 0 ? _viewer->windows().front()->getOrCreateDevice() : nullptr;
}

VulkanExtensions*
VSGContextImpl::ext()
{
    if (!_vulkanExtensions)
    {
        std::scoped_lock lock(_compileMutex);
        if (!_vulkanExtensions)
        {
            ROCKY_HARD_ASSERT(device());
            _vulkanExtensions = VulkanExtensions::create(device());
        }
    }
    return _vulkanExtensions;
}

vsg::ref_ptr<vsg::CommandGraph>
VSGContextImpl::getComputeCommandGraph() const
{
    return _computeCommandGraph;
}

vsg::ref_ptr<vsg::CommandGraph>
VSGContextImpl::getOrCreateComputeCommandGraph(vsg::ref_ptr<vsg::Device> device, int queueFamily)
{
    if (!_computeCommandGraph && device)
    {
        _computeCommandGraph = vsg::CommandGraph::create(device, queueFamily);
    }
    return _computeCommandGraph;
}

void
VSGContextImpl::scheduleMeteredUpdate(vsg::Operation* operation, std::function<float()> getPriority)
{
    auto pq = dynamic_cast<PriorityUpdateQueue*>(_priorityUpdateQueue.get());
    if (pq)
    {
        std::scoped_lock lock(pq->_mutex);
        pq->_queue.emplace_back(operation, getPriority);
    }
}

void
VSGContextImpl::onNextUpdate(std::function<void(VSGContext)> function)
{
    // makes it illegal to call this function recursively
    std::scoped_lock lock(_functionsToRunDuringNextUpdateMutex);
    _functionsToRunDuringNextUpdate.emplace_back(function);
}

vsg::CompileResult
VSGContextImpl::compile(vsg::ref_ptr<vsg::Object> compilable, bool callerHandlesFailure)
{
    ROCKY_SOFT_ASSERT_AND_RETURN(compilable.valid(), {});
    ROCKY_SOFT_ASSERT_AND_RETURN(_viewer && _viewer->compileManager, {});

#ifdef __ANDROID__
    static std::atomic<int> androidCompileDiagnosticsRemaining{ 3 };
    const bool logCompileDiagnostics = androidCompileDiagnosticsRemaining.load(std::memory_order_relaxed) > 0;
    vsg::CollectResourceRequirements collector;
    std::uint64_t bufferBytes = 0;
    std::uint64_t imageBytes = 0;
    std::uint32_t bufferInfoCount = 0;
    if (logCompileDiagnostics)
    {
        compilable->accept(collector);
        for (const auto& bufferInfo : collector.requirements.dynamicData.bufferInfos)
        {
            ++bufferInfoCount;
            if (bufferInfo && bufferInfo->data)
            {
                bufferBytes += bufferInfo->data->dataSize();
            }
        }
        for (const auto& imageInfo : collector.requirements.dynamicData.imageInfos)
        {
            if (imageInfo && imageInfo->imageView && imageInfo->imageView->image &&
                imageInfo->imageView->image->data)
            {
                imageBytes += imageInfo->imageView->image->data->dataSize();
            }
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            "rocky_jni",
            "VSGContext::compile requirements buffers=%u bufferBytes=%llu images=%zu imageBytes=%llu descriptorSets=%u",
            bufferInfoCount,
            static_cast<unsigned long long>(bufferBytes),
            collector.requirements.dynamicData.imageInfos.size(),
            static_cast<unsigned long long>(imageBytes),
            collector.requirements.computeNumDescriptorSets());
    }
#endif

    // note: this can block (with a fence) until a compile traversal is available.
    // Be sure to group as many compiles together as possible for maximum performance.
#ifdef __ANDROID__
    auto cr = _viewer->compileManager->compile(compilable, [logCompileDiagnostics](vsg::Context& context)
        {
            if (context.device)
            {
                const auto localAvailable = context.device->availableMemory(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                const auto hostAvailable = context.device->availableMemory(
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                const bool hasMemoryBudget = context.device->supportsDeviceExtension(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
                if (logCompileDiagnostics)
                {
                    __android_log_print(
                        ANDROID_LOG_INFO,
                        "rocky_jni",
                        "VSGContext::compile context memoryBudget=%d localAvailable=%llu hostVisibleAvailable=%llu",
                        hasMemoryBudget ? 1 : 0,
                        static_cast<unsigned long long>(localAvailable),
                        static_cast<unsigned long long>(hostAvailable));
                }
            }
            return true;
        });
#else
    auto cr = _viewer->compileManager->compile(compilable);
#endif

#ifdef __ANDROID__
    if (logCompileDiagnostics || cr.result != 0)
    {
        __android_log_print(
            cr.result == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
            "rocky_jni",
            "VSGContext::compile result=%d requiresUpdate=%d containsPagedLOD=%d message=%s",
            cr.result,
            cr.requiresViewerUpdate() ? 1 : 0,
            cr.containsPagedLOD ? 1 : 0,
            cr.message.c_str());
    }
    if (logCompileDiagnostics)
    {
        --androidCompileDiagnosticsRemaining;
    }
#endif

    if (cr)
    {
        // compile results are stored and processed later during update
        std::unique_lock lock(_compileMutex);
        _compileResult.add(cr);
    }
    else if (!callerHandlesFailure)
    {
        // The failed object may hold partially-created GPU state; if it could
        // be in (or enter) the live scene graph, recording would crash, so
        // rendering gets disabled. Callers that keep failed objects out of
        // the scene graph opt out via callerHandlesFailure.
        compileFailed = true;
    }

    return cr;
}

void
VSGContextImpl::dispose(vsg::ref_ptr<vsg::Object> object)
{
    if (object)
    {
        // if the user installed a custom disposer, use it
        if (disposer)
        {
            disposer(object);
        }

        // otherwise use our own
        else
        {
            std::unique_lock lock(_gc_mutex);
            _gc.back().emplace_back(object);
        }

        requestFrame();
    }
}

void
VSGContextImpl::upload(const vsg::BufferInfoList& bufferInfos)
{
    // A way to upload GPU buffers without using the dirty()/DYNAMIC_DATA mechanism,
    // which gets slow with a large number of buffers.
    // inspired by: https://github.com/vsg-dev/VulkanSceneGraph/discussions/1572
    vsg::BufferInfoList validBufferInfos;
    validBufferInfos.reserve(bufferInfos.size());

    for (auto& bi : bufferInfos)
    {
        if (bi && bi->data)
        {
            bi->data->dirty();
            validBufferInfos.emplace_back(bi);
        }
    }

    if (!validBufferInfos.empty())
    {
        auto& tasks = _viewer->recordAndSubmitTasks;
        for (auto& task : tasks)
        {
            task->transferTask->assign(validBufferInfos);
        }

        requestFrame();
    }
}

void
VSGContextImpl::upload(const vsg::ImageInfoList& imageInfos)
{
    // A way to upload images without using the dirty()/DYNAMIC_DATA mechanism,
    // which gets slow with a large number of buffers.
    // inspired by: https://github.com/vsg-dev/VulkanSceneGraph/discussions/1572
    vsg::ImageInfoList validImageInfos;
    validImageInfos.reserve(imageInfos.size());
    for (auto& bi : imageInfos)
    {
        if (bi && bi->imageView && bi->imageView->image && bi->imageView->image->data)
        {
            bi->imageView->image->data->dirty();
            validImageInfos.emplace_back(bi);
        }
    }

    if (!validImageInfos.empty())
    {
        auto& tasks = _viewer->recordAndSubmitTasks;
        for (auto& task : tasks)
        {
            task->transferTask->assign(validImageInfos);
        }

        requestFrame();
    }
}

void
VSGContextImpl::requestFrame()
{
    ++renderRequests;
}

void
VSGContextImpl::update()
{
    // Every-time update functions
    onUpdate.fire(this);

    // One-time update functions
    // Swap-and-run lets update functions re-queue themselves
    if (!_functionsToRunDuringNextUpdate.empty())
    {
        std::vector<std::function<void(VSGContext)>> temp;
        {
            std::scoped_lock lock(_functionsToRunDuringNextUpdateMutex);
            temp.swap(_functionsToRunDuringNextUpdate);
        }
        for (auto& f : temp)
        {
            f(this);
        }
    }

    // One-shot update priority queue
    _priorityUpdateQueue->run();

    {
        std::unique_lock lock(_compileMutex);

        // Merge compilation results
        if (_compileResult)
        {
#ifdef __ANDROID__
            __android_log_print(
                ANDROID_LOG_INFO,
                "rocky_jni",
                "VSGContext::update mergeCompile result=%d requiresUpdate=%d containsPagedLOD=%d message=%s",
                _compileResult.result,
                _compileResult.requiresViewerUpdate() ? 1 : 0,
                _compileResult.containsPagedLOD ? 1 : 0,
                _compileResult.message.c_str());
#endif
            if (_compileResult.requiresViewerUpdate())
            {
                vsg::updateViewer(*_viewer, _compileResult);
            }

        _compileResult.reset();

        requestFrame();
        }
    }

    // process the garbage collector
    {
        std::unique_lock lock(_gc_mutex);
        // unref everything in the oldest collection:
        _gc.front().clear();
        // move the empty collection to the back:
        _gc.emplace_back(std::move(_gc.front()));
        _gc.pop_front();
    }

    // keep the frames running if a database pager is active
    auto& tasks = viewer()->recordAndSubmitTasks;
    if (!tasks.empty() && tasks[0]->databasePager && tasks[0]->databasePager->numActiveRequests > 0)
    {
        requestFrame();
    }
}


// Call this when adding a new rendergraph to the scene.
void
VSGContextImpl::compileRenderGraph(vsg::ref_ptr<vsg::RenderGraph> renderGraph, vsg::ref_ptr<vsg::Window> window)
{
    ROCKY_SOFT_ASSERT_AND_RETURN(renderGraph, void());
    ROCKY_SOFT_ASSERT_AND_RETURN(window, void());
    ROCKY_SOFT_ASSERT_AND_RETURN(viewer(), void());
    ROCKY_SOFT_ASSERT_AND_RETURN(viewer()->compileManager, void());

    auto view = detail::find<vsg::View>(renderGraph);

    ROCKY_SOFT_ASSERT_AND_RETURN(view, void());

    // add this rendergraph's view to the viewer's compile manager.
    viewer()->compileManager->add(*window, vsg::ref_ptr<vsg::View>(view));

    // Compile the new render pass for this view.
    // The lambda idiom is taken from vsgexamples/dynamicviews
    auto result = viewer()->compileManager->compile(renderGraph, 
        [view](vsg::Context& compileContext) -> bool
        {
            return compileContext.view.ref_ptr() == view;
        });

    // if something was compiled, we need to update the viewer:
    if (result.requiresViewerUpdate())
    {
        vsg::updateViewer(*viewer(), result);
    }
}
