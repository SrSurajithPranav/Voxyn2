package com.voxyn.execution.capabilities

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.utils.VoxynLogger

class MediaCapability(
    private val context: Context
) : Capability {

    override val id: String = "media"

    override val supportedActions: Set<String> = setOf(
        "PLAY_MUSIC",
        "PAUSE_MUSIC",
        "NEXT_TRACK",
        "PREV_TRACK"
    )

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = false

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("MediaCapability executing: ${step.actionType}", "CAPABILITY")

        return try {
            when (step.actionType) {
                "PLAY_MUSIC" -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    PhaseResult.Success("Playing music")
                }
                "PAUSE_MUSIC" -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    PhaseResult.Success("Paused music")
                }
                "NEXT_TRACK" -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    PhaseResult.Success("Next track")
                }
                "PREV_TRACK" -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    PhaseResult.Success("Previous track")
                }
                else -> PhaseResult.Failure("Unsupported media action: ${step.actionType}", canFallback = false)
            }
        } catch (e: Exception) {
            VoxynLogger.e("Media capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(e.message ?: "Media action failed", canFallback = true)
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return runCatching {
            when (step.actionType) {
                "PLAY_MUSIC" -> {
                    if (audioManager.isMusicActive) VerificationOutcome.Success
                    else VerificationOutcome.PartialSuccess
                }
                "PAUSE_MUSIC" -> {
                    if (!audioManager.isMusicActive) VerificationOutcome.Success
                    else VerificationOutcome.PartialSuccess
                }
                "NEXT_TRACK", "PREV_TRACK" -> VerificationOutcome.Success
                else -> VerificationOutcome.Unverified
            }
        }.getOrDefault(VerificationOutcome.Unverified)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
