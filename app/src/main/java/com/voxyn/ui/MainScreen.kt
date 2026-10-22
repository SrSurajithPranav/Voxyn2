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
import com.voxyn.security.SpeakerProfile
import com.voxyn.voice.VoiceOption
import com.voxyn.security.VoiceEnrollmentState
import com.voxyn.ui.theme.*
import com.voxyn.voice.AsrDownloadState
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val enrolledProfiles by viewModel.enrolledProfiles.collectAsState()
    val enrollmentState by viewModel.enrollmentState.collectAsState()
    var input by remember { mutableStateOf("") }

    var showDiagnostics by remember { mutableStateOf(false) }
    var showVoiceProfilesDialog by remember { mutableStateOf(false) }
    var showVoiceSelectorDialog by remember { mutableStateOf(false) }
    val activeVoiceOption by viewModel.activeVoiceOption.collectAsState()
    var selectedVoice by remember { mutableStateOf(viewModel.currentVoice) }

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
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showVoiceSelectorDialog = true },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, VoxynCyan.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    val currentTitle = activeVoiceOption?.displayName ?: selectedVoice.displayName
                    Text("🗣️ $currentTitle", color = VoxynCyan, fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        viewModel.clearEnrollmentStatus()
                        showVoiceProfilesDialog = true
                    },
                    modifier = Modifier.weight(1.3f),
                    border = BorderStroke(
                        1.dp,
                        if (enrolledProfiles.isEmpty()) WARN_COLOR.copy(alpha = 0.8f) else SUCCESS_COLOR.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    val label = if (enrolledProfiles.isEmpty()) "🎙️ Add My Voice" else "🎙️ Voice ID (${enrolledProfiles.size}/25)"
                    Text(
                        label,
                        color = if (enrolledProfiles.isEmpty()) WARN_COLOR else SUCCESS_COLOR,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = { showDiagnostics = true },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Color(0xFF64B5F6).copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Text("Diagnostics", color = Color(0xFF64B5F6), fontSize = 11.sp, maxLines = 1)
                }
            }

            LocalAiPanel(
                state = localAi,
                asrState = asrState,
                onDownload = { viewModel.downloadLocalModel() },
                onAsrDownload = { viewModel.downloadAsrModel() }
            )
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

        if (showDiagnostics) {
            val report = viewModel.exportDiagnosticReport()
            AlertDialog(
                onDismissRequest = { showDiagnostics = false },
                title = { Text("Voice Diagnostics Report", color = Color.White) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = report,
                            color = Color(0xFFCFD8DC),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDiagnostics = false }) { Text("Close") }
                }
            )
        }

        if (showVoiceSelectorDialog) {
            VoiceSelectorDialog(
                options = viewModel.getAllVoiceOptions(),
                activeOption = activeVoiceOption,
                onSelect = { option ->
                    viewModel.selectVoiceOption(option)
                    showVoiceSelectorDialog = false
                },
                onPreview = { option -> viewModel.previewVoiceOption(option) },
                onOpenProfiles = {
                    showVoiceSelectorDialog = false
                    viewModel.clearEnrollmentStatus()
                    showVoiceProfilesDialog = true
                },
                onDismiss = { showVoiceSelectorDialog = false }
            )
        }

        if (showVoiceProfilesDialog) {
            VoiceProfilesDialog(
                profiles = enrolledProfiles,
                enrollmentState = enrollmentState,
                activeVoiceOption = activeVoiceOption,
                onEnroll = { name, isOwner -> viewModel.startEnrollment(name, isOwner) },
                onEnrollFromFile = { name, isOwner, uri -> viewModel.startEnrollmentFromFile(name, isOwner, uri) },
                onAddSample = { profileId -> viewModel.addSample(profileId) },
                onAddSampleFromFile = { profileId, uri -> viewModel.addSampleFromFile(profileId, uri) },
                onCancelEnroll = { viewModel.cancelEnrollment() },
                onTestVoice = { viewModel.testVoiceMatch() },
                onDeleteProfile = { id -> viewModel.deleteProfile(id) },
                onSelectVoice = { profile -> viewModel.selectClonedVoice(profile) },
                onPreviewVoice = { profile -> viewModel.previewProfileVoice(profile) },
                onClearStatus = { viewModel.clearEnrollmentStatus() },
                onDismiss = {
                    viewModel.cancelEnrollment()
                    showVoiceProfilesDialog = false
                }
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
    }
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

@Composable
private fun VoiceProfilesDialog(
    profiles: List<SpeakerProfile>,
    enrollmentState: VoiceEnrollmentState,
    activeVoiceOption: VoiceOption?,
    onEnroll: (name: String, isOwner: Boolean) -> Unit,
    onEnrollFromFile: (name: String, isOwner: Boolean, uri: Uri) -> Unit,
    onAddSample: (profileId: String) -> Unit,
    onAddSampleFromFile: (profileId: String, uri: Uri) -> Unit,
    onCancelEnroll: () -> Unit,
    onTestVoice: () -> Unit,
    onDeleteProfile: (id: String) -> Unit,
    onSelectVoice: (profile: SpeakerProfile) -> Unit,
    onPreviewVoice: (profile: SpeakerProfile) -> Unit,
    onClearStatus: () -> Unit,
    onDismiss: () -> Unit
) {
    var profileName by remember { mutableStateOf(if (profiles.isEmpty()) "Owner" else "") }
    var isOwner by remember { mutableStateOf(profiles.isEmpty()) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onEnrollFromFile(profileName, isOwner, uri)
        }
    }

    var targetProfileForSample by remember { mutableStateOf<String?>(null) }
    val sampleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val target = targetProfileForSample
        if (uri != null && target != null) {
            onAddSampleFromFile(target, uri)
            targetProfileForSample = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Voice ID & Biometrics",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${profiles.size}/25 Enrolled Profiles",
                        color = VoxynCyan,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Text("✕", color = Color.White, fontSize = 14.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Info banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF131B24), RoundedCornerShape(8.dp))
                        .border(1.dp, VoxynCyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        "Enroll your voice so VOXYN recognizes you, rejects unknown voices, and securely gates sensitive voice actions.",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }

                // Enrolled Profiles List Header
                Text(
                    "ENROLLED PROFILES",
                    color = VoxynCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                if (profiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WARN_COLOR.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .border(1.dp, WARN_COLOR.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            "No voice profiles enrolled yet. Anyone near the phone can speak commands. Enroll your voice below to secure VOXYN!",
                            color = WARN_COLOR,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profiles.forEach { profile ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161F2B)),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (profile.isOwner) VoxynCyan.copy(alpha = 0.5f) else Color(0xFF263238)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    profile.name,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                if (profile.isOwner) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                VoxynCyan.copy(alpha = 0.2f),
                                                                RoundedCornerShape(4.dp)
                                                            )
                                                            .border(0.5.dp, VoxynCyan, RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            "OWNER",
                                                            color = VoxynCyan,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            val dateStr = runCatching {
                                                SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(profile.enrolledAtMs))
                                            }.getOrDefault("Recently")
                                            Text(
                                                "${profile.sampleVoiceprints.size} sample(s) (max 25) • 32-dim MFCC • Enrolled $dateStr",
                                                color = DIM_COLOR,
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                "Acoustic Clone: Pitch ${String.format(Locale.US, "%.2f", profile.pitch)}x • Speed ${String.format(Locale.US, "%.2f", profile.speechRate)}x • Timbre: ${profile.timbre.replaceFirstChar { it.uppercase() }}",
                                                color = VoxynCyan.copy(alpha = 0.85f),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteProfile(profile.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Text("🗑️", fontSize = 14.sp)
                                        }
                                    }

                                    // Add Samples Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onAddSample(profile.id) },
                                            border = BorderStroke(1.dp, VoxynCyan.copy(alpha = 0.4f)),
                                            shape = RoundedCornerShape(4.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("+ Mic Sample", color = VoxynCyan, fontSize = 10.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                targetProfileForSample = profile.id
                                                sampleFilePickerLauncher.launch("audio/*")
                                            },
                                            border = BorderStroke(1.dp, VoxynCyan.copy(alpha = 0.4f)),
                                            shape = RoundedCornerShape(4.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("+ Audio File Sample", color = VoxynCyan, fontSize = 10.sp)
                                        }
                                    }

                                    // Voice Clone Action Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isCurrentSpeakingVoice = activeVoiceOption?.profileId == profile.id
                                        Button(
                                            onClick = { onSelectVoice(profile) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isCurrentSpeakingVoice) SUCCESS_COLOR else VoxynCyan
                                            ),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                if (isCurrentSpeakingVoice) "✓ Active Voice" else "🔊 Reply in ${profile.name}'s Voice",
                                                color = VoxynBg,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { onPreviewVoice(profile) },
                                            border = BorderStroke(1.dp, VoxynCyan.copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("▶ Preview", color = VoxynCyan, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Test Voice Match Button
                    OutlinedButton(
                        onClick = onTestVoice,
                        enabled = enrollmentState !is VoiceEnrollmentState.Recording,
                        border = BorderStroke(1.dp, Color(0xFF81D4FA)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🧪 Test Voice Match (2s)", color = Color(0xFF81D4FA), fontSize = 12.sp)
                    }
                }

                // Add Profile Section
                if (profiles.size < 25) {
                    HorizontalDivider(color = Color(0xFF263238))
                    Text(
                        "ENROLL NEW VOICE",
                        color = VoxynCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        "Enter your name or a friend's name and record with the mic (3s) or select an audio file (.wav, .mp3, .m4a). VOXYN extracts 32-dim MFCC acoustic signatures, clones vocal timbre, and matches against up to 25 profiles & samples!",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Profile Name", fontSize = 12.sp) },
                        placeholder = { Text("e.g. Owner, Sriram", color = DIM_COLOR) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VoxynCyan,
                            unfocusedBorderColor = Color(0xFF37474F),
                            focusedLabelColor = VoxynCyan,
                            cursorColor = VoxynCyan,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = isOwner,
                            onCheckedChange = { isOwner = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = VoxynCyan,
                                checkmarkColor = VoxynBg
                            )
                        )
                        Text(
                            "Designate as Primary Owner",
                            color = Color(0xFFECEFF1),
                            fontSize = 12.sp
                        )
                    }

                    // Enrollment state reactive cards
                    when (enrollmentState) {
                        is VoiceEnrollmentState.Idle -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onEnroll(profileName, isOwner) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = VoxynCyan),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "🎙️ Record Voice & Enroll (3s)",
                                        color = VoxynBg,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = { audioPickerLauncher.launch("audio/*") },
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.dp, VoxynCyan),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "📁 Import Audio File (.wav, .mp3, .m4a)",
                                        color = VoxynCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        is VoiceEnrollmentState.Recording -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1A1A24), RoundedCornerShape(8.dp))
                                    .border(1.dp, VoxynCyan, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "🔴 Listening... Speak clearly now!",
                                        color = ERROR_COLOR,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${enrollmentState.secondsRemaining}s left",
                                        color = VoxynCyan,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { enrollmentState.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = VoxynCyan,
                                    trackColor = Color(0xFF263238)
                                )
                                OutlinedButton(
                                    onClick = onCancelEnroll,
                                    modifier = Modifier.align(Alignment.End),
                                    border = BorderStroke(1.dp, ERROR_COLOR.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Cancel", color = ERROR_COLOR, fontSize = 10.sp)
                                }
                            }
                        }
                        is VoiceEnrollmentState.Processing -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF131B24), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = VoxynCyan,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    "Extracting 128-dim acoustic voiceprint...",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        is VoiceEnrollmentState.Success -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SUCCESS_COLOR.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                    .border(1.dp, SUCCESS_COLOR, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "✓ Success",
                                        color = SUCCESS_COLOR,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        enrollmentState.message,
                                        color = Color.White,
                                        fontSize = 11.sp
                                    )
                                    TextButton(
                                        onClick = onClearStatus,
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Enroll Another", color = SUCCESS_COLOR, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        is VoiceEnrollmentState.Error -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ERROR_COLOR.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                    .border(1.dp, ERROR_COLOR, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "⚠️ Enrollment Issue",
                                        color = ERROR_COLOR,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        enrollmentState.message,
                                        color = Color.White,
                                        fontSize = 11.sp
                                    )
                                    TextButton(
                                        onClick = onClearStatus,
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Try Again", color = ERROR_COLOR, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        is VoiceEnrollmentState.TestResult -> {
                            val color = if (enrollmentState.verified) SUCCESS_COLOR else ERROR_COLOR
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                    .border(1.dp, color, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        enrollmentState.summary,
                                        color = color,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        enrollmentState.details,
                                        color = Color.White,
                                        fontSize = 11.sp
                                    )
                                    TextButton(
                                        onClick = onClearStatus,
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Dismiss", color = color, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161F2B), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            "Maximum of 5 profiles reached. Delete an existing profile to add another.",
                            color = DIM_COLOR,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = VoxynCyan)
            }
        }
    )
}

