package com.voxyn.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Records a validated 16 kHz mono PCM enrollment sample into private storage. */
class VoiceEnrollmentManager(
    private val repository: VoiceProfileRepository,
    private val speakerVerifier: SpeakerVerifier,
    private val microphoneArbiter: MicrophoneArbiter
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val DEFAULT_DURATION_MS = 6_000L
        private const val MIN_DURATION_MS = 2_000L
        private const val MAX_DURATION_MS = 15_000L
    }

    suspend fun record(slot: Int, label: String, durationMs: Long = DEFAULT_DURATION_MS): EnrollmentResult =
        withContext(Dispatchers.IO) {
            if (slot !in 0 until VoiceProfileRepository.SLOT_COUNT) return@withContext EnrollmentResult.Rejected("Invalid voice slot")
                .also { VoxynLogger.w("Enrollment rejected: invalid slot=$slot", "SPEAKER") }
            if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) return@withContext EnrollmentResult.Rejected("Recording must be 2–15 seconds")
                .also { VoxynLogger.w("Enrollment rejected: duration=${durationMs}ms", "SPEAKER") }
            if (!microphoneArbiter.acquire(MicrophoneArbiter.Owner.ENROLLMENT)) {
                VoxynLogger.w("Enrollment rejected: microphone is owned by another operation", "SPEAKER")
                return@withContext EnrollmentResult.Rejected("Microphone is busy; stop voice listening before recording")
            }
            try {
            VoxynLogger.i("Enrollment recording started: slot=${slot + 1}, duration=${durationMs}ms", "SPEAKER")
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).takeIf { it > 0 } ?: return@withContext EnrollmentResult.Failure("Microphone is unavailable")
            val pcm = ShortArray((SAMPLE_RATE * durationMs / 1000L).toInt())
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, pcm.size * 2)
                )
            } catch (e: SecurityException) {
                return@withContext EnrollmentResult.Failure("Microphone permission is required", e)
            } catch (e: Exception) {
                return@withContext EnrollmentResult.Failure("Could not initialize microphone", e)
            }
            try {
                record.startRecording()
                var offset = 0
                while (offset < pcm.size) {
                    val read = record.read(pcm, offset, pcm.size - offset)
                    if (read <= 0) return@withContext EnrollmentResult.Failure("Microphone capture failed")
                    offset += read
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }

            val nonSilent = pcm.count { abs(it.toInt()) >= 500 }
            val clipped = pcm.count { abs(it.toInt()) >= 32_000 }
            VoxynLogger.d("Enrollment audio validated: slot=${slot + 1}, samples=${pcm.size}, active=${nonSilent * 100 / pcm.size}%, clipped=${clipped * 100 / pcm.size}%", "SPEAKER")
            if (nonSilent < pcm.size / 100) return@withContext EnrollmentResult.Rejected("Recording is silent")
                .also { VoxynLogger.w("Enrollment rejected: silent sample for slot=${slot + 1}", "SPEAKER") }
            if (clipped > pcm.size / 20) return@withContext EnrollmentResult.Rejected("Recording is clipped; move farther from the microphone")
                .also { VoxynLogger.w("Enrollment rejected: clipped sample for slot=${slot + 1}", "SPEAKER") }

            val profile = repository.list()[slot]
            val output = repository.recordingFile(profile.id)
            val savedRecording = runCatching { writeWav(output, pcm) }
            if (savedRecording.isFailure) return@withContext EnrollmentResult.Failure("Could not save enrollment recording", savedRecording.exceptionOrNull())
                .also { VoxynLogger.e("Enrollment WAV could not be saved: slot=${slot + 1}", savedRecording.exceptionOrNull(), "SPEAKER") }
            when (val result = speakerVerifier.enroll(profile, pcm, SAMPLE_RATE)) {
                is EnrollmentResult.Success -> {
                    val saved = repository.saveEnrollment(slot, label, 1)
                    VoxynLogger.i("Enrollment complete: slot=${slot + 1}, profileId=${saved.id}, embedding=ready", "SPEAKER")
                    EnrollmentResult.Success(saved)
                }
                is EnrollmentResult.Rejected -> result.also { VoxynLogger.w("Speaker model rejected enrollment slot=${slot + 1}: ${it.reason}", "SPEAKER") }
                is EnrollmentResult.Failure -> result.also { VoxynLogger.e("Speaker enrollment failed slot=${slot + 1}: ${it.reason}", it.cause, "SPEAKER") }
            }
            } finally {
                microphoneArbiter.release(MicrophoneArbiter.Owner.ENROLLMENT)
            }
        }

    private fun writeWav(file: File, samples: ShortArray) {
        file.parentFile?.mkdirs()
        BufferedOutputStream(file.outputStream()).use { out ->
            val dataSize = samples.size * 2
            fun writeAscii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun writeInt(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            fun writeShort(value: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
            writeAscii("RIFF"); writeInt(36 + dataSize); writeAscii("WAVEfmt "); writeInt(16)
            writeShort(1); writeShort(1); writeInt(SAMPLE_RATE); writeInt(SAMPLE_RATE * 2); writeShort(2); writeShort(16)
            writeAscii("data"); writeInt(dataSize)
            samples.forEach { writeShort(it.toInt()) }
        }
    }
}
