package com.voxyn.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxyn.ui.theme.*
import com.voxyn.voice.AsrDownloadState
import org.koin.androidx.compose.koinViewModel

private val SUCCESS_COLOR = Color(0xFF00E676)
private val ERROR_COLOR   = Color(0xFFFF1744)
private val WARN_COLOR    = Color(0xFFFFAB00)
private val DIM_COLOR     = Color(0xFF546E7A)

@Composable
fun MainScreen(
    onStartVoiceInput: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    viewModel: VoxynViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val localAi by viewModel.localAi.collectAsState()
    val asrState by viewModel.asrState.collectAsState()
    val storagePrompt by viewModel.storagePrompt.collectAsState()
    var input by remember { mutableStateOf("") }
    var voiceStudioOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VoxynBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(isRunning = state.isRunning, phase = state.currentPhase?.name)
            PhaseBar(currentPhase = state.currentPhase?.ordinal ?: -1, isRunning = state.isRunning)
            LogPanel(logs = state.logs, modifier = Modifier.weight(1f))
            LocalAiPanel(
                state = localAi,
                asrState = asrState,
                onDownload = { viewModel.downloadLocalModel() },
                onAsrDownload = { viewModel.downloadAsrModel() }
            )
            OutlinedButton(
                onClick = { voiceStudioOpen = true },
                border = BorderStroke(1.dp, VoxynCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("VOICE STUDIO — FIVE SPEAKER PROFILES", color = VoxynCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            CommandInput(
                value = input,
                isRunning = state.isRunning,
                onValueChange = { input = it },
                onSubmit = {
                    if (input.isNotBlank()) {
                        viewModel.submit(input)
                        input = ""
                    }
                },
                onStartVoiceInput = onStartVoiceInput,
                onInterrupt = { viewModel.interrupt() }
            )
        }

        if (storagePrompt) {
            AlertDialog(
                onDismissRequest = { viewModel.showStoragePrompt(false) },
                title = { Text("Restore models without re-downloading?") },
                text = {
                    Text(
                        "VOXYN found model files in your Downloads folder, but Android " +
                            "hides them from apps. Allow \"All files access\" so VOXYN can " +
                            "restore the speech + AI models instantly — no 1.2 GB re-download."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.showStoragePrompt(false)
                        onRequestAllFilesAccess()
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showStoragePrompt(false) }) { Text("Later") }
                }
            )
        }
        if (voiceStudioOpen) {
            VoiceStudioDialog(onDismiss = { voiceStudioOpen = false })
        }
    }
}

@Composable
private fun VoiceStudioDialog(onDismiss: () -> Unit, viewModel: VoiceStudioViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    AlertDialog(
        onDismissRequest = { viewModel.stopPlayback(); onDismiss() },
        title = { Text("VOICE STUDIO", fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enroll exactly five speaker profiles. Record a clear six-second sample.", fontSize = 12.sp)
                state.profiles.forEach { profile ->
                    var label by remember(profile.id) { mutableStateOf(profile.label) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            singleLine = true,
                            label = { Text("Slot ${profile.slot + 1}") },
                            modifier = Modifier.weight(1f)
                        )
                        if (profile.enrolled) {
                            TextButton(onClick = { viewModel.play(profile.slot) }) { Text("PLAY") }
                            TextButton(onClick = { viewModel.record(profile.slot, label) }, enabled = state.recordingSlot == null) { Text("RETAKE") }
                            TextButton(onClick = { viewModel.selectReplyVoice(profile.slot) }) { Text(if (state.selectedReplyVoiceId == profile.id) "SELECTED" else "USE") }
                            TextButton(onClick = { viewModel.delete(profile.slot) }) { Text("CLEAR") }
                        } else {
                            TextButton(onClick = { viewModel.record(profile.slot, label) }, enabled = state.recordingSlot == null) { Text("RECORD") }
                        }
                    }
                }
                if (state.recordingSlot != null) Text(state.message, color = WARN_COLOR, fontSize = 12.sp)
                else if (state.message.isNotBlank()) Text(state.message, color = DIM_COLOR, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.stopPlayback(); onDismiss() }) { Text("DONE") } }
    )
}

