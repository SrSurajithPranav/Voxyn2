package com.voxyn.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private val lock = Object()
    @Volatile private var llm: LlmInference? = null

    override suspend fun answer(question: String, conversationContext: String): AiResponse? =
        withContext(Dispatchers.Default) {
            val inference = load() ?: return@withContext null
            runCatching {
                val prompt = buildPrompt(question, conversationContext)
                VoxynLogger.i("[AI] Local LLM generating...", "AI")
                val response = inference.generateResponse(prompt)
                val text = response.trim().takeIf { it.isNotBlank() }
                if (text != null) {
                    VoxynLogger.i("[AI] Local LLM answered (${text.length} chars)", "AI")
                    AiResponse(text, "Local AI")
                } else null
            }.onFailure {
                VoxynLogger.e("Local LLM failed: ${it.message}", tag = "AI")
            }.getOrNull()
        }

    private fun load(): LlmInference? {
        llm?.let { return it }
        if (!modelManager.isModelPresent()) return null
        synchronized(lock) {
            llm?.let { return it }
            val inference = runCatching {
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelManager.modelFile.absolutePath)
                        .setMaxTokens(512)
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
        val ctx = conversationContext.ifBlank { "No prior conversation." }
        return """
            You are VOXYN, a helpful Android assistant. Answer briefly and naturally, in one to three spoken sentences. Never claim to have performed an action. If you don't know, say so.

            Prior conversation:
            $ctx

            User: $question
            VOXYN:""".trimIndent()
    }
}
