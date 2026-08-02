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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
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
import com.voxyn.wake.VoskModelState
import org.koin.androidx.compose.koinViewModel

private val SUCCESS_COLOR = Color(0xFF00E676)
private val ERROR_COLOR   = Color(0xFFFF1744)
private val WARN_COLOR    = Color(0xFFFFAB00)
private val DIM_COLOR     = Color(0xFF546E7A)

@Composable
fun MainScreen(
    onStartVoiceInput: () -> Unit,
    viewModel: VoxynViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val isModelReady = state.modelState is VoskModelState.Ready

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
            ModelStateBanner(
                modelState = state.modelState,
                onRetry = { viewModel.retryModelDownload() }
            )
            PhaseBar(currentPhase = state.currentPhase?.ordinal ?: -1, isRunning = state.isRunning)
            LogPanel(logs = state.logs, modifier = Modifier.weight(1f))
            CommandInput(
                value = input,
                isRunning = state.isRunning,
<<<<<<< HEAD
=======
                modelReady = isModelReady,
                awaitingConfirmation = state.awaitingConfirmation,
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
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
    }
}

@Composable
fun ModelStateBanner(
    modelState: VoskModelState,
    onRetry: () -> Unit
) {
    when (modelState) {
        is VoskModelState.Uninitialized -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = VoxynSurface,
                border = BorderStroke(1.dp, DIM_COLOR.copy(alpha = 0.4f))
            ) {
                Text(
                    "Preparing offline wake model...",
                    color = VoxynText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        is VoskModelState.Downloading, is VoskModelState.Extracting -> {
            val progress = when (modelState) {
                is VoskModelState.Downloading -> modelState.progress
                is VoskModelState.Extracting -> modelState.progress
                else -> 0f
            }
            val label = when (modelState) {
                is VoskModelState.Downloading -> "Downloading offline model"
                is VoskModelState.Extracting -> "Extracting offline model"
                else -> "Preparing model"
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = VoxynSurface,
                border = BorderStroke(1.dp, VoxynCyan.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "$label ${(progress * 100).toInt()}%",
                        color = VoxynCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = VoxynCyan,
                        trackColor = DIM_COLOR.copy(alpha = 0.3f)
                    )
                }
            }
        }
        is VoskModelState.Error -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ERROR_COLOR.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ERROR_COLOR.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Offline model failed: ${modelState.message}",
                        color = ERROR_COLOR,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedButton(
                        onClick = onRetry,
                        border = BorderStroke(1.dp, ERROR_COLOR),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "RETRY DOWNLOAD",
                            color = ERROR_COLOR,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
        is VoskModelState.Ready -> Unit
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
fun CommandInput(
    value: String,
    isRunning: Boolean,
<<<<<<< HEAD
=======
    modelReady: Boolean,
    awaitingConfirmation: Boolean,
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStartVoiceInput: () -> Unit,
    onInterrupt: () -> Unit
) {
<<<<<<< HEAD
=======
    val inputEnabled = modelReady && !isRunning

>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = !isRunning,
            placeholder = {
<<<<<<< HEAD
                Text("enter command...", color = DIM_COLOR, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
=======
                val hint = when {
                    !modelReady -> "waiting for offline model..."
                    awaitingConfirmation -> "type yes or no..."
                    else -> "enter command..."
                }
                Text(hint, color = DIM_COLOR, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
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
<<<<<<< HEAD
            OutlinedButton(
                onClick = onStartVoiceInput,
                enabled = !isRunning,
                border = BorderStroke(1.dp, VoxynCyan),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Start voice input", tint = VoxynCyan)
            }
            if (isRunning) {
                OutlinedButton(
                    onClick = onInterrupt,
                    border = BorderStroke(1.dp, ERROR_COLOR),
                    shape = RoundedCornerShape(10.dp)
=======
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSubmit,
                    enabled = modelReady && !isRunning && value.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = VoxynCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
                ) {
                    Text("STOP", color = ERROR_COLOR, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
