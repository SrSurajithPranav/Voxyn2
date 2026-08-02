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
import android.provider.ContactsContract
import android.provider.Settings
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.mode.ModeEngine
import com.voxyn.perception.VoxynAccessibilityService
import com.voxyn.utils.VoxynLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ExecutionController(
    private val context: Context,
    private val modeEngine: ModeEngine,
    private val appLaunchResolver: AppLaunchResolver
) {

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("Executing: ${step.actionType}", "EXEC")
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
                "SEND_MESSAGE"     -> sendMessage(step.params)
                "CALL_CONTACT"     -> callContact(step.params["contact"] ?: "")
                "LOCK_PHONE"       -> lockPhone()
                "MODE_ACTIVATE"    -> activateMode(step.params["mode_name"] ?: "normal")
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
        val stops = parseDestinations(destination)
        if (stops.size > 1) {
            return openMultiStopNavigation(stops)
        }

        val finalDestination = stops.firstOrNull().orEmpty()
        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(finalDestination)}&mode=d")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Navigation started to $finalDestination")
        } catch (e: Exception) {
<<<<<<< HEAD
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
=======
            // Fallback: open maps with search
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(finalDestination)}")
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Maps opened for $finalDestination")
        }
    }

<<<<<<< HEAD
=======
    private fun openMultiStopNavigation(stops: List<String>): PhaseResult<String> {
        return try {
            val destination = stops.last()
            val waypoints = stops.dropLast(1).joinToString("|") { Uri.encode(it) }
            val mapsUrl = StringBuilder("https://www.google.com/maps/dir/?api=1")
                .append("&origin=My+Location")
                .append("&destination=").append(Uri.encode(destination))
                .apply {
                    if (waypoints.isNotBlank()) {
                        append("&waypoints=").append(waypoints)
                    }
                }
                .toString()

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Multi-stop navigation started")
        } catch (e: Exception) {
            PhaseResult.Failure("Failed to start multi-stop navigation", canFallback = true)
        }
    }

    private fun parseDestinations(raw: String): List<String> {
        return raw
            .replace(Regex("\\s+and\\s+then\\s+", RegexOption.IGNORE_CASE), " then ")
            .replace(Regex("\\s+then\\s+", RegexOption.IGNORE_CASE), "|")
            .replace(",", "|")
            .replace(" and ", "|")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    // ─── Messaging ────────────────────────────────────────────────────

    private fun sendMessage(params: Map<String, String>): PhaseResult<String> {
        val message = params["message"]?.trim().orEmpty()
        val contact = params["contact"]?.trim().orEmpty()
        val platform = params["platform"]?.trim()?.lowercase().orEmpty()

        if (message.isBlank()) return PhaseResult.Failure("Message body missing", canFallback = true)
        if (contact.isBlank()) return PhaseResult.Failure("Contact missing", canFallback = true)

        return when (platform) {
            "whatsapp" -> sendWhatsAppMessage(contact, message)
            "sms", "instagram", "" -> sendSmsMessage(contact, message)
            else -> sendSmsMessage(contact, message)
        }
    }

    private fun sendSmsMessage(contactOrNumber: String, message: String): PhaseResult<String> {
        val recipient = resolvePhoneNumber(contactOrNumber) ?: contactOrNumber
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(recipient)}")).apply {
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("SMS prepared for $contactOrNumber")
        } catch (e: Exception) {
            PhaseResult.Failure("Failed to open SMS app", canFallback = true)
        }
    }

    private fun sendWhatsAppMessage(contactOrNumber: String, message: String): PhaseResult<String> {
        val number = resolvePhoneNumber(contactOrNumber) ?: contactOrNumber
        val cleanedNumber = number.filter { it.isDigit() || it == '+' }
        val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.toString())

        return try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanedNumber&text=$encodedMessage")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("WhatsApp message prepared for $contactOrNumber")
        } catch (e: Exception) {
            PhaseResult.Failure("Failed to open WhatsApp", canFallback = true)
        }
    }

    private fun resolvePhoneNumber(contactName: String): String? {
        if (contactName.any { it.isDigit() }) return contactName

        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$contactName%")

        return runCatching {
            resolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        }.getOrNull()
    }

    // ─── Call ─────────────────────────────────────────────────────────

>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
    private fun callContact(contact: String): PhaseResult<String> {
        return try {
            val uri = Uri.parse("tel:$contact")
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Success("Calling $contact")
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            PhaseResult.Failure("Opened dialer", canFallback = false)
        }
    }
}
