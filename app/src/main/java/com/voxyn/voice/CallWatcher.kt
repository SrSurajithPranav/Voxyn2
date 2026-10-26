package com.voxyn.voice

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.voxyn.utils.VoxynLogger
import java.util.concurrent.Executor

/**
 * CallWatcher — monitors telephony and audio-mode state to pause VOXYN's
 * microphone whenever a phone call (cellular or VoIP) is active, and resume
 * it automatically when the call ends.
 *
 * Works on API 21+ using the legacy PhoneStateListener path and on API 31+
 * using the newer TelephonyCallback path.
 *
 * Inspiration: Friday Mobile CallWatcher.kt — unhooks mic on ringing / offhook,
 * re-arms on IDLE.
 *
 * Usage:
 *   val watcher = CallWatcher(context) { shouldPause -> ... }
 *   watcher.register()
 *   // later:
 *   watcher.unregister()
 */
class CallWatcher(
    private val context: Context,
    /** Called with `true` when a call starts (mic should pause), `false` when it ends. */
    private val onCallStateChanged: (pauseMic: Boolean) -> Unit
) {

    private val telephonyManager: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // ─── Legacy listener (API < 31) ───────────────────────────────────────────

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in API 31")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleTelephonyState(state)
        }
    }

    // ─── Modern callback (API 31+) ────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.S)
    private val modernCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            handleTelephonyState(state)
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun register() {
        if (telephonyManager == null) {
            VoxynLogger.w("TelephonyManager unavailable — call interruption disabled", "CALLWATCHER")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(
                MainExecutor(context),
                modernCallback
            )
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        VoxynLogger.i("CallWatcher registered (API ${Build.VERSION.SDK_INT})", "CALLWATCHER")
    }

    fun unregister() {
        if (telephonyManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.unregisterTelephonyCallback(modernCallback)
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
        }
        VoxynLogger.i("CallWatcher unregistered", "CALLWATCHER")
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun handleTelephonyState(state: Int) {
        val callActive = when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> true
            else -> false // CALL_STATE_IDLE
        }

        // Also catch VoIP calls that don't go through TelephonyManager by
        // checking AudioManager.mode — MODE_IN_CALL or MODE_IN_COMMUNICATION
        // indicates an active voice session from any app.
        val voipActive = audioManager?.mode.let { mode ->
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        }

        val shouldPause = callActive || voipActive
        val stateLabel = when (state) {
            TelephonyManager.CALL_STATE_RINGING  -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK  -> "OFFHOOK"
            TelephonyManager.CALL_STATE_IDLE     -> "IDLE"
            else                                  -> "UNKNOWN($state)"
        }
        VoxynLogger.i(
            "CallWatcher: state=$stateLabel voipActive=$voipActive → pauseMic=$shouldPause",
            "CALLWATCHER"
        )
        onCallStateChanged(shouldPause)
    }

    // Simple Executor that posts to the main looper — avoids requiring a Handler dependency.
    private class MainExecutor(context: Context) : Executor {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        override fun execute(command: Runnable) { handler.post(command) }
    }
}
