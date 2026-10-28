package com.voxyn.overlay

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxyn.MainActivity
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService
import kotlinx.coroutines.launch

@Composable
internal fun IslandExpandedPanel(store: IslandSurfaceStore, todoRepository: IslandTodoRepository, mediaRepository: IslandMediaRepository, activityRepository: IslandActivityRepository, onCollapse: () -> Unit, onShortcut: (String) -> Unit) {
    val state by store.state.collectAsState()
    val todos by todoRepository.items.collectAsState()
    val media by mediaRepository.state.collectAsState()
    val activities by activityRepository.activities.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var todoDraft by remember { mutableStateOf("") }
    var reminderTitle by remember { mutableStateOf("") }
    var reminderMinutes by remember { mutableStateOf("5") }
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xF20B1119),
        contentColor = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x5539D7E8))
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("VOXYN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF80DEEA))
                    Text(state.label, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onCollapse) { Text("Close") }
            }
            if (state.voiceState == IslandState.STOPPED) {
                Button(onClick = {
                    try {
                        context.startForegroundService(Intent(context, VoiceInputService::class.java).apply { action = VoiceInputService.ACTION_START_LISTENING })
                    } catch (e: Exception) {
                        VoxynLogger.e("Could not resume voice service: ${e.message}", tag = "OVERLAY")
                        android.widget.Toast.makeText(context, "Could not resume listening", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Resume listening") }
            } else {
                Text("Listening remains active in the background. Hold the island to pause.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            }
            val currentTranscript = when {
                state.partialTranscript.isNotBlank() -> "Live: ${state.partialTranscript}"
                state.verifiedTranscript != null -> state.verifiedTranscript.orEmpty()
                state.transcriptionUnavailable -> "Speech recognition unavailable"
                state.voiceState == IslandState.RECOVERING -> state.label
                else -> "No transcript available"
            }
            InfoCard("Voice transcript", currentTranscript)
            state.recentAction?.let { InfoCard("Recent action", it) }
            state.verificationOutcome?.let { InfoCard("Pipeline result", it) }
            val pipelineBusy = state.voiceState in setOf(
                IslandState.PROCESSING, IslandState.PLANNING, IslandState.SPEAKING,
                IslandState.EXECUTING, IslandState.VERIFYING, IslandState.CONFIRMATION
            )
            if (state.voiceState == IslandState.CONFIRMATION) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onShortcut("yes") }, modifier = Modifier.weight(1f)) { Text("Confirm") }
                    Button(onClick = { onShortcut("no") }, modifier = Modifier.weight(1f)) { Text("Reject") }
                }
            }
            val track = listOfNotNull(media.title, media.artist).joinToString(" · ")
            val musicStatus = when {
                !media.accessGranted -> "Allow VOXYN in Android Notification access to read active media sessions."
                !media.sessionAvailable -> "No active media session exposed by Android."
                track.isBlank() -> "A session is active, but track metadata is unavailable."
                else -> "$track\n${if (media.isPlaying) "Playing" else "Paused"}"
            }
            InfoCard("Music", musicStatus)
            if (!media.accessGranted) {
                Button(onClick = {
                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { VoxynLogger.w("Notification access settings unavailable: ${it.message}", "OVERLAY") }
                }, modifier = Modifier.fillMaxWidth()) { Text("Set up media access") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { mediaRepository.skipPrevious() }, enabled = media.canSkipPrevious, modifier = Modifier.weight(1f)) { Text("Previous") }
                    Button(onClick = { mediaRepository.togglePlayback() }, enabled = media.canTogglePlayback, modifier = Modifier.weight(1f)) { Text(if (media.isPlaying) "Pause" else "Play") }
                    Button(onClick = { mediaRepository.skipNext() }, enabled = media.canSkipNext, modifier = Modifier.weight(1f)) { Text("Next") }
                }
            }
            Text("Lyrics are unavailable: no licensed lyrics source is configured.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            TextButton(onClick = { onShortcut("play music") }, enabled = !pipelineBusy) { Text("Ask VOXYN to play music") }
            val battery = state.batteryPercent?.let { "$it%${if (state.charging) " · charging" else ""}" } ?: "Unavailable"
            val headphoneStatus = if (!state.headphoneStatusAvailable) "unavailable" else if (state.headphonesConnected) "connected" else "not connected"
            InfoCard("Device", "Battery: $battery\nHeadphones: $headphoneStatus")
            SettingRow("Resting mascot", state.mascotEnabled) { scope.launch { store.setMascotEnabled(it) } }
            SettingRow("Minimal notch", state.minimalNotch) { scope.launch { store.setMinimalNotch(it) } }
            InfoCard("Focus timer and reminders", "VOXYN schedules approximate local reminders. Android may delay them during Doze; exact-alarm access is not requested.")
            Button(onClick = { scope.launch { activityRepository.schedule(IslandActivityKind.FOCUS_TIMER, "Focus session", 25) } }, modifier = Modifier.fillMaxWidth()) { Text("Start 25-minute focus timer") }
            Text("Schedule a reminder", style = MaterialTheme.typography.titleSmall, color = Color(0xFF80DEEA))
            TextField(value = reminderTitle, onValueChange = { reminderTitle = it.take(100) }, modifier = Modifier.fillMaxWidth(), label = { Text("Reminder") }, singleLine = true)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = reminderMinutes, onValueChange = { reminderMinutes = it.filter(Char::isDigit).take(5) }, modifier = Modifier.weight(1f), label = { Text("Minutes from now") }, singleLine = true)
                val parsedMinutes = reminderMinutes.toIntOrNull()
                Button(onClick = {
                    scope.launch {
                        val scheduled = activityRepository.schedule(IslandActivityKind.REMINDER, reminderTitle, parsedMinutes ?: 0)
                        if (scheduled != null) reminderTitle = ""
                    }
                }, enabled = reminderTitle.trim().isNotEmpty() && parsedMinutes != null && parsedMinutes in 1..10_080) { Text("Set") }
            }
            if (activities.isEmpty()) Text("No active timers or reminders", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            activities.forEach { activity ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val kindLabel = if (activity.kind == IslandActivityKind.FOCUS_TIMER) "Focus" else "Reminder"
                    val dueText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(activity.dueAtMillis))
                    Text("$kindLabel · ${activity.title}\n$dueText", Modifier.weight(1f), color = Color.White)
                    Button(onClick = { scope.launch { activityRepository.cancel(activity.id) } }) { Text("Cancel") }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Text("Notifications are disabled, so due reminders will not alert you.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFCC80))
                TextButton(onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open notification settings") }
            }
            Button(onClick = { onShortcut("show timers") }, modifier = Modifier.fillMaxWidth()) { Text("Open timers") }
            Text("To-do", style = MaterialTheme.typography.titleSmall, color = Color(0xFF80DEEA))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = todoDraft, onValueChange = { todoDraft = it.take(121) }, modifier = Modifier.weight(1f), label = { Text("Add a task") }, singleLine = true)
                Button(onClick = { scope.launch { if (todoRepository.add(todoDraft)) todoDraft = "" } }, enabled = todoDraft.trim().isNotEmpty() && todoDraft.trim().length <= 120) { Text("Add") }
            }
            if (todos.isEmpty()) Text("No tasks yet", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            todos.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.completed, onCheckedChange = { checked -> scope.launch { todoRepository.setCompleted(item.id, checked) } })
                    Text(item.title, Modifier.weight(1f), color = if (item.completed) Color.LightGray else Color.White)
                    Button(onClick = { scope.launch { todoRepository.delete(item.id) } }) { Text("Delete") }
                }
            }
            Button(onClick = {
                context.startActivity(Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP })
            }, modifier = Modifier.fillMaxWidth()) { Text("Open VOXYN") }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF151E28), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFF80DEEA), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE4EBF0))
    }
}

@Composable
private fun SettingRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF151E28), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Color.White)
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}
