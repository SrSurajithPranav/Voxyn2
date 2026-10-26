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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
internal fun IslandExpandedPanel(store: IslandSurfaceStore, onCollapse: () -> Unit, onShortcut: (String) -> Unit) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            InfoCard("Last verified transcript", state.verifiedTranscript ?: "No verified transcript yet")
            state.recentAction?.let { InfoCard("Recent action", it) }
            state.verificationOutcome?.let { InfoCard("Pipeline result", it) }
            val pipelineBusy = state.voiceState == IslandState.PROCESSING || state.voiceState == IslandState.SPEAKING ||
                state.voiceState == IslandState.EXECUTING || state.voiceState == IslandState.CONFIRMATION
            if (state.voiceState == IslandState.CONFIRMATION) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onShortcut("yes") }, modifier = Modifier.weight(1f)) { Text("Confirm") }
                    Button(onClick = { onShortcut("no") }, modifier = Modifier.weight(1f)) { Text("Reject") }
                }
            }
            InfoCard("Music", "Playback actions use VOXYN's existing verified pipeline. Track metadata and synced lyrics need media-session access, which is not connected.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onShortcut("play music") }, enabled = !pipelineBusy, modifier = Modifier.weight(1f)) { Text("Play") }
                Button(onClick = { onShortcut("pause music") }, enabled = !pipelineBusy, modifier = Modifier.weight(1f)) { Text("Pause") }
                Button(onClick = { onShortcut("next track") }, enabled = !pipelineBusy, modifier = Modifier.weight(1f)) { Text("Next") }
            }
            Button(onClick = { onShortcut("previous track") }, enabled = !pipelineBusy, modifier = Modifier.fillMaxWidth()) { Text("Previous track") }
            val battery = state.batteryPercent?.let { "$it%${if (state.charging) " · charging" else ""}" } ?: "Unavailable"
            val headphoneStatus = if (!state.headphoneStatusAvailable) "unavailable" else if (state.headphonesConnected) "connected" else "not connected"
            InfoCard("Device", "Battery: $battery\nHeadphones: $headphoneStatus")
            SettingRow("Resting mascot", state.mascotEnabled) { scope.launch { store.setMascotEnabled(it) } }
            SettingRow("Minimal notch", state.minimalNotch) { scope.launch { store.setMinimalNotch(it) } }
            InfoCard("Timers, reminders and tasks", "Timer requests hand off to Android Clock. VOXYN cannot read active timer status; focus reminders and to-do lists are not tracked yet.")
            Button(onClick = { onShortcut("show timers") }, modifier = Modifier.fillMaxWidth()) { Text("Open timers") }
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
