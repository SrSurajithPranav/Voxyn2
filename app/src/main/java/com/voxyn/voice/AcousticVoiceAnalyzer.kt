package com.voxyn.voice

import com.voxyn.utils.VoxynLogger
import kotlin.math.abs
import kotlin.math.sqrt

data class AcousticVoiceFeatures(
    val pitch: Float,
    val speechRate: Float,
    val timbre: String,
    val estimatedF0Hz: Float
)

/**
 * AcousticVoiceAnalyzer — Analyzes spoken PCM16 audio samples to extract vocal
 * fundamental frequency (F0 pitch), cadence/speaking rate, and acoustic timbre.
 *
 * Used to clone the user's or their friends' voices by configuring on-device
 * acoustic synthesis parameters that mirror their natural vocal characteristics.
 */
object AcousticVoiceAnalyzer {

    private const val SAMPLE_RATE = 16000
    private const val MIN_F0 = 65.0f   // Deep male voice (~65 Hz)
    private const val MAX_F0 = 360.0f  // High female / child voice (~360 Hz)
    private const val BASELINE_PITCH_HZ = 130.0f // Neutral reference frequency

    fun analyze(samples: ShortArray): AcousticVoiceFeatures {
        if (samples.isEmpty()) {
            return AcousticVoiceFeatures(
                pitch = 1.0f,
                speechRate = 1.0f,
                timbre = "male",
                estimatedF0Hz = BASELINE_PITCH_HZ
            )
        }

        val frameSize = 1024
        val step = 512
        val minLag = (SAMPLE_RATE / MAX_F0).toInt().coerceAtLeast(20)
        val maxLag = (SAMPLE_RATE / MIN_F0).toInt().coerceAtMost(frameSize / 2)

        val f0Estimates = mutableListOf<Float>()
        var voicedFrames = 0
        var totalFrames = 0

        var offset = 0
        while (offset + frameSize <= samples.size) {
            totalFrames++

            // Calculate frame energy
            var energy = 0.0
            for (i in 0 until frameSize) {
                val s = samples[offset + i].toDouble()
                energy += s * s
            }
            val rms = sqrt(energy / frameSize)

            // Voiced detection threshold (RMS > 150)
            if (rms > 150.0) {
                var bestLag = -1
                var maxCorr = 0.0

                for (lag in minLag..maxLag) {
                    var corr = 0.0
                    for (i in 0 until (frameSize - lag)) {
                        corr += samples[offset + i].toDouble() * samples[offset + i + lag].toDouble()
                    }
                    if (corr > maxCorr) {
                        maxCorr = corr
                        bestLag = lag
                    }
                }

                // Check normalized correlation confidence
                if (bestLag > 0 && energy > 0.0 && (maxCorr / energy) > 0.28) {
                    val frameF0 = SAMPLE_RATE.toFloat() / bestLag.toFloat()
                    if (frameF0 in MIN_F0..MAX_F0) {
                        f0Estimates.add(frameF0)
                        voicedFrames++
                    }
                }
            }

            offset += step
        }

        // Determine median or average F0
        val avgF0 = if (f0Estimates.isNotEmpty()) {
            f0Estimates.sorted().let { sorted ->
                sorted[sorted.size / 2] // Median is robust against octave jumps
            }
        } else {
            BASELINE_PITCH_HZ
        }

        // Map F0 to Android TTS pitch scale (0.65f to 1.55f, centered around 1.0)
        val calculatedPitch = (avgF0 / BASELINE_PITCH_HZ).coerceIn(0.65f, 1.55f)

        // Calculate speaking rate from voiced frame ratio and zero-crossings
        var zeroCrossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toFloat() / samples.size.toFloat()
        val calculatedRate = (0.92f + (zcr * 1.5f)).coerceIn(0.85f, 1.20f)

        // Timbre / gender classification
        val timbre = if (avgF0 < 165.0f) "male" else "female"

        VoxynLogger.i(
            "AcousticVoiceAnalyzer: F0=${"%.1f".format(avgF0)}Hz, pitch=${"%.2f".format(calculatedPitch)}, rate=${"%.2f".format(calculatedRate)}, timbre=$timbre (voiced: $voicedFrames/$totalFrames)",
            "SPEAKER"
        )

        return AcousticVoiceFeatures(
            pitch = calculatedPitch,
            speechRate = calculatedRate,
            timbre = timbre,
            estimatedF0Hz = avgF0
        )
    }
}
