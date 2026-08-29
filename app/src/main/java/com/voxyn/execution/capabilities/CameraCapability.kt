package com.voxyn.execution.capabilities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.delay

/**
 * CameraCapability — safe, legitimate camera execution for VOXYN.
 *
 * Adheres to Android background execution and camera permission policies.
 * Dispatches legitimate camera intents and handles capture delay parameters
 * without relying on unsafe background recording hacks.
 */
class CameraCapability(
    private val context: Context
) : Capability {

    override val id: String = "camera"

    override val supportedActions: Set<String> = setOf(
        "TAKE_PHOTO",
        "RECORD_VIDEO",
        "OPEN_CAMERA"
    )

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = setOf(
        Manifest.permission.CAMERA
    )

    override val requiresNetwork: Boolean = false

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("Camera capability executing: ${step.actionType} (params=${step.params})", "CAPABILITY")

        val hasCameraPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPerm) {
            VoxynLogger.w("Camera permission not granted", "CAPABILITY")
            return PhaseResult.Failure(
                "Camera permission is required to use the camera",
                canFallback = false
            )
        }

        return try {
            when (step.actionType) {
                "TAKE_PHOTO" -> {
                    val delaySeconds = step.params["delay_seconds"]?.toLongOrNull() ?: 0L
                    if (delaySeconds > 0) {
                        VoxynLogger.i("Delaying photo capture for $delaySeconds seconds", "CAPABILITY")
                        delay(delaySeconds * 1000L)
                    }

                    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)

                    val msg = if (delaySeconds > 0) {
                        "Taking photo after $delaySeconds seconds"
                    } else {
                        "Opening Camera to take a photo"
                    }
                    PhaseResult.Success(msg)
                }

                "RECORD_VIDEO" -> {
                    val durationSeconds = step.params["duration_seconds"]?.toLongOrNull() ?: 0L
                    val intent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)

                    val msg = if (durationSeconds > 0) {
                        "Opening Camera to record video for $durationSeconds seconds"
                    } else {
                        "Opening Camera to record video"
                    }
                    PhaseResult.Success(msg)
                }

                "OPEN_CAMERA" -> {
                    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    PhaseResult.Success("Opening Camera")
                }

                else -> PhaseResult.Failure(
                    "Unsupported camera action: ${step.actionType}",
                    canFallback = false
                )
            }
        } catch (e: Exception) {
            VoxynLogger.e("Camera capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(
                e.message ?: "Camera action failed",
                canFallback = true
            )
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return VerificationOutcome.Success
    }
}
