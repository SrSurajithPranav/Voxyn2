package com.voxyn.execution

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.voxyn.communication.ContactResolution
import com.voxyn.communication.ContactResolver
import com.voxyn.communication.MessageComposer
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.mode.ModeEngine
import com.voxyn.perception.VoxynAccessibilityService
import com.voxyn.utils.VoxynLogger

class ExecutionController(
    private val context: Context,
    private val modeEngine: ModeEngine,
    private val appLaunchResolver: AppLaunchResolver,
    private val contactResolver: ContactResolver,
    private val composer: MessageComposer,
    private val capabilityRegistry: CapabilityRegistry? = null
) {

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("Executing: ${step.actionType}", "EXEC")

        val cap = capabilityRegistry?.find(step.actionType)
        if (cap != null) {
            return cap.execute(step)
        }

        return try {
            when (step.actionType) {
                // ─── Accessibility Features ───────────────────────────
                "SEND_CURRENT_MESSAGE" -> sendCurrentMessage()
                "LIKE_CURRENT"         -> likeCurrent()
                "SCROLL_DOWN"          -> scrollDown()
                "SCROLL_UP"            -> scrollUp()
                "GO_BACK"              -> goBack()
                "GO_HOME"              -> goHome()
                "READ_NOTIFICATIONS"   -> readNotifications()
                "REMEMBER_THIS"        -> rememberThis()

                // ─── Core System Actions ──────────────────────────────
                "WIFI_ON"          -> wifiOn()
                "WIFI_OFF"         -> wifiOff()
                "BLUETOOTH_ON"     -> bluetoothOn()
                "BLUETOOTH_OFF"    -> bluetoothOff()
                "VOLUME_UP"        -> volumeUp()
                "VOLUME_DOWN"      -> volumeDown()
                "SILENT_MODE"      -> silentMode()
                "VIBRATE_MODE"     -> vibrateMode()
                "NORMAL_MODE"      -> normalMode()
                "FLASHLIGHT_ON"    -> flashlightOn()
                "FLASHLIGHT_OFF"   -> flashlightOff()
                "BRIGHTNESS_UP"    -> brightnessUp()
                "BRIGHTNESS_DOWN"  -> brightnessDown()
                "AIRPLANE_ON"      -> airplaneSettings()
                "AIRPLANE_OFF"     -> airplaneSettings()
                "BATTERY_SAVER_ON" -> batterySaverSettings()
                "BATTERY_STATUS"   -> batteryStatus()
                "SCREENSHOT"       -> screenshot()
                "PLAY_MUSIC"       -> playMusic()
                "PAUSE_MUSIC"      -> pauseMusic()
                "NEXT_TRACK"       -> nextTrack()
                "PREV_TRACK"       -> prevTrack()
                "OPEN_APP"         -> appLaunchResolver.launch(step.params["app_name"] ?: "")
                "SET_ALARM"        -> setAlarm(step.params)
                "SET_TIMER"        -> setTimer(step.params)
                "NAVIGATE"         -> navigate(step.params["destination"] ?: step.params["destinations"] ?: "")
                "CALL_CONTACT"     -> callContact(step.params)
                "SEND_MESSAGE"     -> sendMessage(step.params)
                "SEND_WHATSAPP_MESSAGE" -> sendWhatsApp(step.params)
                "MESSAGE_GROUP"    -> composer.composeGroup(step.params["group"] ?: "", step.params["message"] ?: "")
                "CONTACT_LOOKUP"   -> contactLookup(step.params)
                "LOCK_PHONE"       -> lockPhone()
                "MODE_ACTIVATE"    -> activateMode(step.params["mode_name"] ?: "normal")
                "TAKE_PHOTO", "RECORD_VIDEO", "OPEN_CAMERA" -> com.voxyn.execution.capabilities.CameraCapability(context).execute(step)
                else               -> PhaseResult.Failure("Unknown action: ${step.actionType}", canFallback = true)
            }
        } catch (e: Exception) {
            VoxynLogger.e("Execution error: ${e.message}", e, "EXEC")
            PhaseResult.Failure(e.message ?: "Execution failed", canFallback = true)
        }
    }

    // ─── Accessibility Actions ────────────────────────────────────────

    private fun sendCurrentMessage(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled. Please enable VOXYN Assistant in Settings.", canFallback = false)
        val success = service.performSendCurrentMessage()
        return if (success) PhaseResult.Success("Message sent") else PhaseResult.Failure("Send button not found on screen", canFallback = false)
    }

    private fun likeCurrent(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled.", canFallback = false)
        val success = service.performLikeCurrent()
        return if (success) PhaseResult.Success("Liked") else PhaseResult.Failure("Like button not found on screen", canFallback = false)
    }

    private fun scrollDown(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled.", canFallback = false)
        val success = service.performScrollDown()
        return if (success) PhaseResult.Success("Scrolled down") else PhaseResult.Failure("Could not scroll", canFallback = false)
    }

    private fun scrollUp(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled.", canFallback = false)
        val success = service.performScrollUp()
        return if (success) PhaseResult.Success("Scrolled up") else PhaseResult.Failure("Could not scroll", canFallback = false)
    }

    private fun goBack(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled.", canFallback = false)
        val success = service.performGoBack()
        return if (success) PhaseResult.Success("Went back") else PhaseResult.Failure("Back action failed", canFallback = false)
    }

    private fun goHome(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled.", canFallback = false)
        val success = service.performGoHome()
        return if (success) PhaseResult.Success("Went to Home Screen") else PhaseResult.Failure("Home action failed", canFallback = false)
    }

    private fun readNotifications(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled.", canFallback = false)
        val success = service.performReadNotifications()
        return if (success) PhaseResult.Success("Opening notifications shade") else PhaseResult.Failure("Notifications action failed", canFallback = false)
    }

    private fun rememberThis(): PhaseResult<String> {
        val service = VoxynAccessibilityService.instance
            ?: return PhaseResult.Failure("Accessibility Service disabled.", canFallback = false)
        val text = service.performRememberThis()
        return PhaseResult.Success("Saved to memory: \"${text.take(40)}\"")
    }

    private fun activateMode(modeName: String): PhaseResult<String> {
        val result = modeEngine.activateMode(modeName)
        return PhaseResult.Success(result)
    }

    // ─── Core Actions ─────────────────────────────────────────────────

    private fun wifiOn(): PhaseResult<String> {
        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("WiFi settings opened")
    }

    private fun wifiOff(): PhaseResult<String> {
        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("WiFi settings opened")
    }

    private fun bluetoothOn(): PhaseResult<String> {
        return try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled) {
                @Suppress("DEPRECATION")
                bluetoothAdapter.enable()
            }
            PhaseResult.Success("Bluetooth enabled")
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Bluetooth settings opened")
        }
    }

    private fun bluetoothOff(): PhaseResult<String> {
        return try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                @Suppress("DEPRECATION")
                bluetoothAdapter.disable()
            }
            PhaseResult.Success("Bluetooth disabled")
        } catch (e: Exception) {
            PhaseResult.Failure("Bluetooth control failed", canFallback = true)
        }
    }

    private fun volumeUp(): PhaseResult<String> {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
        return PhaseResult.Success("Volume increased")
    }

    private fun volumeDown(): PhaseResult<String> {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        return PhaseResult.Success("Volume decreased")
    }

    private fun silentMode(): PhaseResult<String> {
        return try {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                PhaseResult.Success("Silent mode on")
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                PhaseResult.Success("Please grant DND permission")
            }
        } catch (e: Exception) {
            PhaseResult.Failure("Silent mode failed", canFallback = true)
        }
    }

    private fun vibrateMode(): PhaseResult<String> {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        return PhaseResult.Success("Vibrate mode on")
    }

    private fun normalMode(): PhaseResult<String> {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        return PhaseResult.Success("Ringer on")
    }

    private fun flashlightOn(): PhaseResult<String> {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], true)
            PhaseResult.Success("Flashlight on")
        } catch (e: Exception) {
            PhaseResult.Failure("Flashlight failed: ${e.message}", canFallback = false)
        }
    }

    private fun flashlightOff(): PhaseResult<String> {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], false)
            PhaseResult.Success("Flashlight off")
        } catch (e: Exception) {
            PhaseResult.Failure("Flashlight failed: ${e.message}", canFallback = false)
        }
    }

    private fun brightnessUp(): PhaseResult<String> {
        return try {
            if (Settings.System.canWrite(context)) {
                val current = Settings.System.getInt(
                    context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
                )
                val newVal = (current + 51).coerceAtMost(255)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newVal)
                PhaseResult.Success("Brightness increased")
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                PhaseResult.Success("Please grant brightness permission")
            }
        } catch (e: Exception) {
            PhaseResult.Failure("Brightness failed", canFallback = true)
        }
    }

    private fun brightnessDown(): PhaseResult<String> {
        return try {
            if (Settings.System.canWrite(context)) {
                val current = Settings.System.getInt(
                    context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
                )
                val newVal = (current - 51).coerceAtLeast(10)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newVal)
                PhaseResult.Success("Brightness decreased")
            } else {
                PhaseResult.Failure("Write settings permission needed", canFallback = true)
            }
        } catch (e: Exception) {
            PhaseResult.Failure("Brightness failed", canFallback = true)
        }
    }

    private fun airplaneSettings(): PhaseResult<String> {
        val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("Airplane mode settings opened")
    }

    private fun batterySaverSettings(): PhaseResult<String> {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("Battery saver settings opened")
    }

    private fun batteryStatus(): PhaseResult<String> {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("Battery status opened")
    }

    private fun screenshot(): PhaseResult<String> {
        return PhaseResult.Success("Use your phone's screenshot shortcut (Power + Volume Down)")
    }

    private fun lockPhone(): PhaseResult<String> {
        return PhaseResult.Success("Lock phone — enable Device Admin in settings")
    }

    private fun sendMediaKey(keyCode: Int): PhaseResult<String> {
        val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        audioManager.dispatchMediaKeyEvent(event)
        return PhaseResult.Success("Media key sent")
    }

    private fun playMusic() = sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
    private fun pauseMusic() = sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
    private fun nextTrack() = sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    private fun prevTrack() = sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun openApp(appName: String): PhaseResult<String> {
        val packageMap = mapOf(
            "youtube"    to "com.google.android.youtube",
            "whatsapp"   to "com.whatsapp",
            "chrome"     to "com.android.chrome",
            "maps"       to "com.google.android.apps.maps",
            "instagram"  to "com.instagram.android",
            "settings"   to "com.android.settings",
            "camera"     to "com.android.camera2",
            "spotify"    to "com.spotify.music",
            "twitter"    to "com.twitter.android",
            "gmail"      to "com.google.android.gm",
            "facebook"   to "com.facebook.katana",
            "telegram"   to "org.telegram.messenger",
            "netflix"    to "com.netflix.mediaclient",
            "amazon"     to "com.amazon.mShop.android.shopping",
            "snapchat"   to "com.snapchat.android",
            "calculator" to "com.android.calculator2",
            "calendar"   to "com.google.android.calendar",
            "contacts"   to "com.google.android.contacts",
            "clock"      to "com.google.android.deskclock",
            "files"      to "com.google.android.documentsui",
            "phone"      to "com.google.android.dialer",
            "messages"   to "com.google.android.apps.messaging",
            "photos"     to "com.google.android.apps.photos",
            "play"       to "com.android.vending",
            "drive"      to "com.google.android.apps.docs",
            "meet"       to "com.google.android.apps.tachyon",
            "zoom"       to "us.zoom.videomeetings",
            "linkedin"   to "com.linkedin.android",
            "reddit"     to "com.reddit.frontpage",
        )

        val pkg = packageMap[appName.lowercase().trim()]
            ?: run {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(appName.lowercase())
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    return PhaseResult.Success("Opened $appName")
                }
                return PhaseResult.Failure("App not found: $appName", canFallback = false)
            }

        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                PhaseResult.Success("Opened $appName")
            } else {
                PhaseResult.Failure("$appName not installed", canFallback = false)
            }
        } catch (e: Exception) {
            PhaseResult.Failure("Failed to open $appName", canFallback = false)
        }
    }

    private fun setAlarm(params: Map<String, String>): PhaseResult<String> {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                params["time"]?.let { time ->
                    val parts = time.replace("am", "").replace("pm", "").trim().split(":")
                    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 7
                    val min  = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    val isPm = time.contains("pm", ignoreCase = true)
                    putExtra(AlarmClock.EXTRA_HOUR, if (isPm && hour != 12) hour + 12 else hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, min)
                }
                params["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(intent)
            PhaseResult.Success("Alarm set")
        } catch (e: Exception) {
            PhaseResult.Failure("Alarm failed: ${e.message}", canFallback = true)
        }
    }

    private fun setTimer(params: Map<String, String>): PhaseResult<String> {
        return try {
            val duration = params["duration"] ?: ""
            val seconds = parseToSeconds(duration)
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                params["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            }
            context.startActivity(intent)
            PhaseResult.Success("Timer started")
        } catch (e: Exception) {
            PhaseResult.Failure("Timer failed", canFallback = true)
        }
    }

    private fun parseToSeconds(duration: String): Int {
        var seconds = 0
        Regex("(\\d+)\\s*(hour|hr|h)", RegexOption.IGNORE_CASE).find(duration)?.let {
            seconds += (it.groupValues[1].toIntOrNull() ?: 0) * 3600
        }
        Regex("(\\d+)\\s*(minute|min|m)", RegexOption.IGNORE_CASE).find(duration)?.let {
            seconds += (it.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        Regex("(\\d+)\\s*(second|sec|s)", RegexOption.IGNORE_CASE).find(duration)?.let {
            seconds += (it.groupValues[1].toIntOrNull() ?: 0)
        }
        return if (seconds == 0) 600 else seconds
    }

    private fun navigate(destination: String): PhaseResult<String> {
        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Navigation started to $destination")
        } catch (e: Exception) {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Maps opened for $destination")
        }
    }

    private fun callContact(params: Map<String, String>): PhaseResult<String> {
        val contact = params["contact"] ?: ""
        val phone = params["phone"] ?: contact
        if (phone.isBlank()) {
            return PhaseResult.Failure("I don't know who to call.", canFallback = false)
        }
        return try {
            val uri = Uri.parse("tel:$phone")
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            VoxynLogger.i("COMMUNICATION: recipient=$contact phone=$phone action=CALL_CONTACT status=SUCCESS", "EXEC")
            PhaseResult.Success("Calling $contact")
        } catch (e: SecurityException) {
            VoxynLogger.e("CALL_PHONE permission missing — falling back to dialer", e, "EXEC")
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Opened dialer for $contact")
        } catch (e: Exception) {
            VoxynLogger.e("Call failed — falling back to dialer", e, "EXEC")
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Opened dialer for $contact")
        }
    }

    private fun sendMessage(params: Map<String, String>): PhaseResult<String> {
        val contact = params["recipient"] ?: params["contact"] ?: ""
        val phone = params["phone"] ?: contact
        val channel = params["channel"] ?: "sms"
        var message = params["message"] ?: ""
        if (message == "<screen>") {
            message = VoxynAccessibilityService.instance?.currentScreenText()?.takeIf { it.isNotBlank() } ?: ""
        }
        if (message.isBlank()) {
            return PhaseResult.Failure("What should I say?", canFallback = false)
        }
        return if (channel == "whatsapp") composer.composeWhatsApp(phone, message)
        else composer.sendSms(phone, message)
    }

    private fun sendWhatsApp(params: Map<String, String>): PhaseResult<String> {
        val contact = params["recipient"] ?: params["contact"] ?: ""
        val phone = params["phone"] ?: contact
        var message = params["message"] ?: ""
        if (message == "<screen>") {
            message = VoxynAccessibilityService.instance?.currentScreenText()?.takeIf { it.isNotBlank() } ?: ""
        }
        if (message.isBlank()) {
            return PhaseResult.Failure("What should I say?", canFallback = false)
        }
        if (!composer.isWhatsAppInstalled()) {
            VoxynLogger.w("COMMUNICATION: channel=WhatsApp action=SEND_WHATSAPP_MESSAGE status=UNAVAILABLE", "EXEC")
            return PhaseResult.Failure("WhatsApp is not installed.", canFallback = false)
        }
        return composer.composeWhatsApp(phone, message)
    }

    private fun contactLookup(params: Map<String, String>): PhaseResult<String> {
        val name = params["contact"] ?: ""
        return when (val res = contactResolver.resolve(name)) {
            is ContactResolution.Unique ->
                PhaseResult.Success("${res.displayName}'s number is ${res.phoneNumber}")
            is ContactResolution.Ambiguous ->
                PhaseResult.Failure("I found ${res.candidates.size} contacts named $name. Which one?", canFallback = false)
            ContactResolution.NotFound ->
                PhaseResult.Failure("I couldn't find a contact named $name.", canFallback = false)
        }
    }
}
