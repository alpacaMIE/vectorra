package com.vectorra.maps.network

import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

class TileMemoryCache(
    private val maxBytes: Long
) {
    private data class Entry(
        val body: ByteArray,
        val headers: Map<String, String>,
        val statusCode: Int,
        val size: Long
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var currentBytes = 0L

    init {
        require(maxBytes >= 0L) { "Tile memory cache maxBytes must be greater than or equal to 0." }
    }

    @Synchronized
    fun get(key: String, request: TileRequest): TileResponse? {
        val entry = entries[key] ?: return null
        return TileResponse(
            request = request,
            statusCode = entry.statusCode,
            headers = entry.headers,
            body = entry.body.copyOf(),
            cacheStatus = TileCacheStatus.MEMORY
        )
    }

    @Synchronized
    fun put(key: String, response: TileResponse) {
        if (maxBytes == 0L || response.body.isEmpty()) return
        val size = response.body.size.toLong()
        if (size > maxBytes) return
        entries.remove(key)?.let { currentBytes -= it.size }
        entries[key] = Entry(
            body = response.body.copyOf(),
            headers = response.headers,
            statusCode = response.statusCode,
            size = size
        )
        currentBytes += size
        trimToSize()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0L
    }

    @Synchronized
    fun sizeBytes(): Long = currentBytes

    @Synchronized
    fun entryCount(): Int = entries.size

    private fun trimToSize() {
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val removed = iterator.next()
            currentBytes -= removed.value.size
            iterator.remove()
        }
    }
}

class TileDiskCache(
    private val rootDirectory: File,
    private val maxBytes: Long
) {
    init {
        require(maxBytes >= 0L) { "Tile disk cache maxBytes must be greater than or equal to 0." }
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file.readBytes()
    }

    @Synchronized
    fun put(key: String, body: ByteArray) {
        if (maxBytes == 0L || body.isEmpty() || body.size.toLong() > maxBytes) return
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }
        val file = fileFor(key)
        val temp = File(rootDirectory, "${file.name}.tmp")
        temp.writeBytes(body)
        if (file.exists()) {
            file.delete()
        }
        check(temp.renameTo(file)) { "Failed to write tile cache entry: ${file.absolutePath}" }
        trimToSize()
    }

    @Synchronized
    fun clear() {
        if (rootDirectory.exists()) {
            rootDirectory.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    @Synchronized
    fun sizeBytes(): Long {
        return rootDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun trimToSize() {
        var total = sizeBytes()
        if (total <= maxBytes) return
        val files = rootDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
        for (file in files) {
            if (total <= maxBytes) return
            val length = file.length()
            if (file.delete()) {
                total -= length
            }
        }
    }

    private fun fileFor(key: String): File {
        return File(rootDirectory, "${sha256(key)}.tile")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object TileCacheKeys {
    fun from(request: TileRequest): String {
        val headers = request.headers
            .toSortedMap()
            .entries
            .joinToString("&") { (key, value) -> "$key=$value" }
        return listOf(
            request.method.uppercase(),
            request.sourceId.orEmpty(),
            request.layerId.orEmpty(),
            request.resourceType.name,
            request.url,
            headers
        ).joinToString("|")
    }
}

class CachingTileRequestExecutor(
    private val delegate: TileRequestExecutor,
    private val memoryCache: TileMemoryCache? = null,
    private val diskCache: TileDiskCache? = null,
    private val policy: TileCachePolicy = TileCachePolicy()
) : TileRequestExecutor {
    private val cacheStore = TileCacheStore(memoryCache, diskCache, policy)

    override suspend fun execute(request: TileRequest): TileResponse {
        if (!cacheStore.isCacheable(request)) {
            return delegate.execute(request)
        }

        cacheStore.get(request)?.let { return it }

        val response = delegate.execute(request)
        cacheStore.put(response)
        return response
    }
}

class ResilientTileRequestExecutor(
    private val delegate: TileRequestExecutor,
    private val failureStatusCode: Int = 599
) : TileRequestExecutor {
    override suspend fun execute(request: TileRequest): TileResponse {
        return try {
            delegate.execute(request)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            TileResponse(request = request, statusCode = 499, errorMessage = e.message)
        } catch (e: Exception) {
            TileResponse(request = request, statusCode = failureStatusCode, errorMessage = e.message)
        }
    }
}
