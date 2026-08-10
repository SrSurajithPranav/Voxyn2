package com.voxyn.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxyn.core.PhaseResult
import com.voxyn.utils.VoxynLogger

/**
 * Composes and launches message intents (SMS/WhatsApp).
 * Uses ACTION_SENDTO for SMS (no SEND_SMS permission needed; user taps send).
 * Uses wa.me deep link for WhatsApp when possible, falls back to ACTION_SEND.
 * All delivery is "compose" style — the user confirms by tapping Send in the target app.
 */
class MessageComposer(
    private val context: Context,
    private val policy: CommunicationPolicy
) {

    private val TAG = "MESSAGE_COMPOSER"

    /**
     * Sends an SMS via ACTION_SENDTO sms:<number> with body prefilled.
     * Opens the default SMS app with the message ready to send.
     */
    fun composeSms(phoneNumber: String, message: String): PhaseResult<String> {
        val digits = phoneNumber.replace(Regex("[^0-9+]"), "")
        val uri = Uri.parse("smsto:$digits")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            VoxynLogger.i("COMMUNICATION: recipient=$phoneNumber channel=SMS action=SEND_MESSAGE status=COMPOSE_OPENED", TAG)
            return PhaseResult.Success("Opened SMS to $phoneNumber")
        }
        VoxynLogger.w("No SMS app found", TAG)
        return PhaseResult.Failure("No SMS app installed", canFallback = false)
    }

    /**
     * Opens WhatsApp with the conversation prefilled.
     * Tries wa.me deep link first (targets exact contact), falls back to ACTION_SEND.
     */
    fun composeWhatsApp(phoneNumber: String, message: String): PhaseResult<String> {
        // Try wa.me deep link (targets specific contact by phone)
        val waUri = CommunicationPolicy.buildWhatsAppUrl(phoneNumber, message)
        if (waUri != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                VoxynLogger.i("COMMUNICATION: recipient=$phoneNumber channel=WhatsApp action=SEND_WHATSAPP_MESSAGE status=COMPOSE_OPENED", TAG)
                return PhaseResult.Success("Opened WhatsApp to $phoneNumber")
            }
        }

        // Fallback: ACTION_SEND with package restriction (opens WhatsApp share sheet)
        if (policy.isWhatsAppInstalled()) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                VoxynLogger.i("COMMUNICATION: recipient=$phoneNumber channel=WhatsApp action=SEND_WHATSAPP_MESSAGE status=COMPOSE_OPENED (fallback)", TAG)
                return PhaseResult.Success("Opened WhatsApp")
            }
        }

        VoxynLogger.w("WhatsApp not installed", TAG)
        return PhaseResult.Failure("WhatsApp is not installed.", canFallback = false)
    }

    /**
     * Compose based on resolved channel.
     */
    fun compose(channel: String, phoneNumber: String, message: String): PhaseResult<String> = when (channel) {
        "whatsapp" -> composeWhatsApp(phoneNumber, message)
        else -> composeSms(phoneNumber, message)
    }

    fun isWhatsAppInstalled(): Boolean = policy.isWhatsAppInstalled()

    /** Groups are not reliably resolvable via public Android APIs — documented seam. */
    fun composeGroup(groupName: String, message: String): PhaseResult<String> {
        VoxynLogger.w("Group messaging requested but not supported: $groupName — \"$message\"", TAG)
        return PhaseResult.Failure("I can't send to groups yet.", canFallback = false)
    }
}
