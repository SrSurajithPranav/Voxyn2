package com.voxyn.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LocalLlmProvider — on-device LLM via MediaPipe GenAI (Gemma/LLaMA .task
 * models). Runs entirely offline; used for general conversation and knowledge
 * before falling back to the web. Returns null when the model is not installed
 * or fails to load, so the engine degrades gracefully.
 */
class LocalLlmProvider(
    private val context: Context,
    private val modelManager: LocalModelManager
) : AiProvider {

    override val name: String = "Local AI"

    private val initLock = Object()
    private val inferenceMutex = Mutex()
    @Volatile private var llm: LlmInference? = null

    override suspend fun answer(question: String, conversationContext: String): AiResponse? =
        withContext(Dispatchers.Default) {
            val inference = load() ?: return@withContext null
            inferenceMutex.withLock {
                runCatching {
                    val prompt = buildPrompt(question, conversationContext)
                    VoxynLogger.i("[AI] Local LLM generating...", "AI")
                    val response = inference.generateResponse(prompt)
                    val rawText = response.trim().takeIf { it.isNotBlank() }
                    val text = rawText?.replaceFirst(Regex("^(?:VOXYN|VOXIN|Assistant|AI|Bot)\\s*[:\\-–—]?\\s*", RegexOption.IGNORE_CASE), "")?.trim()
                    if (!text.isNullOrBlank()) {
                        VoxynLogger.i("[AI] Local LLM answered (${text.length} chars)", "AI")
                        AiResponse(text, "Local AI")
                    } else null
                }.onFailure {
                    VoxynLogger.e("Local LLM failed: ${it.message}", tag = "AI")
                    // Reset on error so subsequent requests re-initialize cleanly
                    synchronized(initLock) {
                        try { (llm as? AutoCloseable)?.close() } catch (_: Throwable) {}
                        llm = null
                    }
                }.getOrNull()
            }
        }

    private fun load(): LlmInference? {
        llm?.let { return it }
        if (!modelManager.isModelPresent()) return null
        synchronized(initLock) {
            llm?.let { return it }
            val inference = runCatching {
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelManager.modelFile.absolutePath)
                        .setMaxTokens(1024)
                        .build()
                )
            }.onFailure {
                VoxynLogger.e("Local LLM load failed: ${it.message}", tag = "AI")
            }.getOrNull()
            llm = inference
            return inference
        }
    }

    private fun buildPrompt(question: String, conversationContext: String): String {
        // Sanitize conversation context: normalize smart quotes and trim length
        // to prevent KV-cache / BPE tokenization issues in native MediaPipe engine.
        val cleanCtx = conversationContext
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .trim()
            .takeLast(400)
            .ifBlank { "None." }

        val cleanQuestion = question
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .trim()
            .take(200)

        return """
            You are VOXYN, a loyal and helpful Android AI assistant. Always address the user politely as "Sir". Answer briefly and naturally in one to three sentences.
            Context: $cleanCtx
            User: $cleanQuestion
            VOXYN:""".trimIndent()
    }
}
