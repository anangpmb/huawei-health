/*  Copyright (C) 2026 WatchLink contributors

    This file is part of WatchLink, a modified version of Gadgetbridge.

    WatchLink is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    WatchLink is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.watchlink.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Encodes rendered frames to an H.264 MP4 on the device: no ffmpeg, no network.
 *
 * Each frame is drawn into a bitmap, converted to YUV and queued with an explicit presentation time
 * (frame / fps), so the file plays at exactly [fps] no matter how long a frame took to render.
 */
class ShareVideoEncoder(private val fps: Int = WorkoutShareRenderer.FPS) {

    /** Output size actually used: 1080x1920 when the encoder supports it, otherwise 720x1280. */
    class Size(val width: Int, val height: Int)

    fun chooseSize(): Size {
        for (size in listOf(Size(1080, 1920), Size(720, 1280))) {
            if (encoderFor(size) != null) return size
        }
        throw IOException("No H.264 encoder available")
    }

    /**
     * Renders [frameCount] frames with [render] (canvas already scaled to the output size; draw in
     * the renderer's 1080x1920 design space) and writes them to [output]. Cancellable: the
     * coroutine's cancellation stops the encode and releases the codec.
     */
    suspend fun encode(
        output: File,
        frameCount: Int,
        render: (Canvas, Int) -> Unit,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val size = chooseSize()
        val width = size.width
        val height = size.height
        val format = videoFormat(size)
        val codecName = encoderFor(size) ?: throw IOException("No H.264 encoder for ${width}x$height")
        LOG.info("Encoding {} frames at {}x{} with {}", frameCount, width, height, codecName)

        val codec = MediaCodec.createByCodecName(codecName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val scale = width / WorkoutShareRenderer.WIDTH.toFloat()
        val argb = IntArray(width * height)
        val info = MediaCodec.BufferInfo()

        var frame = 0
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        if (frame >= frameCount) {
                            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs(frame), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            bitmap.eraseColor(Color.BLACK)
                            val save = canvas.save()
                            canvas.scale(scale, scale)
                            render(canvas, frame)
                            canvas.restoreToCount(save)
                            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                            val image = codec.getInputImage(inputIndex)
                                ?: throw IOException("Encoder gave no input image")
                            writeYuv(image, argb, width, height)
                            codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, presentationTimeUs(frame), 0)
                            frame++
                            onProgress(frame / frameCount.toFloat())
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0L)
                while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (buffer != null && info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                    outputIndex = codec.dequeueOutputBuffer(info, 0L)
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
                LOG.warn("Stopping the encoder failed", e)
            }
            codec.release()
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    LOG.warn("Stopping the muxer failed", e)
                }
            }
            muxer.release()
            bitmap.recycle()
        }
    }

    private fun presentationTimeUs(frame: Int): Long = frame * 1_000_000L / fps

    private fun videoFormat(size: Size): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, if (size.width >= 1080) 12_000_000 else 7_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    private fun encoderFor(size: Size): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoFormat(size))

    /**
     * ARGB to YUV 4:2:0 (BT.601, limited range), honouring the planes' row and pixel strides so it
     * works for both planar and semi-planar encoder layouts.
     */
    private fun writeYuv(image: Image, argb: IntArray, width: Int, height: Int) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val row = ByteArray(width)
        for (j in 0 until height) {
            val offset = j * width
            for (i in 0 until width) {
                val c = argb[offset + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                row[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (yPixelStride == 1) {
                yBuffer.position(j * yRowStride)
                yBuffer.put(row, 0, width)
            } else {
                for (i in 0 until width) yBuffer.put(j * yRowStride + i * yPixelStride, row[i])
            }
        }

        for (j in 0 until height / 2) {
            for (i in 0 until width / 2) {
                // Average the 2x2 block so thin coloured lines keep their hue
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val c = argb[(2 * j + dy) * width + 2 * i + dx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                r /= 4
                g /= 4
                b /= 4
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuffer.put(j * uRowStride + i * uPixelStride, u.toByte())
                vBuffer.put(j * vRowStride + i * vPixelStride, v.toByte())
            }
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(ShareVideoEncoder::class.java)
        const val TIMEOUT_US = 10_000L
    }
}
