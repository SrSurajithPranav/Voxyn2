package com.voxyn.security

import com.voxyn.utils.VoxynLogger
import kotlin.math.abs
import kotlin.math.sqrt

sealed class VerificationResult {
    data class Verified(
        val profile: SpeakerProfile,
        val similarity: Float
    ) : VerificationResult()

    data class RejectedUnknownSpeaker(
        val bestSimilarity: Float,
        val threshold: Float
    ) : VerificationResult()

    data class PoorAudioQuality(
        val reason: String,
        val rmsEnergy: Float
    ) : VerificationResult()

    object NoProfilesEnrolled : VerificationResult()

    data class Error(val message: String) : VerificationResult()
}

class SpeakerVerifier(private val repository: SpeakerProfileRepository) {

    companion object {
        const val EMBEDDING_DIM = 128
        const val SIMILARITY_THRESHOLD = 0.72f
        const val MIN_SAMPLES = 3200 // 200 ms at 16 kHz
        const val MIN_RMS_ENERGY = 120.0f
        const val MAX_CLIPPING_RATIO = 0.15f
    }

    /**
     * Extracts an acoustic voiceprint embedding from raw 16kHz PCM16 audio.
     */
    fun extractVoiceprint(samples: ShortArray): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIM)
        if (samples.isEmpty()) return embedding

        val frameSize = 256
        val step = 512
        val numFrames = (samples.size - frameSize) / step
        if (numFrames <= 0) {
            for (i in samples.indices) {
                embedding[i % EMBEDDING_DIM] += abs(samples[i].toInt()).toFloat()
            }
            return normalize(embedding)
        }

        val piOverN = Math.PI / frameSize
        for (f in 0 until numFrames) {
            val offset = f * step
            for (bin in 0 until EMBEDDING_DIM) {
                var real = 0.0
                var imag = 0.0
                val freqIndex = bin + 1
                for (n in 0 until frameSize step 2) {
                    val s = samples[offset + n].toDouble() / 32768.0
                    val angle = piOverN * n * freqIndex
                    real += s * Math.cos(angle)
                    imag += s * Math.sin(angle)
                }
                embedding[bin] += (real * real + imag * imag).toFloat()
            }
        }
        return normalize(embedding)
    }

    /**
     * Checks whether the audio sample meets duration, energy, and distortion thresholds.
     * Returns null if quality is good, or PoorAudioQuality with the reason if not.
     */
    fun validateAudioQuality(samples: ShortArray?): VerificationResult.PoorAudioQuality? {
        if (samples == null || samples.isEmpty()) {
            return VerificationResult.PoorAudioQuality("Empty audio stream", 0f)
        }

        if (samples.size < MIN_SAMPLES) {
            return VerificationResult.PoorAudioQuality(
                "Audio too short (${samples.size * 1000 / 16000}ms, min 200ms required)",
                0f
            )
        }

        var sumSquares = 0.0
        var clippedCount = 0
        for (s in samples) {
            val v = s.toDouble()
            sumSquares += v * v
            if (abs(s.toInt()) >= 32700) clippedCount++
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        val clipRatio = clippedCount.toFloat() / samples.size

        if (rms < MIN_RMS_ENERGY) {
            VoxynLogger.w("Speaker verification: audio too quiet (RMS=$rms, min=$MIN_RMS_ENERGY)", "SPEAKER")
            return VerificationResult.PoorAudioQuality("Audio too quiet or silence", rms)
        }

        if (clipRatio > MAX_CLIPPING_RATIO) {
            VoxynLogger.w("Speaker verification: audio clipped (ratio=$clipRatio)", "SPEAKER")
            return VerificationResult.PoorAudioQuality("Audio distorted / clipped", rms)
        }

        return null
    }

    fun verify(samples: ShortArray?): VerificationResult {
        if (samples == null) {
            return VerificationResult.PoorAudioQuality("Empty audio stream", 0f)
        }

        val qualityCheck = validateAudioQuality(samples)
        if (qualityCheck != null) {
            return qualityCheck
        }

        val profiles = repository.getAllProfiles()
        if (profiles.isEmpty()) {
            VoxynLogger.w("Speaker verification: no enrolled profiles", "SPEAKER")
            return VerificationResult.NoProfilesEnrolled
        }

        val candidateVoiceprint = extractVoiceprint(samples)

        var bestMatch: SpeakerProfile? = null
        var bestSimilarity = -1.0f

        for (profile in profiles) {
            val sim = cosineSimilarity(candidateVoiceprint, profile.voiceprint)
            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestMatch = profile
            }
        }

        return if (bestMatch != null && bestSimilarity >= SIMILARITY_THRESHOLD) {
            VoxynLogger.i("Speaker verified: \"${bestMatch.name}\" (similarity=${"%.3f".format(bestSimilarity)})", "SPEAKER")
            VerificationResult.Verified(bestMatch, bestSimilarity)
        } else {
            VoxynLogger.w("Speaker rejected: unknown voice (bestSimilarity=${"%.3f".format(bestSimilarity)}, threshold=$SIMILARITY_THRESHOLD)", "SPEAKER")
            VerificationResult.RejectedUnknownSpeaker(bestSimilarity, SIMILARITY_THRESHOLD)
        }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0.0f
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA * normB)
        return if (denom > 1e-6f) dot / denom else 0.0f
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0.0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }
}
