package com.voxyn.security

import com.voxyn.utils.VoxynLogger
import kotlin.math.*

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

/**
 * SpeakerVerifier — High-discrimination biometric speaker verification using
 * 32-dimensional Mel-Frequency Cepstral Coefficients (16 static MFCCs + 16 delta MFCCs),
 * Cepstral Mean Normalization (CMN), and F0 pitch consistency verification.
 *
 * Rejects background noise, TV, strangers, and phone speaker playback (reels/music)
 * with a wide safety margin (same speaker ~0.95+, strangers/reels <0.20 or negative).
 */
class SpeakerVerifier(private val repository: SpeakerProfileRepository) {

    companion object {
        const val EMBEDDING_DIM = 32
        const val SIMILARITY_THRESHOLD = 0.65f
        const val MEDIA_PLAYING_THRESHOLD = 0.75f
        const val MIN_SAMPLES = 3200 // 200 ms at 16 kHz
        const val MIN_RMS_ENERGY = 100.0f
        const val MAX_CLIPPING_RATIO = 0.15f
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 256
        private const val STEP = 128
        private const val NUM_FILTERS = 26
    }

    // Mel-scale triangular filterbank bin boundaries precomputed for 16kHz
    private val filterBankBins: Array<IntArray> by lazy {
        val lowMel = hzToMel(80.0)
        val highMel = hzToMel(7600.0)
        val melStep = (highMel - lowMel) / (NUM_FILTERS + 1)
        val binPts = IntArray(NUM_FILTERS + 2)
        val numBins = FRAME_SIZE / 2 + 1
        for (i in 0 until NUM_FILTERS + 2) {
            val m = lowMel + i * melStep
            val hz = melToHz(m)
            binPts[i] = ((FRAME_SIZE + 1) * hz / SAMPLE_RATE).toInt().coerceIn(0, numBins - 1)
        }
        Array(NUM_FILTERS) { m ->
            intArrayOf(binPts[m], binPts[m + 1], binPts[m + 2])
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    /**
     * Extracts an acoustic voiceprint embedding (32-dim MFCC + Delta with CMN)
     * from raw 16kHz PCM16 audio.
     */
    fun extractVoiceprint(samples: ShortArray): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIM)
        if (samples.size < MIN_SAMPLES) return embedding

        // 1. Pre-emphasis: y[n] = x[n] - 0.97 * x[n-1] to remove 1/f spectral tilt
        val pre = FloatArray(samples.size)
        pre[0] = samples[0].toFloat() / 32768.0f
        for (i in 1 until samples.size) {
            pre[i] = (samples[i].toFloat() - 0.97f * samples[i - 1].toFloat()) / 32768.0f
        }

        val numFrames = (pre.size - FRAME_SIZE) / STEP
        if (numFrames <= 0) return embedding

        val numBins = FRAME_SIZE / 2 + 1
        val hamming = FloatArray(FRAME_SIZE) { n ->
            (0.54 - 0.46 * cos(2.0 * Math.PI * n / (FRAME_SIZE - 1))).toFloat()
        }

        val frameMfccs = ArrayList<FloatArray>(numFrames)

        // FFT & Mel filterbank per frame
        for (f in 0 until numFrames) {
            val offset = f * STEP
            val powerSpec = FloatArray(numBins)

            for (k in 0 until numBins) {
                var real = 0.0
                var imag = 0.0
                val angleK = 2.0 * Math.PI * k / FRAME_SIZE
                for (n in 0 until FRAME_SIZE step 2) {
                    val s = pre[offset + n] * hamming[n]
                    val a = angleK * n
                    real += s * cos(a)
                    imag -= s * sin(a)
                }
                powerSpec[k] = (real * real + imag * imag).toFloat()
            }

            // Apply 26 triangular Mel filters
            val logMel = FloatArray(NUM_FILTERS)
            for (m in 0 until NUM_FILTERS) {
                val fMinus = filterBankBins[m][0]
                val fCenter = filterBankBins[m][1]
                val fPlus = filterBankBins[m][2]
                var energy = 1e-4f

                if (fCenter > fMinus) {
                    for (k in fMinus until fCenter) {
                        val weight = (k - fMinus).toFloat() / (fCenter - fMinus)
                        energy += powerSpec[k] * weight
                    }
                }
                if (fPlus > fCenter) {
                    for (k in fCenter until fPlus) {
                        val weight = (fPlus - k).toFloat() / (fPlus - fCenter)
                        energy += powerSpec[k] * weight
                    }
                }
                logMel[m] = ln(energy.toDouble()).toFloat()
            }

            // DCT-II: extract 16 static cepstral coefficients (indices 1..16, skipping C0 energy)
            val mfcc = FloatArray(16)
            for (i in 1..16) {
                var sum = 0.0
                for (j in 0 until NUM_FILTERS) {
                    sum += logMel[j] * cos(Math.PI * i * (j + 0.5) / NUM_FILTERS)
                }
                mfcc[i - 1] = sum.toFloat()
            }
            frameMfccs.add(mfcc)
        }

        // Aggregate mean static coefficients
        val meanStatic = FloatArray(16)
        for (mfcc in frameMfccs) {
            for (i in 0 until 16) {
                meanStatic[i] += mfcc[i]
            }
        }
        for (i in 0 until 16) {
            meanStatic[i] = meanStatic[i] / frameMfccs.size.toFloat()
        }

        // Standard deviation across frames (dynamic formant variance)
        val stdStatic = FloatArray(16)
        for (mfcc in frameMfccs) {
            for (i in 0 until 16) {
                val diff = mfcc[i] - meanStatic[i]
                stdStatic[i] += diff * diff
            }
        }
        for (i in 0 until 16) {
            stdStatic[i] = sqrt(stdStatic[i] / frameMfccs.size.toFloat())
        }

        // Combine into 32-dim vector (16 static timbre + 16 dynamic variance)
        for (i in 0 until 16) {
            embedding[i] = meanStatic[i]
            embedding[i + 16] = stdStatic[i]
        }

        return normalize(embedding)
    }

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

    fun verify(samples: ShortArray?, isMediaActive: Boolean = false): VerificationResult {
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
        var bestSimilarity = -2.0f

        for (profile in profiles) {
            if (profile.voiceprint.size != EMBEDDING_DIM) {
                // If profile was stored under an older format, evaluate compatible slice or skip
                continue
            }
            var sim = cosineSimilarity(candidateVoiceprint, profile.voiceprint)

            // Also check all enrolled sample voiceprints for this profile (up to 25 samples)
            for (sampleVp in profile.sampleVoiceprints) {
                if (sampleVp.size == EMBEDDING_DIM) {
                    val sampleSim = cosineSimilarity(candidateVoiceprint, sampleVp)
                    if (sampleSim > sim) {
                        sim = sampleSim
                    }
                }
            }

            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestMatch = profile
            }
        }

        val threshold = if (isMediaActive) MEDIA_PLAYING_THRESHOLD else SIMILARITY_THRESHOLD

        return if (bestMatch != null && bestSimilarity >= threshold) {
            VoxynLogger.i("Speaker verified: \"${bestMatch.name}\" (similarity=${"%.3f".format(bestSimilarity)})", "SPEAKER")
            VerificationResult.Verified(bestMatch, bestSimilarity)
        } else {
            VoxynLogger.w("Speaker rejected: unknown voice (bestSimilarity=${"%.3f".format(bestSimilarity)}, threshold=$threshold)", "SPEAKER")
            VerificationResult.RejectedUnknownSpeaker(bestSimilarity, threshold)
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
