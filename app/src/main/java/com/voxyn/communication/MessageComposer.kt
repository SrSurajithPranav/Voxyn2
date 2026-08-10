package com.voxyn.communication

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.voxyn.core.PhaseResult
import com.voxyn.utils.VoxynLogger

/**
 * Delivers messages. SMS is auto-sent via SmsManager (SEND_SMS permission,
 * after the user confirms the action). WhatsApp has no public API for
 * auto-sending to personal accounts, so it opens the chat prefilled
 * (wa.me deep link when a country code is known, ACTION_SEND otherwise).
 */
class MessageComposer(
    private val context: Context,
    private val policy: CommunicationPolicy
) {

    private val TAG = "MESSAGE_COMPOSER"
    private var smsRequestCode = 1

    /**
     * Auto-sends an SMS. Requires SEND_SMS permission — callers guarantee the
     * confirmation flow already ran; this method never sends silently on its own.
     */
    fun sendSms(phoneNumber: String, message: String): PhaseResult<String> {
        val digits = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            VoxynLogger.w("SEND_SMS permission missing — cannot auto-send", TAG)
            return PhaseResult.Failure("I need SMS permission to auto-send. Grant it in app settings.", canFallback = false)
        }

        val sentAction = "com.voxyn.action.SMS_SENT_$smsRequestCode"
        val sentPi = PendingIntent.getBroadcast(
            context,
            smsRequestCode,
            Intent(sentAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                when (resultCode) {
                    Activity.RESULT_OK ->
                        VoxynLogger.i("COMMUNICATION: recipient=$digits channel=SMS action=SEND_MESSAGE status=SENT", TAG)
                    else ->
                        VoxynLogger.w("SMS send failed resultCode=$resultCode", TAG)
                }
                runCatching { receivedContext?.unregisterReceiver(this) }
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(sentAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.isSuccess

        return try {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(digits, null, message, sentPi, null)
            smsRequestCode++
            VoxynLogger.i("COMMUNICATION: recipient=$digits channel=SMS action=SEND_MESSAGE status=SENDING", TAG)
            PhaseResult.Success("Message sent to $digits")
        } catch (e: Exception) {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            VoxynLogger.e("SMS send threw", e, TAG)
            PhaseResult.Failure("SMS failed to send.", canFallback = true)
        }
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

    fun isWhatsAppInstalled(): Boolean = policy.isWhatsAppInstalled()

    /** Groups are not reliably resolvable via public Android APIs — documented seam. */
    fun composeGroup(groupName: String, message: String): PhaseResult<String> {
        VoxynLogger.w("Group messaging requested but not supported: $groupName — \"$message\"", TAG)
        return PhaseResult.Failure("I can't send to groups yet.", canFallback = false)
    }
}
