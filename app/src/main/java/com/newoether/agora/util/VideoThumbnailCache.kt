package com.newoether.agora.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Memory + disk cache for file-picker video thumbnails (absolute filesystem paths).
 *
 * Disk files are JPEG under cacheDir; keys include path, mtime, and size so edits invalidate.
 */
class VideoThumbnailCache(context: Context) {
    private val appContext = context.applicationContext
    private val diskDir = File(appContext.cacheDir, "file-picker-video-thumbs").also { it.mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceIn(2_048, 16_384),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val extractLocks = ConcurrentHashMap<String, Mutex>()
    private val extractSemaphore = Semaphore(permits = 2)

    suspend fun getThumbnailFile(video: File, maxEdgePx: Int = THUMB_EDGE_PX): File? =
        withContext(Dispatchers.IO) {
            if (!video.isFile || video.length() <= 0L) return@withContext null
            val key = cacheKey(video, maxEdgePx)
            val disk = diskFileFor(key)
            if (disk.isFile && disk.length() > 0L) return@withContext disk

            val lock = extractLocks.getOrPut(key) { Mutex() }
            try {
                lock.withLock {
                    if (disk.isFile && disk.length() > 0L) return@withLock disk
                    extractSemaphore.withPermit {
                        val cachedBmp = memory.get(key)
                        val bitmap = cachedBmp ?: extractFrame(video, maxEdgePx)
                        if (bitmap == null) return@withPermit null
                        if (cachedBmp == null) memory.put(key, bitmap)
                        writeJpeg(bitmap, disk)
                        disk.takeIf { it.isFile && it.length() > 0L }
                    }
                }
            } finally {
                extractLocks.remove(key, lock)
            }
        }

    private fun extractFrame(video: File, maxEdgePx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val raw = retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: retriever.frameAtTime ?: return null
            scaleDown(raw, maxEdgePx)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(source: Bitmap, maxEdgePx: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val longest = maxOf(w, h)
        if (longest <= maxEdgePx) return source
        val scale = maxEdgePx.toFloat() / longest
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, nw, nh, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Throwable) {
            tmp.delete()
        }
    }

    private fun diskFileFor(key: String): File = File(diskDir, "$key.jpg")

    private fun cacheKey(video: File, maxEdgePx: Int): String {
        val raw = "${video.absolutePath}|${video.lastModified()}|${video.length()}|$maxEdgePx"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    companion object {
        const val THUMB_EDGE_PX = 256
    }
}
