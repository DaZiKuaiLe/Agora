package com.newoether.agora.viewmodel

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class VideoSliceConfig(
    val intervalMicros: Long,
    val frameCount: Int
)

class ImageProcessor(
    private val app: Application
) {
    suspend fun processImagesAndVideos(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap(),
        maxDimension: Int = 512,
    ): List<String> = withContext(Dispatchers.IO) {
        uris.flatMap { uriString ->
            try {
                val uri = android.net.Uri.parse(uriString)
                val mimeType = app.contentResolver.getType(uri)

                when {
                    mimeType?.startsWith("video/") == true -> {
                        val config = sliceConfigs[uriString]
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(app, uri)
                            val paths = mutableListOf<String>()

                            if (config != null && config.frameCount > 1) {
                                var timeUs = 0L
                                for (i in 0 until config.frameCount) {
                                    val bitmap = retriever.getFrameAtTime(
                                        timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                                    )
                                    if (bitmap != null) {
                                        val file = File(app.filesDir, "vid_${UUID.randomUUID()}_$i.jpg")
                                        file.outputStream().use { out ->
                                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                        }
                                        bitmap.recycle()
                                        paths.add(file.absolutePath)
                                    }
                                    timeUs += config.intervalMicros
                                }
                            } else {
                                val bitmap = retriever.frameAtTime
                                if (bitmap != null) {
                                    val file = File(app.filesDir, "vid_${UUID.randomUUID()}.jpg")
                                    file.outputStream().use { out ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                    }
                                    bitmap.recycle()
                                    paths.add(file.absolutePath)
                                }
                            }
                            paths
                        } finally {
                            retriever.release()
                        }
                    }
                    mimeType?.startsWith("image/") == true || mimeType == null -> {
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        openStream(uriString)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream, null, options)
                        }

                        if (options.outWidth > 0 && options.outHeight > 0) {
                            // Strict ceiling: downsample by powers of two so the decoded bitmap's
                            // long edge lands in [maxDim, 2*maxDim), then precisely scale to maxDim.
                            // This guarantees the result is <= maxDim (no off-by-one from the
                            // decoder's ceil) and stays close to maxDim (no coarse power-of-two gap).
                            val maxDim = maxDimension.coerceAtLeast(1)
                            val longEdge = maxOf(options.outWidth, options.outHeight)
                            var scale = 1
                            while (longEdge / (scale * 2) >= maxDim) {
                                scale *= 2
                            }

                            val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                            val decoded = openStream(uriString)?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
                            }

                            // A decoded long edge in [maxDim, 2*maxDim) means createScaledBitmap only
                            // ever shrinks, never upscales, and the ratio stays in (0.5, 1].
                            val finalBitmap = if (decoded != null && maxOf(decoded.width, decoded.height) > maxDim) {
                                val decodedLong = maxOf(decoded.width, decoded.height).toFloat()
                                val ratio = maxDim / decodedLong
                                val nw = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
                                val nh = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
                                android.graphics.Bitmap.createScaledBitmap(decoded, nw, nh, true)
                                    .also { decoded.recycle() }
                            } else {
                                decoded
                            }

                            if (finalBitmap != null) {
                                val file = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                                file.outputStream().use { out ->
                                    finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                }
                                finalBitmap.recycle()
                                listOf(file.absolutePath)
                            } else emptyList()
                        } else emptyList()
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun openStream(source: String): java.io.InputStream? =
        com.newoether.agora.util.AttachmentSourceReader.open(app, source)
}
