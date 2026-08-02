package com.voxyn.engine

import android.content.Context
import android.content.Intent
<<<<<<< HEAD
=======
import android.media.AudioManager
import android.media.ToneGenerator
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
import com.voxyn.ai.ClaudeIntentResolver
import com.voxyn.ai.ClaudeResult
import com.voxyn.core.*
import com.voxyn.execution.ExecutionController
import com.voxyn.intent.IntentRouter
import com.voxyn.memory.MemoryRepository
import com.voxyn.mode.ModeEngine
import com.voxyn.overlay.OverlayService
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.TextToSpeechManager
import com.voxyn.voice.VoiceInputService
import com.voxyn.wake.WakeWordService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray

class VoxynEngine(
    private val scope          : CoroutineScope,
<<<<<<< HEAD
    private val context        : Context,
=======
    private val appContext     : Context,
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
    private val memory         : MemoryRepository,
    private val intentRouter   : IntentRouter,
    private val executor       : ExecutionController,
    private val tts            : TextToSpeechManager,
    private val claudeResolver : ClaudeIntentResolver? = null,
    private val modeEngine     : ModeEngine? = null,
    private val phaseTimeoutMs : Long = 10_000L
) {
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events

<<<<<<< HEAD
    private var activeJob    : Job? = null
    private var pendingInput : String? = null
=======
    private var activeJob         : Job? = null
    private var pendingInput      : String? = null
    private var skipConfirmation  : Boolean = false
    private var isSessionActive   : Boolean = false
    private val conversationHistory = mutableListOf<String>()

    private val YES_WORDS = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay",
        "confirm", "do it", "proceed", "go ahead", "affirmative")
    private val NO_WORDS  = setOf("no", "nope", "nah", "cancel", "stop", "abort",
        "never mind", "nevermind", "don't", "dont")
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)

    fun process(input: String) {
        activeJob?.cancel()
        activeJob = scope.launch { runPipeline(input) }
    }

    fun interrupt(reason: String = "Interrupted") {
        activeJob?.cancel()
        emit(PipelineEvent.EngineInterrupted(reason))
        tts.speak("Cancelled.")
<<<<<<< HEAD
        setOverlay("IDLE")
=======
        if (isSessionActive) {
            continueSessionAfterPrompt()
        } else {
            resumeWakeListening()
        }
    }

    fun confirm() {
        val pending = pendingInput ?: return
        skipConfirmation = true
        activeJob?.cancel()
        activeJob = scope.launch { runPipeline(pending) }
    }

    fun deny() {
        pendingInput = null
        skipConfirmation = false
        activeJob?.cancel()
        emit(PipelineEvent.EngineInterrupted("User cancelled"))
        tts.speak("Cancelled.")
        if (isSessionActive) {
            continueSessionAfterPrompt()
        } else {
            resumeWakeListening()
        }
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
    }

    fun resumePending() { pendingInput?.let { process(it) } }

    fun onFollowUpTimeout() {
        isSessionActive = false
        conversationHistory.clear()
        resumeWakeListening()
        VoxynLogger.i("Conversation session ended after silence timeout", "ENGINE")
    }

    // ─── Pipeline ─────────────────────────────────────────────────────

    private suspend fun runPipeline(raw: String) {
<<<<<<< HEAD
        VoxynLogger.i("▶ Starting pipeline for: \"$raw\"", "ENGINE")
        emit(PipelineEvent.PhaseStarted(PipelinePhase.INPUT))
        emit(PipelineEvent.PhaseCompleted(PipelinePhase.INPUT, raw))

        setOverlay("LISTENING")

        val perceived = phase(PipelinePhase.PERCEPTION) { perceive(raw) } ?: return
        setOverlay("PROCESSING")

        var intent = phase(PipelinePhase.INTENT) { resolveIntent(perceived) } ?: return
=======
        VoxynLogger.i("▶ \"$raw\"", "ENGINE")
        tts.stop()

        conversationHistory.add(raw)
        if (conversationHistory.size > 12) {
            conversationHistory.removeAt(0)
        }

        val perceived = phase(PipelinePhase.PERCEPTION) { perceive(raw) }
            ?: return onPipelineEarlyReturn()
        var intent = phase(PipelinePhase.INTENT) { resolveIntent(perceived) }
            ?: return onPipelineEarlyReturn()
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        var claudeSpeak: String? = null

        // Claude fallback for UNKNOWN intent
        if (intent.action == "UNKNOWN" && claudeResolver != null) {
            VoxynLogger.i("Local router returned UNKNOWN — invoking Claude AI fallback", "ENGINE")
            setOverlay("PROCESSING", "Thinking...")
            tts.speak("Let me check...")
            val result: ClaudeResult? = try {
                withTimeout(12_000L) {
                    claudeResolver.resolve(
                        userInput = perceived,
                        conversationHistory = conversationHistory
                    )
                }
            } catch (e: Exception) {
                VoxynLogger.e("Claude error: ${e.message}", tag = "ENGINE"); null
            }
            if (result != null && result.intent.action != "UNKNOWN") {
                intent = result.intent
                claudeSpeak = result.speakOverride
                VoxynLogger.i("Claude resolved intent: ${intent.action}", "ENGINE")
            }
        }

        if (intent.action == "UNKNOWN") {
            tts.speak("I didn't understand that command. Try saying turn on WiFi, open YouTube, or scroll down.")
            emit(PipelineEvent.ClarificationRequired(listOf("Unknown command")))
<<<<<<< HEAD
            setOverlay("ERROR", "Unknown command")
            delay(1500L)
            setOverlay("IDLE")
=======
            if (isSessionActive) continueSessionAfterPrompt()
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
            return
        }

        val confidence = phase(PipelinePhase.CONFIDENCE) { scoreConfidence(intent) }
            ?: return onPipelineEarlyReturn()
        val risk = phase(PipelinePhase.RISK) { assessRisk(intent) }
            ?: return onPipelineEarlyReturn()
        val decision = phase(PipelinePhase.DECISION) { decide(intent, confidence, risk) }
            ?: return onPipelineEarlyReturn()

        emit(PipelineEvent.DecisionMade(decision))

        when (decision) {
            is DecisionState.AwaitingConfirmation -> {
                pendingInput = raw
                val promptText = "Confirm ${friendlyAction(intent.action)}?"
                setOverlay("CONFIRMATION", promptText)
                tts.speak("Should I ${friendlyAction(intent.action)}? Say yes to confirm.")
                emit(PipelineEvent.ConfirmationRequired(decision.intent))
                if (isSessionActive) continueSessionAfterPrompt()
                return
            }
            is DecisionState.AwaitingClarification -> {
                pendingInput = raw
                val msg = decision.missing.firstOrNull() ?: "please clarify"
                setOverlay("ERROR", msg)
                tts.speak("I need more info: $msg.")
                emit(PipelineEvent.ClarificationRequired(decision.missing))
<<<<<<< HEAD
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

        // ─── Handling Smart Chain Commands ───────────────────────────
        if (intent.action == "CHAIN" || intent.params.containsKey("steps")) {
            val stepsStr = intent.params["steps"] ?: "[]"
            runChainExecution(stepsStr, claudeSpeak)
            return
        }

        // Single Step Execution
        val actionText = friendlyAction(intent.action)
        setOverlay("SPEAKING", actionText)

        val plan     = phase(PipelinePhase.PLAN)   { buildPlan(intent) } ?: return
        val executed = executeWithRecovery(plan)    ?: return
        val outcome  = phase(PipelinePhase.VERIFY) { verify(executed) }  ?: return
=======
                if (isSessionActive) continueSessionAfterPrompt()
                return
            }
            is DecisionState.Rejected -> {
                tts.speak("That action was rejected.")
                if (isSessionActive) continueSessionAfterPrompt()
                return
            }
            is DecisionState.Execute  -> Unit
        }

        val plan = phase(PipelinePhase.PLAN) { buildPlan(intent) }
            ?: return onPipelineEarlyReturn()
        val executed = executeWithRecovery(plan)
            ?: return onPipelineEarlyReturn()
        val outcome = phase(PipelinePhase.VERIFY) { verify(executed) }
            ?: return onPipelineEarlyReturn()
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)

        emit(PipelineEvent.VerificationResult(outcome))

        val isSuccess = outcome is VerificationOutcome.Success
        setOverlay(if (isSuccess) "SUCCESS" else "ERROR", if (isSuccess) "Completed" else "Failed")

        val speech = claudeSpeak ?: friendlySuccess(intent.action, intent.params, outcome)
        tts.speak(speech)

        phase(PipelinePhase.LEARN) { learn(intent, outcome) }
        pendingInput = null
        emit(PipelineEvent.PipelineComplete)
