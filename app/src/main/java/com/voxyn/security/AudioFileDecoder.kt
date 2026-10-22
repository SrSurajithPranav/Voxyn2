package com.voxyn.security

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.voxyn.utils.VoxynLogger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes audio files (WAV, MP3, M4A, AAC, FLAC, OGG) from Android storage
 * into 16,000 Hz, 16-bit mono PCM samples for voice enrollment and acoustic analysis.
 */
object AudioFileDecoder {

    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 5000L

    fun decodeToPcm16(context: Context, uri: Uri): ShortArray? {
        VoxynLogger.i("Decoding audio file from URI: $uri", "AUDIO_DECODER")

        // First attempt standard MediaExtractor + MediaCodec (handles MP3, M4A, AAC, FLAC, OGG, WAV)
        val pcm = runCatching { decodeViaMediaCodec(context, uri) }.getOrNull()
        if (pcm != null && pcm.isNotEmpty()) {
            VoxynLogger.i("Decoded ${pcm.size} samples (${pcm.size * 1000 / TARGET_SAMPLE_RATE}ms) via MediaCodec", "AUDIO_DECODER")
            return pcm
        }

        // Fallback: direct WAV stream parser if MediaCodec was unavailable
        val wavPcm = runCatching { decodeWavStream(context, uri) }.getOrNull()
        if (wavPcm != null && wavPcm.isNotEmpty()) {
            VoxynLogger.i("Decoded ${wavPcm.size} samples via WAV parser fallback", "AUDIO_DECODER")
            return wavPcm
        }

        VoxynLogger.e("Failed to decode audio file from URI: $uri", tag = "AUDIO_DECODER")
        return null
    }

    private fun decodeViaMediaCodec(context: Context, uri: Uri): ShortArray? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                VoxynLogger.w("No audio track found in media file", "AUDIO_DECODER")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val inputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val rawBytes = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val maxSamplesToDecode = TARGET_SAMPLE_RATE * 30 // Max 30 seconds of audio

            while (!sawOutputEOS && rawBytes.size() < maxSamplesToDecode * 2) {
                if (!sawInputEOS) {
                    val inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                sawInputEOS = true
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIndex)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuf.get(chunk)
                        rawBytes.write(chunk)
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }

            val pcmBytes = rawBytes.toByteArray()
            if (pcmBytes.isEmpty()) return null

            // Convert raw PCM bytes (little-endian 16-bit) to ShortArray
            val shortCount = pcmBytes.size / 2
            val rawShorts = ShortArray(shortCount)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(rawShorts)

            // Downmix to mono if stereo/multichannel
            val monoShorts = if (channelCount > 1) {
                val mono = ShortArray(rawShorts.size / channelCount)
                for (i in mono.indices) {
                    var sum = 0
                    for (ch in 0 until channelCount) {
                        sum += rawShorts[i * channelCount + ch]
                    }
                    mono[i] = (sum / channelCount).toShort()
                }
                mono
            } else {
                rawShorts
            }

            // Resample to TARGET_SAMPLE_RATE (16 kHz) if needed
            return if (inputSampleRate != TARGET_SAMPLE_RATE) {
                resample(monoShorts, inputSampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoShorts
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { pfd.close() }
        }
    }

    private fun decodeWavStream(context: Context, uri: Uri): ShortArray? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            val header = ByteArray(44)
            if (input.read(header) < 44) return null
            val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
            val sampleRate = (header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)
            val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)

            if (bitsPerSample != 16) return null

            val pcmData = input.readBytes()
            val shortCount = pcmData.size / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

            val mono = if (channels > 1) {
                val m = ShortArray(shorts.size / channels)
                for (i in m.indices) {
                    var sum = 0
                    for (ch in 0 until channels) sum += shorts[i * channels + ch]
                    m[i] = (sum / channels).toShort()
                }
                m
            } else {
                shorts
            }

            if (sampleRate != TARGET_SAMPLE_RATE) {
                resample(mono, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                mono
            }
        }
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate <= 0 || toRate <= 0 || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLength = (input.size / ratio).roundToInt().coerceAtLeast(1)
        val output = ShortArray(outLength)

        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = (srcPos - srcIndex).toFloat()

            if (srcIndex + 1 < input.size) {
                val s0 = input[srcIndex].toFloat()
                val s1 = input[srcIndex + 1].toFloat()
                output[i] = (s0 + frac * (s1 - s0)).roundToInt().coerceIn(-32768, 32767).toShort()
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }
        return output
    }
}
