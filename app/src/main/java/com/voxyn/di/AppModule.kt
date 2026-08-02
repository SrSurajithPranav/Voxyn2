package com.voxyn.di

import com.voxyn.engine.VoxynEngine
import com.voxyn.execution.ExecutionController
import com.voxyn.execution.AppLaunchResolver
import com.voxyn.intent.IntentRouter
import com.voxyn.memory.MemoryRepository
import com.voxyn.mode.ModeEngine
import com.voxyn.perception.ContextEngine
import com.voxyn.ui.VoxynViewModel
import com.voxyn.voice.TextToSpeechManager
import com.voxyn.wake.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    // ── Foundation ────────────────────────────────────────────────────
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { MemoryRepository(androidContext()) }
    single { ContextEngine(androidContext()) }

    // ── Voice & TTS ───────────────────────────────────────────────────
    single { TextToSpeechManager(androidContext()) }
    single { VoskModelManager(androidContext()) }

    // ── Intent ────────────────────────────────────────────────────────
    single { IntentRouter() }

    // ── Mode ─────────────────────────────────────────────────────────
    // ModeEngine depends on: context, TextToSpeechManager
    single { ModeEngine(androidContext(), get()) }

    // ── Execution ─────────────────────────────────────────────────────
    // ExecutionController depends on: context, ModeEngine
    single { AppLaunchResolver(androidContext()) }
    single { ExecutionController(androidContext(), get(), get()) }

    // ── AI ────────────────────────────────────────────────────────────
    // ── Engine ────────────────────────────────────────────────────────
    // VoxynEngine depends on: scope, context, memory, intentRouter, executor, tts, claudeResolver, modeEngine
    single {
        VoxynEngine(
            scope          = get(),
<<<<<<< HEAD
            context        = androidContext(),
=======
            appContext     = androidContext(),
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
            memory         = get(),
            intentRouter   = get(),
            executor       = get(),
            tts            = get(),
            claudeResolver = null,
            modeEngine     = get()
        )
    }

<<<<<<< HEAD
    // ── UI ────────────────────────────────────────────────────────────
    viewModel { VoxynViewModel(engine = get()) }
=======
    viewModel { VoxynViewModel(engine = get(), modelManager = get()) }
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
}