@Composable
fun Header(isRunning: Boolean, phase: String?) {
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (isRunning) VoxynCyan.copy(alpha = pulse) else DIM_COLOR)
        )
        Text("VOXYN", color = VoxynCyan, fontSize = 22.sp, fontWeight = FontWeight.Black,
            letterSpacing = 6.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        if (phase != null && isRunning) {
            Text(phase, color = DIM_COLOR, fontSize = 11.sp, letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun PhaseBar(currentPhase: Int, isRunning: Boolean) {
    val phases = listOf("INPUT", "PERCEIVE", "INTENT", "DECIDE", "PLAN", "EXEC", "VERIFY", "LEARN")
    Surface(shape = RoundedCornerShape(8.dp), color = VoxynSurface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            phases.forEachIndexed { i, label ->
                Text(
                    label,
                    color = when {
                        i == currentPhase && isRunning -> VoxynCyan
                        i < currentPhase && isRunning  -> SUCCESS_COLOR
                        else                           -> DIM_COLOR
                    },
                    fontSize = 8.sp,
                    fontWeight = if (i == currentPhase) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = VoxynSurface,
        border = BorderStroke(1.dp, DIM_COLOR.copy(alpha = 0.3f))
    ) {
        if (logs.isEmpty()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("awaiting input...", color = DIM_COLOR, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { log ->
                    AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically()) {
                        val color = when {
                            log.startsWith("✓") || log.startsWith("🎯") -> SUCCESS_COLOR
                            log.startsWith("✗") || log.startsWith("⛔") -> ERROR_COLOR
                            log.startsWith("⚡") || log.startsWith("⚠️") -> WARN_COLOR
                            log.startsWith("▶") -> VoxynCyan
                            else -> VoxynText.copy(alpha = 0.8f)
                        }
                        Text(log, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun LocalAiPanel(
    state: LocalAiUiState,
    asrState: AsrDownloadState,
    onDownload: () -> Unit,
    onAsrDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = VoxynSurface,
        border = BorderStroke(1.dp, DIM_COLOR.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "LOCAL AI",
                    color = VoxynCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    when {
                        state.downloading -> "downloading ${(state.progress * 100).toInt()}%"
                        state.modelPresent -> "model ready — works offline"
                        state.message.isNotBlank() -> state.message
                        else -> "not installed — download once, saved in Downloads"
                    },
                    color = if (state.modelPresent) SUCCESS_COLOR else DIM_COLOR,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                if (!state.modelPresent && !state.downloading) {
                    OutlinedButton(
                        onClick = onDownload,
                        border = BorderStroke(1.dp, VoxynCyan),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("DOWNLOAD", color = VoxynCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (state.downloading) {
                    Text("${(state.progress * 100).toInt()}%", color = VoxynCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = DIM_COLOR.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "SPEECH",
                    color = VoxynCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    when {
                        asrState.downloading -> "downloading ${(asrState.progress * 100).toInt()}%"
                        asrState.modelPresent -> "Parakeet STT ready — offline"
                        asrState.message.isNotBlank() -> asrState.message
                        else -> "not installed — Vosk fallback active"
                    },
                    color = if (asrState.modelPresent) SUCCESS_COLOR else DIM_COLOR,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                if (!asrState.modelPresent && !asrState.downloading) {
                    OutlinedButton(
                        onClick = onAsrDownload,
                        border = BorderStroke(1.dp, VoxynCyan),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("DOWNLOAD", color = VoxynCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (asrState.downloading) {
                    Text("${(asrState.progress * 100).toInt()}%", color = VoxynCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun CommandInput(
    value: String,
    isRunning: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStartVoiceInput: () -> Unit,
    onInterrupt: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = !isRunning,
            placeholder = {
                Text("enter command...", color = DIM_COLOR, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            },
            textStyle = LocalTextStyle.current.copy(color = VoxynText, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VoxynCyan,
                unfocusedBorderColor = DIM_COLOR.copy(alpha = 0.4f),
                cursorColor = VoxynCyan
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSubmit,
                enabled = !isRunning && value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = VoxynCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("EXECUTE", color = VoxynBg, fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
            }
            OutlinedButton(
                onClick = onStartVoiceInput,
                enabled = !isRunning,
                border = BorderStroke(1.dp, VoxynCyan),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(MicIcon, contentDescription = "Start voice input", tint = VoxynCyan)
            }
            if (isRunning) {
                OutlinedButton(
                    onClick = onInterrupt,
                    border = BorderStroke(1.dp, ERROR_COLOR),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("STOP", color = ERROR_COLOR, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private val MicIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Mic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
    ) {
        // Microphone body
        moveTo(12f, 14f)
        curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
        lineTo(15f, 5f)
        curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
        reflectiveCurveToRelative(-3f, 1.34f, -3f, 3f)
        lineToRelative(0f, 6f)
        curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
        close()
        // Stand
        moveTo(11f, 14.17f)
        curveToRelative(-2.83f, -0.28f, -5f, -2.66f, -5f, -5.17f)
        lineTo(6f, 7f)
        lineTo(4f, 7f)
        lineToRelative(0f, 2f)
        curveToRelative(0f, 3.53f, 2.61f, 6.43f, 6f, 6.92f)
        lineTo(10f, 21f)
        lineTo(8f, 21f)
        lineToRelative(0f, 2f)
        lineToRelative(8f, 0f)
        lineToRelative(0f, -2f)
        lineToRelative(-2f, 0f)
        lineToRelative(0f, -5.08f)
        curveToRelative(3.39f, -0.49f, 6f, -3.39f, 6f, -6.92f)
        lineTo(20f, 7f)
        lineToRelative(-2f, 0f)
        lineToRelative(0f, 2f)
        curveToRelative(0f, 2.51f, -2.17f, 4.89f, -5f, 5.17f)
        close()
    }.build()
}