@Composable
fun VoiceSelectorDialog(
    options: List<VoiceOption>,
    activeOption: VoiceOption?,
    onSelect: (VoiceOption) -> Unit,
    onPreview: (VoiceOption) -> Unit,
    onOpenProfiles: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Speaking Voice",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Text("✕", color = Color.White, fontSize = 14.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Switch which voice VOXYN uses to reply. Choose your cloned voice, a friend's voice, or a standard preset.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                val clonedVoices = options.filter { it.isCloned }
                val presetVoices = options.filter { !it.isCloned }

                Text(
                    "CLONED VOICES (${clonedVoices.size})",
                    color = VoxynCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                if (clonedVoices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF131B24), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF263238), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            "No cloned voices yet. Enroll your voice or your friends' voices to have VOXYN speak as them!",
                            color = DIM_COLOR,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    clonedVoices.forEach { opt ->
                        val isSelected = activeOption?.id == opt.id
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xFF0F2A38) else Color(0xFF161F2B)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) SUCCESS_COLOR else VoxynCyan.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(opt.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Box(
                                            modifier = Modifier
                                                .background(VoxynCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .border(0.5.dp, VoxynCyan, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("CLONED", color = VoxynCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(
                                        "Pitch: ${String.format(Locale.US, "%.2f", opt.pitch)}x • Rate: ${String.format(Locale.US, "%.2f", opt.speechRate)}x",
                                        color = DIM_COLOR,
                                        fontSize = 10.sp
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { onPreview(opt) },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("▶", color = VoxynCyan, fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = { onSelect(opt) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) SUCCESS_COLOR else VoxynCyan
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            if (isSelected) "Active" else "Select",
                                            color = VoxynBg,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "PRESET VOICES",
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                presetVoices.forEach { opt ->
                    val isSelected = activeOption?.id == opt.id
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF0F2A38) else Color(0xFF161F2B)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) SUCCESS_COLOR else Color(0xFF263238)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(opt.displayName, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { onPreview(opt) },
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("▶", color = VoxynCyan, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { onSelect(opt) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) SUCCESS_COLOR else VoxynCyan
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (isSelected) "Active" else "Select",
                                        color = VoxynBg,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onOpenProfiles) {
                    Text("🎙️ Clone Friend's Voice", color = VoxynCyan, fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    )
}
