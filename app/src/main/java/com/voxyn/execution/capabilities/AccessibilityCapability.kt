package com.voxyn.execution.capabilities

import android.content.Context
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.perception.VoxynAccessibilityService
import com.voxyn.utils.VoxynLogger

class AccessibilityCapability(
    private val context: Context
) : Capability {

    override val id: String = "accessibility"

    override val supportedActions: Set<String> = setOf(
        "SEND_CURRENT_MESSAGE",
        "LIKE_CURRENT",
        "SCROLL_DOWN",
        "SCROLL_UP",
        "GO_BACK",
        "GO_HOME",
        "READ_NOTIFICATIONS",
        "REMEMBER_THIS",
        "RECENTS",
        "QUICK_SETTINGS",
        "SPLIT_SCREEN",
        "CLICK_ELEMENT",
        "INPUT_TEXT",
        "READ_SCREEN"
    )

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = false

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("AccessibilityCapability executing: ${step.actionType}", "CAPABILITY")

        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service is not enabled. Please enable it in Settings.")

        return try {
            when (step.actionType) {
                "SEND_CURRENT_MESSAGE" -> {
                    val clicked = service.performSendCurrentMessage()
                    if (clicked) PhaseResult.Success("Message sent")
                    else PhaseResult.Failure("Could not find send button on screen")
                }
                "LIKE_CURRENT" -> {
                    val clicked = service.performLikeCurrent()
                    if (clicked) PhaseResult.Success("Liked")
                    else PhaseResult.Failure("Could not find like button on screen")
                }
                "SCROLL_DOWN" -> {
                    val scrolled = service.performScrollDown()
                    if (scrolled) PhaseResult.Success("Scrolled down")
                    else PhaseResult.Failure("Could not scroll down")
                }
                "SCROLL_UP" -> {
                    val scrolled = service.performScrollUp()
                    if (scrolled) PhaseResult.Success("Scrolled up")
                    else PhaseResult.Failure("Could not scroll up")
                }
                "GO_BACK" -> {
                    val success = service.performGoBack()
                    if (success) PhaseResult.Success("Went back")
                    else PhaseResult.Failure("Go back action failed")
                }
                "GO_HOME" -> {
                    val success = service.performGoHome()
                    if (success) PhaseResult.Success("Went home")
                    else PhaseResult.Failure("Go home action failed")
                }
                "READ_NOTIFICATIONS" -> {
                    val success = service.performReadNotifications()
                    if (success) PhaseResult.Success("Opened notifications")
                    else PhaseResult.Failure("Could not open notifications")
                }
                "RECENTS" -> {
                    val success = service.performOpenRecents()
                    if (success) PhaseResult.Success("Opened recent apps")
                    else PhaseResult.Failure("Could not open recent apps")
                }
                "QUICK_SETTINGS" -> {
                    val success = service.performQuickSettings()
                    if (success) PhaseResult.Success("Opened quick settings")
                    else PhaseResult.Failure("Could not open quick settings")
                }
                "SPLIT_SCREEN" -> {
                    val success = service.performSplitScreen()
                    if (success) PhaseResult.Success("Split screen toggled")
                    else PhaseResult.Failure("Could not toggle split screen")
                }
                "CLICK_ELEMENT" -> {
                    val target = step.params["target"] ?: step.params["query"] ?: ""
                    if (target.isBlank()) {
                        PhaseResult.Failure("No element target specified")
                    } else {
                        val clicked = service.performClickByText(target)
                        if (clicked) PhaseResult.Success("Clicked $target")
                        else PhaseResult.Failure("Could not find '$target' on screen")
                    }
                }
                "INPUT_TEXT" -> {
                    val text = step.params["text"] ?: step.params["query"] ?: ""
                    if (text.isBlank()) {
                        PhaseResult.Failure("No text specified to type")
                    } else {
                        val typed = service.performInputText(text)
                        if (typed) PhaseResult.Success("Typed: $text")
                        else PhaseResult.Failure("No active input field found on screen")
                    }
                }
                "READ_SCREEN" -> {
                    val text = service.currentScreenText()
                    if (!text.isNullOrBlank()) {
                        PhaseResult.Success("On screen: $text")
                    } else {
                        PhaseResult.Failure("No readable text found on screen")
                    }
                }
                "REMEMBER_THIS" -> {
                    val text = service.performRememberThis()
                    if (text.isNotBlank()) PhaseResult.Success("Captured screen text: $text")
                    else PhaseResult.Failure("No readable text found on screen")
                }
                else -> PhaseResult.Failure("Unsupported accessibility action: ${step.actionType}", canFallback = false)
            }
        } catch (e: Exception) {
            VoxynLogger.e("Accessibility capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(e.message ?: "Accessibility action failed", canFallback = true)
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return if (executionResult.isNotBlank() && !executionResult.startsWith("Could not", ignoreCase = true) && !executionResult.startsWith("No", ignoreCase = true)) {
            VerificationOutcome.Success
        } else {
            VerificationOutcome.PartialSuccess
        }
    }
}
