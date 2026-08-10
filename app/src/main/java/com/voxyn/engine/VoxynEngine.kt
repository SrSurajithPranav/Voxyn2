package com.voxyn.engine

import android.content.Context
import android.content.Intent
import com.voxyn.ai.ClaudeIntentResolver
import com.voxyn.ai.ClaudeResult
import com.voxyn.ai.KnowledgeProvider
import com.voxyn.ai.LocalKnowledgeProvider
import com.voxyn.ai.WorldTimeProvider
import com.voxyn.core.*
import com.voxyn.execution.ExecutionController
import com.voxyn.intent.IntentRouter
import com.voxyn.memory.MemoryRepository
import com.voxyn.mode.ModeEngine
import com.voxyn.overlay.OverlayService
import com.voxyn.perception.SpeechNormalizer
import com.voxyn.router.RequestRouter
import com.voxyn.router.RouteDecision
import com.voxyn.router.RouteType
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.ConversationSession
import com.voxyn.voice.TextToSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class VoxynEngine(
    private val scope          : CoroutineScope,
    private val context        : Context,
    private val memory         : MemoryRepository,
    private val intentRouter   : IntentRouter,
    private val executor       : ExecutionController,
    private val tts            : TextToSpeechManager,
    private val conversationSession : ConversationSession,
    private val speechNormalizer : SpeechNormalizer,
    private val requestRouter    : RequestRouter,
    private val worldTimeProvider: WorldTimeProvider,
    private val knowledgeProvider: KnowledgeProvider,
    private val localKnowledge   : LocalKnowledgeProvider,
    private val claudeResolver : ClaudeIntentResolver? = null,
    private val modeEngine     : ModeEngine? = null,
    private val phaseTimeoutMs : Long = 10_000L
) {
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events

    private var activeJob    : Job? = null
    private var pendingInput : String? = null
    private var pipelineGen  = 0L

    @Volatile
    private var processing = false

    fun isProcessing(): Boolean = processing

    fun process(input: String) {
        activeJob?.cancel()
        val gen = ++pipelineGen
        processing = true
        activeJob = scope.launch {
            try {
                runPipeline(input)
            } finally {
                if (gen == pipelineGen) processing = false
            }
        }
    }

    fun interrupt(reason: String = "Interrupted") {
        activeJob?.cancel()
        emit(PipelineEvent.EngineInterrupted(reason))
        tts.speak("Cancelled.")
        setOverlay("IDLE")
    }

    fun resumePending() { pendingInput?.let { process(it) } }

    // ─── Pipeline ─────────────────────────────────────────────────────

    private suspend fun runPipeline(raw: String) {
        VoxynLogger.i("▶ Starting pipeline for: \"$raw\"", "ENGINE")
        emit(PipelineEvent.PhaseStarted(PipelinePhase.INPUT))
        emit(PipelineEvent.PhaseCompleted(PipelinePhase.INPUT, raw))

        setOverlay("LISTENING")

        val perceived = phase(PipelinePhase.PERCEPTION) { perceive(raw) } ?: return
        setOverlay("PROCESSING")

        // Hybrid routing: memory / current information / web / knowledge first.
        val route = requestRouter.route(perceived)
        VoxynLogger.i("[ROUTER] ${route.type} confidence=${route.confidence}", "ROUTER")
        when (route.type) {
            RouteType.MEMORY_SAVE -> { handleMemorySave(route); return }
            RouteType.MEMORY_RECALL -> { handleMemoryRecall(route); return }
            RouteType.WEB_REQUIRED -> { handleNews(route); return }
            RouteType.CONVERSATIONAL -> { handleKnowledge(route, perceived); return }
            RouteType.CURRENT_INFORMATION -> {
                if (route.params.containsKey("city")) { handleWorldTime(route); return }
            }
            else -> Unit
        }

        var intent = phase(PipelinePhase.INTENT) { resolveIntent(perceived) } ?: return
        var claudeSpeak: String? = null

        // Claude fallback for UNKNOWN intent
        if (intent.action == "UNKNOWN" && claudeResolver != null) {
            VoxynLogger.i("Local router returned UNKNOWN — invoking Claude AI fallback", "ENGINE")
            setOverlay("PROCESSING", "Thinking...")
            acknowledge()
            val result: ClaudeResult? = try {
                val contextPrompt = conversationSession.getContext().ifEmpty { "No prior conversation." }
                withTimeout(12_000L) { claudeResolver.resolve("$perceived\n\nConversation context:\n$contextPrompt") }
            } catch (e: Exception) {
                VoxynLogger.e("Claude error: ${e.message}", tag = "ENGINE"); null
            }
            if (result != null && result.intent.action != "UNKNOWN") {
                intent = result.intent
                claudeSpeak = result.speakOverride
                VoxynLogger.i("Claude resolved intent: ${intent.action}", "ENGINE")
            }
        }

        if (handleConversational(intent, perceived)) return

        if (intent.action == "UNKNOWN") {
            val phrase = unknownResponse()
            conversationSession.updateLastTurn("UNKNOWN", phrase)
            emit(PipelineEvent.ClarificationRequired(listOf("Unknown command")))
            setOverlay("ERROR", "Unknown command")
            delay(1500L)
            setOverlay("IDLE")
            return
        }

        val confidence = phase(PipelinePhase.CONFIDENCE) { scoreConfidence(intent) } ?: return
        val risk       = phase(PipelinePhase.RISK)       { assessRisk(intent) }       ?: return
        val decision   = phase(PipelinePhase.DECISION)   { decide(intent, confidence, risk) } ?: return

        emit(PipelineEvent.DecisionMade(decision))

        when (decision) {
            is DecisionState.AwaitingConfirmation -> {
                pendingInput = raw
                val promptText = "Confirm ${friendlyAction(intent.action)}?"
                setOverlay("CONFIRMATION", promptText)
                tts.speak("Just to confirm — should I ${friendlyAction(intent.action)}? Say yes.")
                emit(PipelineEvent.ConfirmationRequired(decision.intent))
                return
            }
            is DecisionState.AwaitingClarification -> {
                pendingInput = raw
                val msg = decision.missing.firstOrNull() ?: "please clarify"
                setOverlay("ERROR", msg)
                tts.speak("I need more info: $msg.")
                emit(PipelineEvent.ClarificationRequired(decision.missing))
                delay(1500L)
                setOverlay("IDLE")
                return
            }
            is DecisionState.Rejected -> {
                setOverlay("ERROR", "Action rejected")
                tts.speak("Action rejected.")
                delay(1500L)
                setOverlay("IDLE")
                return
            }
            is DecisionState.Execute -> Unit
        }

        acknowledge()

        // ─── Handling Smart Chain Commands ───────────────────────────
        if (intent.action == "CHAIN" || intent.params.containsKey("steps")) {
            val stepsStr = intent.params["steps"] ?: "[]"
            runChainExecution(intent.action, stepsStr, claudeSpeak)
            return
        }

        // Single Step Execution
        val actionText = friendlyAction(intent.action)
        setOverlay("SPEAKING", actionText)

        val plan     = phase(PipelinePhase.PLAN)   { buildPlan(intent) } ?: return
        val executed = executeWithRecovery(plan)    ?: return
        val outcome  = phase(PipelinePhase.VERIFY) { verify(executed) }  ?: return

        emit(PipelineEvent.VerificationResult(outcome))

        val isSuccess = outcome is VerificationOutcome.Success
        setOverlay(if (isSuccess) "SUCCESS" else "ERROR", if (isSuccess) "Completed" else "Failed")

        val speech = claudeSpeak ?: friendlySuccess(intent.action, intent.params, outcome)
        tts.speak(speech)
        conversationSession.updateLastTurn(intent.action, speech)

        phase(PipelinePhase.LEARN) { learn(intent, outcome) }
        pendingInput = null
        emit(PipelineEvent.PipelineComplete)
        VoxynLogger.i("✓ Pipeline complete", "ENGINE")

        delay(1500L)
        setOverlay("IDLE")
    }

    // ─── Smart Chain Execution ────────────────────────────────────────

    private suspend fun runChainExecution(action: String, stepsJsonStr: String, claudeSpeak: String?) {
        VoxynLogger.i("Executing Smart Chain sequence: $stepsJsonStr", "ENGINE")
        val stepsList = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(stepsJsonStr)
            for (i in 0 until arr.length()) {
                stepsList.add(arr.getString(i))
            }
        }.onFailure {
            stepsList.addAll(stepsJsonStr.replace("[", "").replace("]", "").replace("\"", "").split(","))
        }

        val chainPlanSteps = mutableListOf<ActionStep>()
        stepsList.forEachIndexed { idx, rawStep ->
            val clean = rawStep.trim()
            val (act, params) = if (clean.contains(":")) {
                val parts = clean.split(":")
                parts[0] to mapOf("app_name" to parts[1], "contact" to parts[1], "destination" to parts[1])
            } else {
                clean to emptyMap()
            }
            chainPlanSteps.add(ActionStep("step_$idx", act, act, params))
        }

        val plan = ExecutionPlan(chainPlanSteps)
        emit(PipelineEvent.PhaseCompleted(PipelinePhase.PLAN, "Chain plan built (${chainPlanSteps.size} steps)"))

        if (!claudeSpeak.isNullOrEmpty()) {
            tts.speak(claudeSpeak)
        }

        var cur = plan
        while (!cur.isComplete) {
            val step = cur.currentAction ?: break
            emit(PipelineEvent.ExecutionStepStarted(step))

            val friendly = friendlyAction(step.actionType)
            setOverlay("SPEAKING", friendly)
            tts.speak(friendly)

            val result = executor.execute(step)
            if (result is PhaseResult.Failure) {
                setOverlay("ERROR", "Chain failed at ${step.actionType}")
                tts.speak("Failed to ${step.actionType}")
                emit(PipelineEvent.PhaseFailed(PipelinePhase.EXECUTE, result.error, false))
                delay(1500L)
                setOverlay("IDLE")
                return
            }

            emit(PipelineEvent.ExecutionStepCompleted(step, (result as PhaseResult.Success).value))
            cur = cur.advance()
            delay(500L) // 500ms gap between steps
        }

        val outcome = VerificationOutcome.Success
        emit(PipelineEvent.VerificationResult(outcome))
        setOverlay("SUCCESS", "Chain complete")
        tts.speak("Sequence completed.")
        conversationSession.updateLastTurn(action, claudeSpeak ?: "Sequence completed.")
        pendingInput = null
        emit(PipelineEvent.PipelineComplete)
        VoxynLogger.i("✓ Chain execution completed successfully", "ENGINE")

        delay(1500L)
        setOverlay("IDLE")
    }

    // ─── Recovery ─────────────────────────────────────────────────────

    private suspend fun executeWithRecovery(plan: ExecutionPlan): ExecutionPlan? {
        var lastError: String? = null
        val first = executeAttempt(plan)
        if (first is PhaseResult.Success) return first.value
        if (first is PhaseResult.Failure) lastError = first.error
        VoxynLogger.w("Attempt 1 failed — retrying", "ENGINE")
        val second = executeAttempt(plan)
        if (second is PhaseResult.Success) return second.value
        if (second is PhaseResult.Failure) lastError = second.error
        VoxynLogger.w("Both attempts failed", "ENGINE")
        val reason = lastError?.takeIf { it.isNotBlank() } ?: "I couldn't complete that action."
        tts.speak(reason)
        emit(PipelineEvent.ClarificationRequired(listOf(lastError ?: "Execution failed")))
        setOverlay("ERROR", "Failed")
        return null
    }

    private suspend fun executeAttempt(plan: ExecutionPlan): PhaseResult<ExecutionPlan> {
        emit(PipelineEvent.PhaseStarted(PipelinePhase.EXECUTE))
        return try {
            withTimeout(phaseTimeoutMs) { executePlan(plan) }.also { r ->
                when (r) {
                    is PhaseResult.Success -> {
                        emit(PipelineEvent.PhaseCompleted(PipelinePhase.EXECUTE, r.value.toString().take(80)))
                    }
                    is PhaseResult.Failure -> {
                        emit(PipelineEvent.PhaseFailed(PipelinePhase.EXECUTE, r.error, r.canFallback))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            emit(PipelineEvent.PhaseFailed(PipelinePhase.EXECUTE, "Timeout", false))
            PhaseResult.Failure("That took too long. Try again.", false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(PipelineEvent.PhaseFailed(PipelinePhase.EXECUTE, e.message ?: "Error", true))
            PhaseResult.Failure(e.message ?: "Execution failed", true)
        }
    }

    // ─── Phase runner ─────────────────────────────────────────────────

    private suspend fun <T> phase(p: PipelinePhase, block: suspend () -> PhaseResult<T>): T? {
        if (activeJob?.isActive != true) return null
        emit(PipelineEvent.PhaseStarted(p))
        return try {
            when (val r = withTimeout(phaseTimeoutMs) { block() }) {
                is PhaseResult.Success -> {
                    emit(PipelineEvent.PhaseCompleted(p, r.value.toString().take(80)))
                    r.value
                }
                is PhaseResult.Failure -> {
                    emit(PipelineEvent.PhaseFailed(p, r.error, r.canFallback))
                    null
                }
            }
        } catch (e: TimeoutCancellationException) {
            emit(PipelineEvent.PhaseFailed(p, "Timeout", false)); null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            emit(PipelineEvent.PhaseFailed(p, e.message ?: "Error", true)); null
        }
    }

    // ─── Phase implementations ────────────────────────────────────────

    private fun perceive(raw: String): PhaseResult<String> {
        VoxynLogger.i("[REQUEST] \"$raw\"", "ENGINE")
        val c = speechNormalizer.normalize(raw)
        return if (c.isEmpty()) PhaseResult.Failure("Empty input") else PhaseResult.Success(c)
    }

    // ─── Hybrid conversational handlers ────────────────────────────────

    private fun handleMemorySave(route: RouteDecision) {
        val note = route.params["note"]
        if (note.isNullOrBlank()) {
            unknownResponse()
            setOverlay("IDLE")
            return
        }
        setOverlay("PROCESSING", "Remembering...")
        scope.launch { memory.saveNote(note) }
        completeConversational("MEMORY_SAVE", "Got it. I'll remember that.")
    }

    private suspend fun handleMemoryRecall(route: RouteDecision) {
        val topic = route.params["topic"] ?: ""
        val notes = memory.recallNotes(topic)
        val speech = when {
            notes.isEmpty() && topic.isBlank() ->
                "I don't remember anything yet. Tell me something and I'll keep it in mind."
            notes.isEmpty() -> "I don't remember anything about $topic."
            else -> "You told me: ${notes.joinToString(" And also: ")}"
        }
        completeConversational("MEMORY_RECALL", speech)
    }

    private suspend fun handleWorldTime(route: RouteDecision) {
        val city = route.params["city"] ?: return
        val speech = worldTimeProvider.timeIn(city) ?: "I don't know the time zone for $city."
        completeConversational("WORLD_TIME", speech)
    }

    private suspend fun handleNews(route: RouteDecision) {
        val query = route.params["query"] ?: ""
        setOverlay("PROCESSING", "Fetching news...")
        VoxynLogger.i("[WEB] Fetching news: \"${query.ifBlank { "top stories" }}\"", "WEB")
        val result = knowledgeProvider.news(query)
        if (result != null) {
            completeConversational("NEWS", result.text)
        } else {
            completeConversational("NEWS", localKnowledge.offlineNotice())
        }
    }

    private suspend fun handleKnowledge(route: RouteDecision, perceived: String) {
        val question = route.params["query"] ?: perceived
        setOverlay("PROCESSING", "Thinking...")
        if (question.contains("joke", ignoreCase = true)) {
            completeConversational("KNOWLEDGE", localKnowledge.joke())
            return
        }
        VoxynLogger.i("[AI] Knowledge query: \"$question\"", "AI")
        val result = knowledgeProvider.knowledge(question)
        if (result != null) {
            completeConversational("KNOWLEDGE", result.text)
        } else {
            completeConversational("KNOWLEDGE", localKnowledge.offlineNotice())
        }
    }

    private fun completeConversational(action: String, speech: String) {
        tts.speak(speech)
        conversationSession.updateLastTurn(action, speech)
        emit(PipelineEvent.PipelineComplete)
        VoxynLogger.i("✓ Conversational response: \"$speech\"", "ENGINE")
        setOverlay("IDLE")
    }

    /**
     * Natural conversational replies (time, date, greetings, small talk) so
     * VOXYN feels like a person rather than a command shell.
     */
    private fun handleConversational(intent: IntentResult, perceived: String): Boolean {
        val response: String? = when (intent.action) {
            "TIME" -> "It's ${SimpleDateFormat("h:mm a", Locale.US).format(Calendar.getInstance().time)}."
            "DATE" -> "Today is ${SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Calendar.getInstance().time)}."
            "GREETING" -> when {
                perceived.contains("good morning") -> "Good morning! How can I help?"
                perceived.contains("good afternoon") -> "Good afternoon! What do you need?"
                perceived.contains("good evening") -> "Good evening! What do you need?"
                else -> "Hey! What do you need?"
            }
            "THANKS" -> "Anytime."
            "CAPABILITIES" -> "I can open apps, control WiFi, Bluetooth, volume, flashlight, brightness, set timers and alarms, take screenshots, and more."
            "SMALLTALK" -> when {
                perceived.contains("how are you") -> "I'm doing great. What do you need?"
                perceived.contains("who are you") -> "I'm VOXYN, your personal assistant."
                perceived.contains("your name") -> "I'm VOXYN."
                perceived.contains("are you") -> "Always here for you."
                perceived.contains("what's that") || perceived.contains("what is that") -> "That's me — VOXYN."
                perceived.contains("what's up") || perceived.contains("what is up") -> "Not much. What do you need?"
                perceived.contains("good job") || perceived.contains("great") -> "Thanks! Happy to help."
                else -> null
            }
            else -> null
        }
        if (response == null) return false

        tts.speak(response)
        conversationSession.updateLastTurn(intent.action, response)
        setOverlay("IDLE")
        emit(PipelineEvent.PipelineComplete)
        VoxynLogger.i("✓ Conversational response: \"$response\"", "ENGINE")
        return true
    }

    private fun resolveIntent(perceived: String): PhaseResult<IntentResult> =
        PhaseResult.Success(intentRouter.route(perceived))

    private suspend fun scoreConfidence(intent: IntentResult): PhaseResult<Float> {
        val trust = memory.getTrustScore(intent.action)
        return PhaseResult.Success((intent.confidence * 0.7f + trust * 0.3f).coerceIn(0f, 1f))
    }

    private fun assessRisk(intent: IntentResult): PhaseResult<Float> =
        PhaseResult.Success(if (intent.action in HIGH_RISK) 0.9f else 0.1f)

    private fun decide(intent: IntentResult, confidence: Float, risk: Float): PhaseResult<DecisionState> =
        PhaseResult.Success(when {
            confidence < 0.25f -> DecisionState.AwaitingClarification(listOf("Low confidence"))
            risk > 0.8f        -> DecisionState.AwaitingConfirmation(intent)
            else               -> DecisionState.Execute
        })

    private fun buildPlan(intent: IntentResult): PhaseResult<ExecutionPlan> =
        PhaseResult.Success(ExecutionPlan(listOf(
            ActionStep("step_0", intent.action, intent.action, intent.params)
        )))

    private suspend fun executePlan(plan: ExecutionPlan): PhaseResult<ExecutionPlan> {
        var cur = plan
        while (!cur.isComplete) {
            val step = cur.currentAction ?: break
            emit(PipelineEvent.ExecutionStepStarted(step))
            val result = executor.execute(step)
            if (result is PhaseResult.Failure) return result
            emit(PipelineEvent.ExecutionStepCompleted(step, (result as PhaseResult.Success).value))
            cur = cur.advance()
        }
        return PhaseResult.Success(cur)
    }

    private fun verify(plan: ExecutionPlan): PhaseResult<VerificationOutcome> =
        PhaseResult.Success(if (plan.isComplete) VerificationOutcome.Success else VerificationOutcome.PartialSuccess)

    private suspend fun learn(intent: IntentResult, outcome: VerificationOutcome): PhaseResult<Float> {
        memory.recordOutcome(intent.action, outcome is VerificationOutcome.Success)
        val score = memory.getTrustScore(intent.action)
        emit(PipelineEvent.TrustScoreUpdated(intent.action, score))
        return PhaseResult.Success(score)
    }

    private fun emit(event: PipelineEvent) { scope.launch { _events.emit(event) } }

    private fun setOverlay(state: String, text: String? = null) {
        // Use singleton directly to avoid startService latency and race conditions
        val overlay = OverlayService.instance
        if (overlay != null) {
            overlay.setState(state, text)
        } else {
            // Service not running yet — start it (state will be queued internally)
            try {
                context.startService(
                    Intent(context, OverlayService::class.java).apply {
                        putExtra("state", state)
                        text?.let { putExtra("text", it) }
                    }
                )
            } catch (_: Exception) {}
        }
    }

    // ─── Friendly messages ────────────────────────────────────────────

    private fun friendlyAction(action: String) = when (action) {
        "WIFI_ON"              -> "turn on WiFi"
        "WIFI_OFF"             -> "turn off WiFi"
        "BLUETOOTH_ON"         -> "enable Bluetooth"
        "FLASHLIGHT_ON"        -> "turn on flashlight"
        "SILENT_MODE"          -> "enable silent mode"
        "SEND_CURRENT_MESSAGE" -> "send message"
        "LIKE_CURRENT"         -> "like current"
        "SCROLL_DOWN"          -> "scroll down"
        "SCROLL_UP"            -> "scroll up"
        "GO_BACK"              -> "go back"
        "GO_HOME"              -> "go home"
        "READ_NOTIFICATIONS"   -> "read notifications"
        "REMEMBER_THIS"        -> "remember this screen"
        "CALL_CONTACT"         -> "make call"
        else                   -> action.replace("_", " ").lowercase()
    }

    private fun friendlySuccess(action: String, params: Map<String, String>, outcome: VerificationOutcome): String {
        if (outcome !is VerificationOutcome.Success) return "Done, but something may not have worked."
        return when (action) {
            "WIFI_ON"              -> "WiFi's on."
            "WIFI_OFF"             -> "WiFi's off."
            "FLASHLIGHT_ON"        -> "Flashlight on."
            "FLASHLIGHT_OFF"       -> "Light's out."
            "VOLUME_UP"            -> "Turned it up."
            "VOLUME_DOWN"          -> "Turned it down."
            "SILENT_MODE"          -> "Going silent."
            "VIBRATE_MODE"         -> "Vibrate on."
            "NORMAL_MODE"          -> "Ringer on."
            "BLUETOOTH_ON"         -> "Bluetooth on."
            "BLUETOOTH_OFF"        -> "Bluetooth off."
            "BATTERY_SAVER_ON"     -> "Battery saver on."
            "OPEN_APP"             -> "Opening ${params["app_name"] ?: "app"}."
            "CALL_CONTACT"         -> "Calling ${params["contact"] ?: "contact"} now."
            "SET_ALARM"            -> "Alarm set."
            "SET_TIMER"            -> "Timer's running."
            "NAVIGATE"             -> "Navigation started."
            "SCREENSHOT"           -> "Got it."
            "PLAY_MUSIC"           -> "Playing."
            "PAUSE_MUSIC"          -> "Paused."
            "NEXT_TRACK"           -> "Next track."
            "PREV_TRACK"           -> "Previous track."
            "BRIGHTNESS_UP"        -> "Brighter."
            "BRIGHTNESS_DOWN"      -> "Dimmed."
            "LOCK_PHONE"           -> "Locking."
            "MODE_ACTIVATE"        -> "${params["mode_name"]?.replaceFirstChar { it.uppercase() } ?: "Mode"} mode activated."
            "SEND_CURRENT_MESSAGE" -> "Message sent."
            "LIKE_CURRENT"         -> "Liked."
            "SCROLL_DOWN"          -> "Scrolled down."
            "SCROLL_UP"            -> "Scrolled up."
            "GO_BACK"              -> "Going back."
            "GO_HOME"              -> "Going home."
            "READ_NOTIFICATIONS"   -> "Opening notifications."
            "REMEMBER_THIS"        -> "Saved to memory."
            else                   -> "Done."
        }
    }

    private fun acknowledge() {
        tts.speak(ACKNOWLEDGEMENTS.random())
    }

    private fun unknownResponse(): String {
        val phrase = UNKNOWN_RESPONSES.random()
        tts.speak(phrase)
        return phrase
    }

    companion object {
        private val HIGH_RISK = setOf("SEND_MESSAGE", "CALL_CONTACT", "DELETE_FILE", "FACTORY_RESET")
        private val ACKNOWLEDGEMENTS = listOf(
            "On it.", "Give me a sec.", "Right away.", "Let me handle that.", "Sure thing."
        )
        private val UNKNOWN_RESPONSES = listOf(
            "Didn't catch that. Try again?",
            "Not sure what you mean. Say it differently?",
            "I missed that. What do you need?"
        )
    }
}
