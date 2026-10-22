package com.voxyn.execution.capabilities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.voxyn.communication.ContactResolution
import com.voxyn.communication.ContactResolver
import com.voxyn.communication.MessageComposer
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.utils.VoxynLogger

class CommunicationCapability(
    private val context: Context,
    private val contactResolver: ContactResolver,
    private val composer: MessageComposer
) : Capability {

    override val id: String = "communication"

    override val supportedActions: Set<String> = setOf(
        "CALL_CONTACT",
        "SEND_MESSAGE",
        "SEND_WHATSAPP_MESSAGE",
        "MESSAGE_GROUP",
        "CONTACT_LOOKUP"
    )

    override val riskLevel: CapabilityRisk = CapabilityRisk.HIGH

    override val requiredPermissions: Set<String> = setOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS
    )

    override val requiresNetwork: Boolean = false

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("CommunicationCapability executing: ${step.actionType}", "CAPABILITY")

        return try {
            when (step.actionType) {
                "CALL_CONTACT" -> callContact(step.params)
                "SEND_MESSAGE" -> sendMessage(step.params)
                "SEND_WHATSAPP_MESSAGE" -> sendWhatsApp(step.params)
                "MESSAGE_GROUP" -> {
                    val group = step.params["group"] ?: ""
                    val message = step.params["message"] ?: ""
                    composer.composeGroup(group, message)
                }
                "CONTACT_LOOKUP" -> contactLookup(step.params)
                else -> PhaseResult.Failure("Unsupported communication action: ${step.actionType}", canFallback = false)
            }
        } catch (e: Exception) {
            VoxynLogger.e("Communication capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(e.message ?: "Communication action failed", canFallback = true)
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return when {
            step.actionType == "CONTACT_LOOKUP" && executionResult.contains("'s number is") -> VerificationOutcome.Success
            // Activity launch or SMS dispatch acceptance does not prove delivery or call connection.
            else -> VerificationOutcome.Unverified
        }
    }

    private suspend fun callContact(params: Map<String, String>): PhaseResult<String> {
        val name = params["contact"] ?: params["recipient"] ?: ""
        if (name.isBlank()) return PhaseResult.Failure("Contact name is missing")

        val phone = params["phone"] ?: when (val res = contactResolver.resolve(name)) {
            is ContactResolution.Unique -> res.phoneNumber
            else -> name.filter { it.isDigit() || it == '+' }
        }

        if (phone.isBlank()) {
            return PhaseResult.Failure("I couldn't find a phone number for $name")
        }

        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$phone")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("Calling $name")
    }

    private suspend fun sendMessage(params: Map<String, String>): PhaseResult<String> {
        val name = params["recipient"] ?: params["contact"] ?: ""
        val message = params["message"] ?: ""
        if (name.isBlank() && params["phone"].isNullOrBlank()) {
            return PhaseResult.Failure("Recipient is missing")
        }

        val phone = params["phone"] ?: when (val res = contactResolver.resolve(name)) {
            is ContactResolution.Unique -> res.phoneNumber
            else -> name.filter { it.isDigit() || it == '+' }
        }

        if (phone.isBlank()) {
            return PhaseResult.Failure("I couldn't find a contact number for $name")
        }

        return composer.composeSms(phone, message)
    }

    private suspend fun sendWhatsApp(params: Map<String, String>): PhaseResult<String> {
        val name = params["recipient"] ?: params["contact"] ?: ""
        val message = params["message"] ?: ""

        val phone = params["phone"] ?: when (val res = contactResolver.resolve(name)) {
            is ContactResolution.Unique -> res.phoneNumber
            else -> name.filter { it.isDigit() || it == '+' }
        }

        return composer.composeWhatsApp(phone, message)
    }

    private suspend fun contactLookup(params: Map<String, String>): PhaseResult<String> {
        val name = params["contact"] ?: ""
        return when (val res = contactResolver.resolve(name)) {
            is ContactResolution.Unique -> PhaseResult.Success("${res.displayName}'s number is ${res.phoneNumber}")
            is ContactResolution.Ambiguous -> PhaseResult.Failure("Found ${res.candidates.size} contacts named $name")
            ContactResolution.NotFound -> PhaseResult.Failure("Contact not found: $name")
        }
    }
}
