package com.voxyn.engine

import android.content.Context
import android.content.Intent
import com.voxyn.ai.ClaudeIntentResolver
import com.voxyn.ai.ClaudeResult
import com.voxyn.core.*
import com.voxyn.execution.ExecutionController
import com.voxyn.intent.IntentRouter
import com.voxyn.memory.MemoryRepository
import com.voxyn.overlay.OverlayService
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.TextToSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class VoxynEngine(
    private val scope          : CoroutineScope,
    private val context        : Context,
    private val memory         : MemoryRepository,
    private val intentRouter   : IntentRouter,
    private val executor       : ExecutionController,
    private val tts            : TextToSpeechManager,
    private val claudeResolver : ClaudeIntentResolver? = null,
    private val phaseTimeoutMs : Long = 10_000L
) {
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events

    private var activeJob    : Job? = null
    private var pendingInput : String? = null

    fun process(input: String) {
        activeJob?.cancel()
        activeJob = scope.launch { runPipeline(input) }
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
        VoxynLogger.i("▶ \"$raw\"", "ENGINE")
        setOverlay("LISTENING")

        val perceived = phase(PipelinePhase.PERCEPTION) { perceive(raw) } ?: return
        setOverlay("THINKING")

        var intent = phase(PipelinePhase.INTENT) { resolveIntent(perceived) } ?: return
        var claudeSpeak: String? = null

        // Claude fallback
        if (intent.action == "UNKNOWN" && intent.confidence < 0.3f && claudeResolver != null) {
            VoxynLogger.i("Asking Claude...", "ENGINE")
            tts.speak("Let me think...")
            val result: ClaudeResult? = try {
                withTimeout(12_000L) { claudeResolver.resolve(perceived) }
            } catch (e: Exception) {
                VoxynLogger.e("Claude error: ${e.message}", tag = "ENGINE"); null
            }
            if (result != null) {
                intent = result.intent
                claudeSpeak = result.speakOverride
            }
        }

        if (intent.action == "UNKNOWN") {
            tts.speak("I didn't understand that. Try: turn on WiFi, open YouTube, or set a timer.")
            emit(PipelineEvent.ClarificationRequired(listOf("Unknown command")))
            setOverlay("ERROR")
            return
        }

        val confidence = phase(PipelinePhase.CONFIDENCE) { scoreConfidence(intent) } ?: return
        val risk       = phase(PipelinePhase.RISK)       { assessRisk(intent) }       ?: return
        val decision   = phase(PipelinePhase.DECISION)   { decide(intent, confidence, risk) } ?: return

        emit(PipelineEvent.DecisionMade(decision))

        when (decision) {
            is DecisionState.AwaitingConfirmation -> {
                pendingInput = raw
                tts.speak("Should I ${friendlyAction(intent.action)}? Say yes to confirm.")
                emit(PipelineEvent.ConfirmationRequired(decision.intent))
                setOverlay("IDLE")
                return
            }
            is DecisionState.AwaitingClarification -> {
                pendingInput = raw
                tts.speak("I need more info: ${decision.missing.firstOrNull() ?: "please clarify"}.")
                emit(PipelineEvent.ClarificationRequired(decision.missing))
                setOverlay("IDLE")
                return
            }
            is DecisionState.Rejected -> {
                tts.speak("That action was rejected.")
                setOverlay("ERROR")
                return
            }
            is DecisionState.Execute -> Unit
        }

        setOverlay("EXECUTING")
        val plan     = phase(PipelinePhase.PLAN)   { buildPlan(intent) } ?: return
        val executed = executeWithRecovery(plan)    ?: return
        val outcome  = phase(PipelinePhase.VERIFY) { verify(executed) }  ?: return

        emit(PipelineEvent.VerificationResult(outcome))

        val isSuccess = outcome is VerificationOutcome.Success
        setOverlay(if (isSuccess) "SUCCESS" else "ERROR")

        val speech = claudeSpeak ?: friendlySuccess(intent.action, intent.params, outcome)
        tts.speak(speech)

        phase(PipelinePhase.LEARN) { learn(intent, outcome) }
        pendingInput = null
        emit(PipelineEvent.PipelineComplete)
        VoxynLogger.i("✓ Done", "ENGINE")
    }

    // ─── Recovery ─────────────────────────────────────────────────────

    private suspend fun executeWithRecovery(plan: ExecutionPlan): ExecutionPlan? {
        phase(PipelinePhase.EXECUTE) { executePlan(plan) }?.let { return it }
        VoxynLogger.w("Attempt 1 failed — retrying", "ENGINE")
        phase(PipelinePhase.EXECUTE) { executePlan(plan) }?.let { return it }
        VoxynLogger.w("Both attempts failed", "ENGINE")
        tts.speak("I couldn't complete that. Please try manually.")
        emit(PipelineEvent.ClarificationRequired(listOf("Execution failed")))
        setOverlay("ERROR")
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

    private fun setOverlay(state: String) {
        try {
            context.startService(
                Intent(context, OverlayService::class.java).apply {
                    putExtra("state", state)
                }
            )
        } catch (_: Exception) {}
    }

    // ─── Friendly messages ────────────────────────────────────────────

    private fun friendlyAction(action: String) = when (action) {
        "WIFI_ON"       -> "turn on WiFi"
        "WIFI_OFF"      -> "turn off WiFi"
        "BLUETOOTH_ON"  -> "enable Bluetooth"
        "FLASHLIGHT_ON" -> "turn on the flashlight"
        "SILENT_MODE"   -> "enable silent mode"
        "SEND_MESSAGE"  -> "send that message"
        "CALL_CONTACT"  -> "make that call"
        else            -> action.replace("_", " ").lowercase()
    }

    private fun friendlySuccess(action: String, params: Map<String, String>, outcome: VerificationOutcome): String {
        if (outcome !is VerificationOutcome.Success) return "Done, but something may not have worked."
        return when (action) {
            "WIFI_ON"          -> "WiFi is on."
            "WIFI_OFF"         -> "WiFi is off."
            "BLUETOOTH_ON"     -> "Bluetooth on."
            "BLUETOOTH_OFF"    -> "Bluetooth off."
            "VOLUME_UP"        -> "Volume up."
            "VOLUME_DOWN"      -> "Volume down."
            "SILENT_MODE"      -> "Silent mode on."
            "VIBRATE_MODE"     -> "Vibrate mode on."
            "NORMAL_MODE"      -> "Ringer on."
            "FLASHLIGHT_ON"    -> "Flashlight on."
            "FLASHLIGHT_OFF"   -> "Flashlight off."
            "SCREENSHOT"       -> "Screenshot taken."
            "BRIGHTNESS_UP"    -> "Brightness up."
            "BRIGHTNESS_DOWN"  -> "Brightness down."
            "BATTERY_SAVER_ON" -> "Battery saver on."
            "PLAY_MUSIC"       -> "Playing music."
            "PAUSE_MUSIC"      -> "Music paused."
            "NEXT_TRACK"       -> "Next track."
            "PREV_TRACK"       -> "Previous track."
            "OPEN_APP"         -> "Opening ${params["app_name"] ?: "app"}."
            "CALL_CONTACT"     -> "Calling ${params["contact"] ?: "contact"}."
            "SET_ALARM"        -> "Alarm set."
            "SET_TIMER"        -> "Timer started."
            "NAVIGATE"         -> "Opening navigation to ${params["destination"] ?: "destination"}."
            "LOCK_PHONE"       -> "Locking phone."
            "MODE_ACTIVATE"    -> "${params["mode_name"]?.replaceFirstChar { it.uppercase() } ?: "Mode"} activated."
            else               -> "Done."
        }
    }

    companion object {
        private val HIGH_RISK = setOf("SEND_MESSAGE", "CALL_CONTACT", "DELETE_FILE", "FACTORY_RESET")
    }
}
