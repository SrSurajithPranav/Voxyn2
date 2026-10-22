package com.voxyn.security

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class VoiceEnrollmentState {
    object Idle : VoiceEnrollmentState()
    data class Recording(val secondsRemaining: Int, val progress: Float) : VoiceEnrollmentState()
    object Processing : VoiceEnrollmentState()
    data class Success(val profileName: String, val message: String) : VoiceEnrollmentState()
    data class Error(val message: String) : VoiceEnrollmentState()
    data class TestResult(val verified: Boolean, val summary: String, val details: String) : VoiceEnrollmentState()
}

/**
 * Coordinates recording audio samples, validating quality, extracting 128-dim acoustic voiceprints,
 * and persisting speaker profiles for VOXYN biometric speaker identification.
 */
class VoiceEnrollmentManager(
    private val context: Context,
    private val verifier: SpeakerVerifier,
    private val repository: SpeakerProfileRepository
) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val ENROLLMENT_DURATION_SEC = 3
        const val TEST_DURATION_SEC = 2
        private const val CHUNK_MS = 100
    }

    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    /**
     * Records audio for 3 seconds, extracts voiceprint, and saves profile in the repository.
     */
    suspend fun enrollVoice(
        name: String,
        isOwner: Boolean,
        onProgress: (secondsRemaining: Int, progress: Float) -> Unit
    ): Result<SpeakerProfile> = withContext(Dispatchers.IO) {
        isCancelled = false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.failure(SecurityException("Microphone permission (RECORD_AUDIO) not granted"))
        }

        val allProfiles = repository.getAllProfiles()
        if (allProfiles.size >= SpeakerProfileRepository.MAX_PROFILES) {
            return@withContext Result.failure(
                IllegalStateException("Maximum of ${SpeakerProfileRepository.MAX_PROFILES} profiles reached. Please delete a profile first.")
            )
        }

        pauseContinuousListening()

        try {
            val totalSamplesNeeded = SAMPLE_RATE * ENROLLMENT_DURATION_SEC
            val samples = recordPcmAudio(totalSamplesNeeded, onProgress)

            if (isCancelled) {
                return@withContext Result.failure(CancellationException("Enrollment cancelled by user"))
            }

            // Quality gate check
            val qualityIssue = verifier.validateAudioQuality(samples)
            if (qualityIssue != null) {
                VoxynLogger.w("Enrollment audio quality check failed: ${qualityIssue.reason}", "SPEAKER")
                return@withContext Result.failure(IllegalArgumentException(qualityIssue.reason))
            }

            // Extract 128-dim acoustic voiceprint
            val voiceprint = verifier.extractVoiceprint(samples)

            val profileId = if (isOwner || allProfiles.isEmpty()) {
                SpeakerProfileRepository.OWNER_ID
            } else {
                UUID.randomUUID().toString()
            }

            val finalName = name.trim().ifBlank {
                if (isOwner || allProfiles.isEmpty()) "Owner" else "User ${allProfiles.size + 1}"
            }

            // Analyze acoustic features for voice cloning
            val acoustic = com.voxyn.voice.AcousticVoiceAnalyzer.analyze(samples)

            val profile = SpeakerProfile(
                id = profileId,
                name = finalName,
                isOwner = isOwner || allProfiles.isEmpty(),
                voiceprint = voiceprint,
                enrolledAtMs = System.currentTimeMillis(),
                pitch = acoustic.pitch,
                speechRate = acoustic.speechRate,
                timbre = acoustic.timbre
            )

            val enrolled = repository.enrollProfile(profile)
            if (!enrolled) {
                return@withContext Result.failure(IllegalStateException("Could not save profile to repository"))
            }

            VoxynLogger.i("Successfully enrolled voice profile: \"${profile.name}\" (owner=${profile.isOwner})", "SPEAKER")
            Result.success(profile)
        } finally {
            resumeContinuousListening()
        }
    }

    /**
     * Enrolls voice from an audio file (.wav, .mp3, .m4a, .aac, .flac).
     */
    suspend fun enrollVoiceFromFile(
        name: String,
        isOwner: Boolean,
        uri: android.net.Uri
    ): Result<SpeakerProfile> = withContext(Dispatchers.IO) {
        val allProfiles = repository.getAllProfiles()
        if (allProfiles.size >= SpeakerProfileRepository.MAX_PROFILES) {
            return@withContext Result.failure(
                IllegalStateException("Maximum of ${SpeakerProfileRepository.MAX_PROFILES} profiles reached")
            )
        }

        val samples = AudioFileDecoder.decodeToPcm16(context, uri)
            ?: return@withContext Result.failure(IllegalArgumentException("Could not decode audio from file"))

        val qualityIssue = verifier.validateAudioQuality(samples)
        if (qualityIssue != null) {
            return@withContext Result.failure(IllegalArgumentException("Audio quality issue: ${qualityIssue.reason}"))
        }

        val voiceprint = verifier.extractVoiceprint(samples)
        val profileId = if (isOwner || allProfiles.isEmpty()) {
            SpeakerProfileRepository.OWNER_ID
        } else {
            UUID.randomUUID().toString()
        }

        val finalName = name.trim().ifBlank {
            if (isOwner || allProfiles.isEmpty()) "Owner" else "User ${allProfiles.size + 1}"
        }

        val acoustic = com.voxyn.voice.AcousticVoiceAnalyzer.analyze(samples)

        val profile = SpeakerProfile(
            id = profileId,
            name = finalName,
            isOwner = isOwner || allProfiles.isEmpty(),
            voiceprint = voiceprint,
            enrolledAtMs = System.currentTimeMillis(),
            pitch = acoustic.pitch,
            speechRate = acoustic.speechRate,
            timbre = acoustic.timbre
        )

        val enrolled = repository.enrollProfile(profile)
        if (!enrolled) {
            return@withContext Result.failure(IllegalStateException("Could not save profile to repository"))
        }

        VoxynLogger.i("Successfully enrolled voice from file: \"${profile.name}\" (owner=${profile.isOwner})", "SPEAKER")
        Result.success(profile)
    }

    /**
     * Adds an additional live audio sample to an existing profile (up to 25 samples).
     */
    suspend fun addSample(
        profileId: String,
        onProgress: (secondsRemaining: Int, progress: Float) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        isCancelled = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.failure(SecurityException("Microphone permission not granted"))
        }
        if (repository.getProfile(profileId) == null) {
            return@withContext Result.failure(IllegalArgumentException("Profile not found: $profileId"))
        }

        pauseContinuousListening()
        try {
            val totalSamples = SAMPLE_RATE * ENROLLMENT_DURATION_SEC
            val samples = recordPcmAudio(totalSamples, onProgress)
            if (isCancelled) return@withContext Result.failure(CancellationException("Enrollment cancelled"))

            val qualityIssue = verifier.validateAudioQuality(samples)
            if (qualityIssue != null) return@withContext Result.failure(IllegalArgumentException("Audio quality issue: ${qualityIssue.reason}"))

            val voiceprint = verifier.extractVoiceprint(samples)
            val added = repository.addSampleToProfile(profileId, voiceprint)
            if (!added) return@withContext Result.failure(IllegalStateException("Failed to add sample to profile"))

            val updated = repository.getProfile(profileId)
            Result.success(updated?.sampleVoiceprints?.size ?: 1)
        } finally {
            resumeContinuousListening()
        }
    }

    /**
     * Adds an additional sample from an audio file to an existing profile.
     */
    suspend fun addSampleFromFile(
        profileId: String,
        uri: android.net.Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (repository.getProfile(profileId) == null) {
            return@withContext Result.failure(IllegalArgumentException("Profile not found: $profileId"))
        }

        val samples = AudioFileDecoder.decodeToPcm16(context, uri)
            ?: return@withContext Result.failure(IllegalArgumentException("Could not decode audio from file"))

        val qualityIssue = verifier.validateAudioQuality(samples)
        if (qualityIssue != null) {
            return@withContext Result.failure(IllegalArgumentException("Audio quality issue: ${qualityIssue.reason}"))
        }

        val voiceprint = verifier.extractVoiceprint(samples)
        val added = repository.addSampleToProfile(profileId, voiceprint)
        if (!added) return@withContext Result.failure(IllegalStateException("Failed to add sample to profile"))

        val updated = repository.getProfile(profileId)
        Result.success(updated?.sampleVoiceprints?.size ?: 1)
    }

    /**
     * Records audio for 2 seconds and evaluates against enrolled profiles to test speaker verification.
     */
    suspend fun testVerification(
        onProgress: (secondsRemaining: Int, progress: Float) -> Unit
    ): Result<VerificationResult> = withContext(Dispatchers.IO) {
        isCancelled = false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.failure(SecurityException("Microphone permission not granted"))
        }

        val profiles = repository.getAllProfiles()
        if (profiles.isEmpty()) {
            return@withContext Result.success(VerificationResult.NoProfilesEnrolled)
        }

        pauseContinuousListening()

        try {
            val totalSamplesNeeded = SAMPLE_RATE * TEST_DURATION_SEC
            val samples = recordPcmAudio(totalSamplesNeeded, onProgress)

            if (isCancelled) {
                return@withContext Result.failure(CancellationException("Test cancelled"))
            }

            val result = verifier.verify(samples)
            Result.success(result)
        } finally {
            resumeContinuousListening()
        }
    }

    private suspend fun recordPcmAudio(
        totalSamplesNeeded: Int,
        onProgress: (secondsRemaining: Int, progress: Float) -> Unit
    ): ShortArray {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 4
            )
        } catch (_: Exception) {}

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            try { record?.release() } catch (_: Exception) {}
            // Fallback to standard MIC
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 4
            )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try { record.release() } catch (_: Exception) {}
            throw IllegalStateException("AudioRecord initialization failed on both VOICE_RECOGNITION and MIC")
        }

        val allSamples = ShortArray(totalSamplesNeeded)
        var samplesReadTotal = 0
        val chunkSamples = (SAMPLE_RATE * CHUNK_MS) / 1000
        val buffer = ShortArray(chunkSamples)

        try {
            record.startRecording()
            while (samplesReadTotal < totalSamplesNeeded && !isCancelled) {
                val toRead = minOf(buffer.size, totalSamplesNeeded - samplesReadTotal)
                val read = record.read(buffer, 0, toRead)
                if (read > 0) {
                    System.arraycopy(buffer, 0, allSamples, samplesReadTotal, read)
                    samplesReadTotal += read
                    val progress = samplesReadTotal.toFloat() / totalSamplesNeeded
                    val secondsRemaining = ((totalSamplesNeeded - samplesReadTotal) / SAMPLE_RATE) + 1
                    onProgress(secondsRemaining, progress.coerceIn(0f, 1f))
                }
                delay(10)
            }
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Exception) {}
            try {
                record.release()
            } catch (_: Exception) {}
        }

        return if (samplesReadTotal == totalSamplesNeeded) {
            allSamples
        } else {
            allSamples.copyOf(samplesReadTotal)
        }
    }

    private fun pauseContinuousListening() {
        try {
            val stopIntent = Intent(context, VoiceInputService::class.java).apply {
                action = VoiceInputService.ACTION_STOP_LISTENING
            }
            context.startService(stopIntent)
            VoxynLogger.d("Paused continuous voice listening for enrollment recording", "SPEAKER")
        } catch (e: Exception) {
            VoxynLogger.w("Could not pause continuous listening: ${e.message}", "SPEAKER")
        }
    }

    private fun resumeContinuousListening() {
        try {
            val startIntent = Intent(context, VoiceInputService::class.java).apply {
                action = VoiceInputService.ACTION_START_LISTENING
            }
            androidx.core.content.ContextCompat.startForegroundService(context, startIntent)
            VoxynLogger.d("Resumed continuous voice listening after enrollment recording", "SPEAKER")
        } catch (e: Exception) {
            VoxynLogger.w("Could not resume continuous listening: ${e.message}", "SPEAKER")
        }
    }
}
