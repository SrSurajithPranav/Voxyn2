package com.voxyn.communication

import android.content.Context
import android.content.pm.PackageManager
import com.voxyn.utils.VoxynLogger
import java.net.URLEncoder

/**
 * Pure, testable policy for communication delivery.
 * - No I/O, no Android framework except Context for package checks.
 * - Default channel = SMS; WhatsApp only when explicitly requested.
 * - Delivery is always "compose" style — the user taps Send in the target app.
 */
class CommunicationPolicy(private val context: Context) {

    private val TAG = "COMM_POLICY"

    /** Supported channels for automatic delivery (compose intents). */
    val supportedChannels = setOf("sms", "whatsapp")

    /** Returns true if WhatsApp is installed. */
    fun isWhatsAppInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun resolveChannel(channelParam: String?): String =
        resolveChannel(channelParam, isWhatsAppInstalled())

    companion object {

        /**
         * Determines the target channel from parsed intent params.
         * - Explicit `channel` param in {sms, whatsapp} wins (WhatsApp downgrades to SMS when missing).
         * - Otherwise SMS.
         */
        fun resolveChannel(channelParam: String?, whatsappInstalled: Boolean): String {
            val explicit = channelParam?.lowercase()?.takeIf { it in setOf("sms", "whatsapp") }
            if (explicit != null) {
                if (explicit == "whatsapp" && !whatsappInstalled) return "sms"
                return explicit
            }
            return "sms"
        }

        /**
         * Builds a wa.me deep link with prefilled message and recipient phone.
         * Returns null when the number can't be normalized to international
         * format (missing country code) — callers then fall back to ACTION_SEND.
         */
        fun buildWhatsAppUrl(phoneNumber: String, message: String): String? {
            val digits = phoneNumber.replace(Regex("[^0-9]"), "")
            if (digits.length < 11) {
                VoxynLogger.w("wa.me needs country code; phone=$phoneNumber", "COMM_POLICY")
                return null
            }
            val encodedMsg = URLEncoder.encode(message, "UTF-8")
            return "https://wa.me/$digits?text=$encodedMsg"
        }
    }
}
