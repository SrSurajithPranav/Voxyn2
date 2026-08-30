package com.voxyn.di

import com.voxyn.BuildConfig
import com.voxyn.ai.ClaudeGeneralProvider
import com.voxyn.ai.ClaudeIntentResolver
import com.voxyn.ai.HybridGeneralResolver
import com.voxyn.ai.LocalKnowledgeProvider
import com.voxyn.ai.LocalLlmProvider
import com.voxyn.ai.LocalModelManager
import com.voxyn.ai.WorldTimeProvider
import com.voxyn.apps.AppCatalog
import com.voxyn.communication.AndroidContactStore
import com.voxyn.communication.CommunicationPolicy
import com.voxyn.communication.ContactResolver
import com.voxyn.communication.ContactStore
import com.voxyn.communication.MessageComposer
import com.voxyn.engine.VoxynEngine
import com.voxyn.execution.AppLaunchResolver
import com.voxyn.execution.CapabilityRegistry
import com.voxyn.execution.ExecutionController
import com.voxyn.execution.capabilities.AccessibilityCapability
import com.voxyn.execution.capabilities.AppCapability
import com.voxyn.execution.capabilities.CameraCapability
import com.voxyn.execution.capabilities.ClockCapability
import com.voxyn.execution.capabilities.CommunicationCapability
import com.voxyn.execution.capabilities.MediaCapability
import com.voxyn.execution.capabilities.ModeCapability
import com.voxyn.execution.capabilities.NavigationCapability
import com.voxyn.execution.capabilities.SystemCapability
import com.voxyn.intent.IntentRouter
import com.voxyn.memory.MemoryRepository
import com.voxyn.mode.ModeEngine
import com.voxyn.perception.AppNameResolver
import com.voxyn.perception.ContextEngine
import com.voxyn.perception.SpeechNormalizer
import com.voxyn.router.RequestRouter
import com.voxyn.ui.VoxynViewModel
import com.voxyn.voice.ConversationSession
import com.voxyn.voice.ParakeetModelManager
import com.voxyn.voice.SpeechRecognizerEngineProvider
import com.voxyn.voice.TextToSpeechManager
import com.voxyn.voice.VoskModelProvider
import com.voxyn.web.DuckDuckGoSearchProvider
import com.voxyn.web.NewsSearchProvider
import com.voxyn.web.OpenMeteoWeatherProvider
import com.voxyn.web.OsmDistanceProvider
import com.voxyn.web.WikipediaSearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    // ─── Foundation ─────────────────────────────────────────────────────────────
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { MemoryRepository(androidContext()) }
    single { ContextEngine(androidContext()) }

    // ─── Communication & Messaging ─────────────────────────────────────────────
    single<ContactStore> { AndroidContactStore(androidContext().contentResolver) }
    single { ContactResolver(get()) }
    single { CommunicationPolicy(androidContext()) }
    single { MessageComposer(androidContext(), get()) }

    // ─── Voice & TTS ───────────────────────────────────────────────────────────
    single { TextToSpeechManager(androidContext()) }
    single { ConversationSession() }
    single { VoskModelProvider(androidContext()) }
    single { ParakeetModelManager(androidContext()) }
    single { SpeechRecognizerEngineProvider(androidContext(), get(), get(), get()) }

    // ─── Perception & Routing ───────────────────────────────────────────────────
    single { SpeechNormalizer(get()) }
    single { RequestRouter() }

    // ─── Web / Current Information ─────────────────────────────────────────────
    single { WikipediaSearchProvider() }
    single { NewsSearchProvider() }
    single { DuckDuckGoSearchProvider() }
    single<com.voxyn.web.WeatherProvider> { OpenMeteoWeatherProvider() }
    single<com.voxyn.web.DistanceProvider> { OsmDistanceProvider() }

    // ─── AI / Knowledge / Current Information ───────────────────────────────────
    single { WorldTimeProvider() }
    single { LocalKnowledgeProvider() }
    single { LocalModelManager(androidContext()) }
    single { LocalLlmProvider(androidContext(), get()) }

    // ─── Hybrid general intelligence: local → web → cloud ───────────────────────
    single<com.voxyn.ai.GeneralQueryResolver> {
        HybridGeneralResolver(
            localLlm        = get(),
            localKnowledge  = get(),
            worldTime       = get(),
            wikipedia       = get(),
            news            = get(),
            duckDuckGo      = get(),
            weather         = get(),
            distance        = get(),
            session         = get(),
            cloud           = BuildConfig.CLAUDE_API_KEY.takeIf { it.isNotBlank() }?.let { ClaudeGeneralProvider(it) }
        )
    }

    // ─── Intent ────────────────────────────────────────────────────────────────
    single { IntentRouter() }

    // ─── Mode ──────────────────────────────────────────────────────────────────
    single { ModeEngine(androidContext(), get()) }

    // ─── Application Resolver ──────────────────────────────────────────────────
    single { AppCatalog(androidContext()) }
    single<AppNameResolver> { get<AppCatalog>() }
    single { AppLaunchResolver(androidContext(), get()) }

    // ─── Capabilities ──────────────────────────────────────────────────────────
    single { SystemCapability(androidContext()) }
    single { AppCapability(get()) }
    single { CommunicationCapability(androidContext(), get(), get()) }
    single { MediaCapability(androidContext()) }
    single { ClockCapability(androidContext()) }
    single { NavigationCapability(androidContext()) }
    single { AccessibilityCapability(androidContext()) }
    single { ModeCapability(get()) }
    single { CameraCapability(androidContext()) }

    single {
        CapabilityRegistry(
            listOf(
                get<SystemCapability>(),
                get<AppCapability>(),
                get<CommunicationCapability>(),
                get<MediaCapability>(),
                get<ClockCapability>(),
                get<NavigationCapability>(),
                get<AccessibilityCapability>(),
                get<ModeCapability>(),
                get<CameraCapability>()
            )
        )
    }

    // ─── Execution ─────────────────────────────────────────────────────────────
    single { ExecutionController(get()) }

    // ─── Engine ────────────────────────────────────────────────────────────────
    single {
        VoxynEngine(
            scope          = get(),
            context        = androidContext(),
            memory         = get(),
            intentRouter   = get(),
            executor       = get(),
            tts            = get(),
            conversationSession = get(),
            speechNormalizer = get(),
            requestRouter    = get(),
            worldTimeProvider = get(),
            generalQuery     = get(),
            localKnowledge   = get(),
            claudeResolver = BuildConfig.CLAUDE_API_KEY.takeIf { it.isNotBlank() }?.let { ClaudeIntentResolver(it) },
            modeEngine     = get(),
            contactResolver = get()
        )
    }

    // ─── UI ────────────────────────────────────────────────────────────────────
    viewModel { VoxynViewModel(engine = get(), localModelManager = get(), parakeetModelManager = get()) }
}
