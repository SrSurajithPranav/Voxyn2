package com.voxyn.di

import com.voxyn.ai.ClaudeIntentResolver
import com.voxyn.engine.VoxynEngine
import com.voxyn.execution.ExecutionController
import com.voxyn.intent.IntentRouter
import com.voxyn.memory.MemoryRepository
import com.voxyn.perception.ContextEngine
import com.voxyn.ui.VoxynViewModel
import com.voxyn.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { MemoryRepository(androidContext()) }
    single { IntentRouter() }
    single { ExecutionController(androidContext()) }
    single { ContextEngine(androidContext()) }
    single { TextToSpeechManager(androidContext()) }

    single {
        ClaudeIntentResolver(
            "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"
        )
    }

    single {
        VoxynEngine(
            scope          = get(),
            context        = androidContext(),
            memory         = get(),
            intentRouter   = get(),
            executor       = get(),
            tts            = get(),
            claudeResolver = get()
        )
    }

    viewModel { VoxynViewModel(engine = get()) }
}
