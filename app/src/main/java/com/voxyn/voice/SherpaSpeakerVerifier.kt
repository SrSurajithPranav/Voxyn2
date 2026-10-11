package com.voxyn.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Local 16 kHz ERes2Net embeddings through the existing sherpa-onnx AAR. */
class SherpaSpeakerVerifier(
    private val context: Context,
    private val repository: VoiceProfileRepository,
    private val modelManager: SpeakerEmbeddingModelManager
) : SpeakerVerifier {
    companion object {
        // Starting operating points from sherpa-onnx examples; device-specific FAR/FRR calibration is still required.
        const val ACCEPT_COSINE = 0.62f
        const val UNCERTAIN_COSINE = 0.50f
        const val MIN_WINNER_MARGIN = 0.08f
        private const val MIN_ENROLLMENT_SAMPLES = 32_000
        private const val MIN_COMMAND_SAMPLES = 16_000
    }

    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null

    private suspend fun getExtractor(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        val model = modelManager.ensureAvailable() ?: run {
            VoxynLogger.w("Speaker model unavailable; local verification remains fail-closed", "SPEAKER")
            return null
        }
        return withContext(Dispatchers.IO) {
            extractor ?: synchronized(this@SherpaSpeakerVerifier) {
                extractor ?: runCatching {
                    SpeakerEmbeddingExtractor(
                        null,
                        SpeakerEmbeddingExtractorConfig(
                            model = model.absolutePath,
                            numThreads = 2,
                            debug = false,
                            provider = "cpu"
                        )
                    ).also { extractor = it }
                }.onFailure {
                    VoxynLogger.e("Speaker model initialization failed: ${it.javaClass.simpleName}", tag = "SPEAKER")
                }.getOrNull()
            }
        }
    }

    override suspend fun enroll(profile: VoiceProfile, samplesPcm16: ShortArray, sampleRateHz: Int): EnrollmentResult =
        withContext(Dispatchers.Default) {
            VoxynLogger.i("Speaker enrollment embedding started: slot=${profile.slot + 1}, samples=${samplesPcm16.size}, rate=$sampleRateHz", "SPEAKER")
            if (profile.slot !in 0 until VoiceProfileRepository.SLOT_COUNT || sampleRateHz != 16_000 || samplesPcm16.size < MIN_ENROLLMENT_SAMPLES) {
                return@withContext EnrollmentResult.Rejected("Use at least two seconds of 16 kHz speech")
            }
            val engine = getExtractor() ?: return@withContext EnrollmentResult.Failure("Speaker model unavailable; connect to download it")
            val embedding = computeEmbedding(engine, samplesPcm16, sampleRateHz)
                ?: return@withContext EnrollmentResult.Rejected("Recording is too short or unsuitable for speaker enrollment")
            runCatching { repository.saveEmbedding(profile.id, embedding) }
                .fold(
                    onSuccess = {
                        VoxynLogger.i("Speaker embedding saved: slot=${profile.slot + 1}, dimensions=${embedding.size}", "SPEAKER")
                        EnrollmentResult.Success(profile)
                    },
                    onFailure = { EnrollmentResult.Failure("Could not save speaker enrollment", it) }
                )
        }

    override suspend fun verify(samplesPcm16: ShortArray, sampleRateHz: Int): SpeakerVerificationResult =
        withContext(Dispatchers.Default) {
            VoxynLogger.d("Speaker verification started: samples=${samplesPcm16.size}, rate=$sampleRateHz", "SPEAKER")
            if (sampleRateHz != 16_000 || samplesPcm16.size < MIN_COMMAND_SAMPLES) {
                return@withContext SpeakerVerificationResult.Rejected("Command audio is shorter than one second")
            }
            val engine = getExtractor()
                ?: return@withContext SpeakerVerificationResult.Unavailable("Speaker model is unavailable")
            migrateExistingRecordings(engine)
            val enrolled = repository.list().filter { it.enrolled && it.speakerEnrolled && repository.hasEmbedding(it.id) }
                .mapNotNull { profile -> repository.loadEmbedding(profile.id)?.let { profile to it } }
            if (enrolled.isEmpty()) {
                VoxynLogger.w("Speaker verification unavailable: no profiles have embeddings for the active model", "SPEAKER")
                return@withContext SpeakerVerificationResult.Unavailable("No speaker profiles are enrolled with the current model")
            }
            val query = computeEmbedding(engine, samplesPcm16, sampleRateHz)
                ?: return@withContext SpeakerVerificationResult.Uncertain("Speaker embedding could not be computed")
            if (enrolled.any { (_, embedding) -> cosine(query, embedding) >= 0.9995f }) {
                return@withContext SpeakerVerificationResult.Rejected("Audio closely matches an enrollment sample; speak a fresh command")
            }
            val ranked = enrolled.map { (profile, embedding) -> profile to cosine(query, embedding) }
                .sortedByDescending { it.second }
            VoxynLogger.d("Speaker match scores: ${ranked.joinToString { "slot=${it.first.slot + 1}:${"%.3f".format(it.second)}" }}", "SPEAKER")
            val best = ranked.first()
            val next = ranked.getOrNull(1)?.second ?: -1f
            when {
                best.second < UNCERTAIN_COSINE -> SpeakerVerificationResult.Rejected("Speaker did not match an enrolled profile", best.second)
                best.second < ACCEPT_COSINE -> SpeakerVerificationResult.Uncertain("Speaker match was below the acceptance threshold", best.second)
                best.second - next < MIN_WINNER_MARGIN -> SpeakerVerificationResult.Uncertain("Multiple speaker profiles matched closely", best.second)
                else -> SpeakerVerificationResult.Accepted(best.first, best.second)
            }
        }

    private fun computeEmbedding(engine: SpeakerEmbeddingExtractor, pcm: ShortArray, sampleRate: Int): FloatArray? {
        val stream = engine.createStream()
        return try {
            val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
            stream.acceptWaveform(audio, sampleRate)
            stream.inputFinished()
            if (!engine.isReady(stream)) return null
            engine.compute(stream).takeIf { it.isNotEmpty() && it.all(Float::isFinite) }
        } catch (error: Exception) {
            VoxynLogger.e("Speaker embedding failed: ${error.javaClass.simpleName}", tag = "SPEAKER")
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    /** Converts pre-verifier Voice Studio WAVs into private embeddings on first verified command. */
    private fun migrateExistingRecordings(engine: SpeakerEmbeddingExtractor) {
        repository.list().filter { it.enrolled && !it.speakerEnrolled && !repository.hasEmbedding(it.id) }
            .forEach { profile ->
                runCatching {
                    val audio = readPcm16Wav(repository.recordingFile(profile.id))
                    require(audio.second == 16_000)
                    val embedding = computeEmbedding(engine, audio.first, audio.second) ?: error("Embedding unavailable")
                    repository.saveEmbedding(profile.id, embedding)
                    repository.markSpeakerEnrolled(profile.id)
                }.onFailure {
                    VoxynLogger.w("Existing voice profile ${profile.slot + 1} needs a new enrollment", "SPEAKER")
                }
            }
    }

    private fun readPcm16Wav(file: java.io.File): Pair<ShortArray, Int> {
        val bytes = file.readBytes()
        require(bytes.size in 44..1_000_000)
        fun ascii(offset: Int, size: Int) = String(bytes, offset, size, Charsets.US_ASCII)
        fun int32(offset: Int) = java.nio.ByteBuffer.wrap(bytes, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        fun uint16(offset: Int) = java.nio.ByteBuffer.wrap(bytes, offset, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        require(ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE")
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var offset = 12
        var dataOffset = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val size = int32(offset + 4).coerceAtLeast(0)
            val body = offset + 8
            if (body + size > bytes.size) break
            when (ascii(offset, 4)) {
                "fmt " -> if (size >= 16) {
                    require(uint16(body) == 1)
                    channels = uint16(body + 2)
                    sampleRate = int32(body + 4)
                    bits = uint16(body + 14)
                }
                "data" -> { dataOffset = body; dataLength = size; break }
            }
            offset = body + size + (size and 1)
        }
        require(channels == 1 && bits == 16 && sampleRate > 0 && dataOffset >= 0 && dataLength >= 2)
        val samples = ShortArray(dataLength / 2) { i ->
            java.nio.ByteBuffer.wrap(bytes, dataOffset + i * 2, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short
        }
        return samples to sampleRate
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        val denominator = sqrt(aa * bb)
        return if (denominator == 0.0) -1f else (dot / denominator).toFloat()
    }
}