<<<<<<< HEAD
        VoxynLogger.i("✓ Pipeline complete", "ENGINE")

        delay(1500L)
        setOverlay("IDLE")
=======

        if (shouldKeepSessionActive(intent.action)) {
            isSessionActive = true
            playFollowUpPromptChime()
            startFollowUpListening()
        } else {
            isSessionActive = false
            conversationHistory.clear()
            resumeWakeListening()
        }

        VoxynLogger.i("✓ Done", "ENGINE")
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
    }

    // ─── Smart Chain Execution ────────────────────────────────────────

    private suspend fun runChainExecution(stepsJsonStr: String, claudeSpeak: String?) {
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
        pendingInput = null
        emit(PipelineEvent.PipelineComplete)
        VoxynLogger.i("✓ Chain execution completed successfully", "ENGINE")

        delay(1500L)
        setOverlay("IDLE")
    }

    // ─── Recovery ─────────────────────────────────────────────────────

    private suspend fun executeWithRecovery(plan: ExecutionPlan): ExecutionPlan? {
        phase(PipelinePhase.EXECUTE) { executePlan(plan) }?.let { return it }
        VoxynLogger.w("Attempt 1 failed — retrying", "ENGINE")
        phase(PipelinePhase.EXECUTE) { executePlan(plan) }?.let { return it }
        VoxynLogger.w("Both attempts failed", "ENGINE")
        tts.speak("I couldn't complete that action.")
        emit(PipelineEvent.ClarificationRequired(listOf("Execution failed")))
<<<<<<< HEAD
        setOverlay("ERROR", "Failed")
=======
        if (isSessionActive) continueSessionAfterPrompt()
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        return null
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
        val c = raw.trim().lowercase()
        return if (c.isEmpty()) PhaseResult.Failure("Empty input") else PhaseResult.Success(c)
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

<<<<<<< HEAD
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
=======
    private fun onPipelineEarlyReturn() {
        if (isSessionActive) {
            continueSessionAfterPrompt()
        } else {
            resumeWakeListening()
        }
    }

    private fun shouldKeepSessionActive(action: String): Boolean {
        return action !in setOf("LOCK_PHONE")
    }

    private fun playFollowUpPromptChime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
            tone.release()
        }
    }

    private fun startFollowUpListening() {
        if (!isSessionActive) return
        runCatching {
            appContext.startForegroundService(
                Intent(appContext, VoiceInputService::class.java).apply {
                    action = VoiceInputService.ACTION_START_LISTENING
                    putExtra(VoiceInputService.EXTRA_RESUME_WAKE_AFTER_FINISH, false)
                    putExtra(VoiceInputService.EXTRA_FOLLOW_UP_MODE, true)
                    putExtra(VoiceInputService.EXTRA_INITIAL_TIMEOUT_MS, 5_000L)
                    putExtra(VoiceInputService.EXTRA_SILENCE_COMPLETE_MS, 1_200L)
                }
            )
        }.onFailure {
            VoxynLogger.e("Failed to start follow-up listening: ${it.message}", tag = "ENGINE")
            isSessionActive = false
            resumeWakeListening()
        }
    }

    private fun continueSessionAfterPrompt() {
        isSessionActive = true
        startFollowUpListening()
    }

    private fun resumeWakeListening() {
        runCatching {
            appContext.startService(Intent(appContext, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_RESUME
            })
        }.onFailure {
            VoxynLogger.e("Failed to resume wake listening: ${it.message}", tag = "ENGINE")
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
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
            "WIFI_ON"              -> "WiFi is on."
            "WIFI_OFF"             -> "WiFi is off."
            "BLUETOOTH_ON"         -> "Bluetooth on."
            "BLUETOOTH_OFF"        -> "Bluetooth off."
            "VOLUME_UP"            -> "Volume up."
            "VOLUME_DOWN"          -> "Volume down."
            "SILENT_MODE"          -> "Silent mode on."
            "VIBRATE_MODE"         -> "Vibrate mode on."
            "NORMAL_MODE"          -> "Ringer on."
            "FLASHLIGHT_ON"        -> "Flashlight on."
            "FLASHLIGHT_OFF"       -> "Flashlight off."
            "SCREENSHOT"           -> "Screenshot taken."
            "BRIGHTNESS_UP"        -> "Brightness up."
            "BRIGHTNESS_DOWN"      -> "Brightness down."
            "BATTERY_SAVER_ON"     -> "Battery saver on."
            "PLAY_MUSIC"           -> "Playing music."
            "PAUSE_MUSIC"          -> "Music paused."
            "NEXT_TRACK"           -> "Next track."
            "PREV_TRACK"           -> "Previous track."
            "OPEN_APP"             -> "Opening ${params["app_name"] ?: "app"}."
            "CALL_CONTACT"         -> "Calling ${params["contact"] ?: "contact"}."
            "SET_ALARM"            -> "Alarm set."
            "SET_TIMER"            -> "Timer started."
            "NAVIGATE"             -> "Opening navigation to ${params["destination"] ?: "destination"}."
            "LOCK_PHONE"           -> "Locking phone."
            "MODE_ACTIVATE"        -> "${params["mode_name"]?.replaceFirstChar { it.uppercase() } ?: "Mode"} activated."
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

    companion object {
        private val HIGH_RISK = setOf("SEND_MESSAGE", "CALL_CONTACT", "DELETE_FILE", "FACTORY_RESET")
    }
}
