# VOXYN Testing Strategy
**Status: Prompt 2 Deliverable — Evidence-Based Blueprint (No source code modified)**

Grounded in actual inspection of `VoxynEngine.kt`, `Contracts.kt`, `Capability.kt`, and the reference project test suites.

---

## Goals

1. **Pipeline determinism:** Given the same input and mocked dependencies, the pipeline must produce the exact same output every time.
2. **Regression safety:** No change to any pipeline phase should silently affect another phase.
3. **Offline verifiability:** All unit tests must run without network access.
4. **Anti-hallucination verifiability:** Test suite explicitly checks that consequential actions are never performed with LLM-fabricated parameters.

---

## Testing Pyramid

```
                      ┌─────────────────────────────┐
                      │  E2E / Instrumented (Slow)  │  ~5%
                      │  - Full pipeline on device  │
                      │  - Overlay rendering        │
                      └─────────────────────────────┘
              ┌───────────────────────────────────────────┐
              │     Integration Tests (Medium)            │  ~25%
              │     - VoxynEngine with mock capabilities  │
              │     - ProviderFallbackChain with mock HTTP│
              │     - MemoryRepository (Room in-memory)   │
              └───────────────────────────────────────────┘
  ┌─────────────────────────────────────────────────────────────┐
  │                  Unit Tests (Fast, Isolated)                │  ~70%
  │  - Individual phase functions (perceive, resolveIntent, ...) │
  │  - MessageGrounding, ContextualMemoryExtractor              │
  │  - ProviderFallbackChain cooldown logic                     │
  │  - KaldiFbank, SpeakerEmbedder (with known audio fixture)   │
  │  - VoiceSessionCoordinator echo bleed timing                │
  └─────────────────────────────────────────────────────────────┘
```

---

## 1. Unit Tests — Per Phase

### Phase: PERCEPTION — SpeechNormalizer

```kotlin
class SpeechNormalizerTest {
    @Test fun `phonetic app name resolved`() {
        assertEquals("com.whatsapp", normalizer.resolveAppName("what's app"))
    }
    @Test fun `number words normalized`() {
        assertEquals("call 207", normalizer.normalize("call two zero seven"))
    }
    @Test fun `shell injection rejected`() {
        assertNull(normalizer.normalize("open $(rm -rf /)"))
    }
}
```

### Phase: INTENT — LearnedCommandStore

```kotlin
class LearnedCommandStoreTest {
    @Test fun `returns null for unknown phrase`() = runTest {
        assertNull(store.match("open spotify"))
    }
    @Test fun `returns cached step for known phrase`() = runTest {
        store.cache("open spotify", openSpotifyStep)
        assertEquals(openSpotifyStep, store.match("open spotify"))
    }
    @Test fun `normalized form used for match`() = runTest {
        store.cache("open spotify", openSpotifyStep)
        assertEquals(openSpotifyStep, store.match("OPEN SPOTIFY"))
    }
}
```

### Phase: INTENT — ProviderFallbackChain

```kotlin
class ProviderFallbackChainTest {
    @Test fun `skips provider on active cooldown`() = runTest {
        chain.markCooldown("GroqFastProvider", 60_000)
        val used = mutableListOf<String>()
        chain.resolveIntent("test", "", recordingCallback = { used += it })
        assertFalse("GroqFastProvider" in used)
    }
    @Test fun `falls back to Claude when Groq returns 429`() = runTest {
        groqProvider.stub { Result.failure(Http429Exception()) }
        claudeProvider.stub { Result.success(intentResult("OPEN_APP")) }
        val result = chain.resolveIntent("open spotify", "")
        assertTrue(result.isSuccess)
        assertEquals(IntentSource.CLAUDE, result.getOrNull()?.resolvedBy)
    }
    @Test fun `local provider used first when available`() = runTest {
        localProvider.stub { Result.success(intentResult("OPEN_APP")) }
        val result = chain.resolveIntent("open spotify", "")
        assertEquals(IntentSource.LOCAL_LLM, result.getOrNull()?.resolvedBy)
        verify(groqProvider, never()).resolveIntent(any(), any())
    }
}
```

### Phase: PLAN — MessageGrounding

```kotlin
class MessageGroundingTest {
    @Test fun `grounded when message is substring of input`() {
        assertTrue(MessageGrounding.isGroundedInSpokenPrompt(
            messageText = "happy birthday",
            rawSpokenInput = "send Alice happy birthday"
        ))
    }
    @Test fun `not grounded when message not in input`() {
        assertFalse(MessageGrounding.isGroundedInSpokenPrompt(
            messageText = "I hope you're doing well",
            rawSpokenInput = "message Bob hey"
        ))
    }
    @Test fun `word overlap >= 0_75 passes`() {
        assertTrue(MessageGrounding.isGroundedInSpokenPrompt(
            messageText = "running late be there soon",
            rawSpokenInput = "text Sarah I am running a bit late and will be there soon"
        ))
    }
}
```

### Phase: EXECUTE — Capability

```kotlin
class SendMessageCapabilityTest {
    @Test fun `verify returns Success when message in sent box`() = runTest {
        mockSentBox.hasMessage("Hello Bob")
        val outcome = capability.verify(step("SEND_MESSAGE", "Bob", "Hello Bob"), "sent")
        assertEquals(VerificationOutcome.Success, outcome)
    }
    @Test fun `execute does not proceed without SEND_SMS permission`() = runTest {
        mockPermissions.deny("SEND_SMS")
        val result = capability.execute(step("SEND_MESSAGE", "Bob", "Hello"))
        assertTrue(result is PhaseResult.Failure)
    }
}
```

### Phase: SECURITY — CommandVerificationGate

```kotlin
class CommandVerificationGateTest {
    @Test fun `same speaker passes gate`() {
        val enrolledEmbedding = testEmbedding("alice_enroll.pcm")
        val queryEmbedding    = testEmbedding("alice_query.pcm")
        assertTrue(gate.verify(enrolledEmbedding, queryEmbedding))
    }
    @Test fun `different speaker fails gate`() {
        val enrolledEmbedding = testEmbedding("alice_enroll.pcm")
        val queryEmbedding    = testEmbedding("bob_query.pcm")
        assertFalse(gate.verify(enrolledEmbedding, queryEmbedding))
    }
    @Test fun `gate not applied to LOW_RISK actions`() {
        val result = gate.shouldVerify("OPEN_APP")
        assertFalse(result)
    }
    @Test fun `gate applied to SEND_MESSAGE`() {
        val result = gate.shouldVerify("SEND_MESSAGE")
        assertTrue(result)
    }
}
```

### Phase: MEMORY — ContextualMemoryExtractor

```kotlin
class ContextualMemoryExtractorTest {
    @Test fun `extracts name from introduction`() {
        val facts = extractor.extract("My name is Surajith")
        assertEquals("Surajith", facts["name"])
    }
    @Test fun `extracts preference`() {
        val facts = extractor.extract("I prefer dark mode")
        assertEquals("dark mode", facts["preference.mode"])
    }
    @Test fun `no false positive on generic statement`() {
        val facts = extractor.extract("The weather is nice today")
        assertTrue(facts.isEmpty())
    }
    @Test fun `confidence below threshold not stored`() {
        val facts = extractor.extract("maybe I like coffee sometimes")
        assertTrue(facts.isEmpty())
    }
}
```

### Phase: VOICE — Echo Bleed Window

```kotlin
class VoiceSessionCoordinatorTest {
    @Test fun `result suppressed within echo bleed window`() {
        coordinator.onTtsStarted()
        val results = mutableListOf<String>()
        coordinator.onFinalResult("tts echo", callback = { results += it })
        assertTrue(results.isEmpty())
    }
    @Test fun `result accepted after echo bleed window`() = runTest {
        coordinator.onTtsStarted()
        advanceTimeBy(400) // > 300ms
        val results = mutableListOf<String>()
        coordinator.onFinalResult("real user input", callback = { results += it })
        assertEquals(listOf("real user input"), results)
    }
}
```

---

## 2. Integration Tests — Full Pipeline Slices

```kotlin
class VoxynEngineIntegrationTest {

    // Full pipeline with all real components except capabilities (mocked)
    private val engine = VoxynEngine(
        scope = TestScope(),
        context = ApplicationProvider.getApplicationContext(),
        memory = InMemoryMemoryRepository(),
        intentRouter = RealIntentRouter(),
        executor = MockExecutionController(),  // Capabilities are mocked
        tts = MockTextToSpeechManager(),
        conversationSession = ConversationSession(),
        speechNormalizer = RealSpeechNormalizer(),
        requestRouter = RealRequestRouter(),
        worldTimeProvider = MockWorldTimeProvider(),
        generalQuery = MockGeneralQueryResolver(),
        localKnowledge = RealLocalKnowledgeProvider(),
        claudeResolver = null,
        contactResolver = MockContactResolver()
    )

    @Test fun `OPEN_APP command completes full pipeline`() = runTest {
        val events = mutableListOf<PipelineEvent>()
        engine.events.collect { events += it }

        engine.process("open spotify")

        assertTrue(events.any { it is PipelineEvent.PhaseStarted && it.phase == PipelinePhase.INPUT })
        assertTrue(events.any { it is PipelineEvent.PhaseStarted && it.phase == PipelinePhase.EXECUTE })
        assertTrue(events.any { it is PipelineEvent.VerificationResult })
        assertTrue(events.any { it is PipelineEvent.PipelineComplete })
    }

    @Test fun `HIGH_RISK action requires confirmation before execute`() = runTest {
        val events = mutableListOf<PipelineEvent>()
        engine.events.collect { events += it }

        engine.process("send message to Alice saying hello")

        assertTrue(events.any { it is PipelineEvent.ConfirmationRequired })
        assertFalse(events.any { it is PipelineEvent.ExecutionStepStarted })
    }

    @Test fun `anti-hallucination: fabricated message body is rejected`() = runTest {
        val events = mutableListOf<PipelineEvent>()
        engine.events.collect { events += it }

        // LLM returns a message body not spoken by user
        mockLlm.stub { IntentResult("SEND_MESSAGE",
            mapOf("recipient" to "Alice", "message" to "I hope you are well"),
            rawInput = "text Alice") }

        engine.process("text Alice")

        // No execution step for SEND_MESSAGE should have been started
        assertFalse(events.any { it is PipelineEvent.ExecutionStepStarted })
    }

    @Test fun `unknown command falls back through provider chain`() = runTest {
        localProvider.stub { Result.failure(Exception("model not loaded")) }
        groqProvider.stub { Result.success(IntentResult("GENERAL", rawInput = "how far is moon")) }

        engine.process("how far is the moon")

        // General resolver handles this, no crash
        assertTrue(events.any { it is PipelineEvent.PipelineComplete })
    }
}
```

---

## 3. Instrumented Tests (On-Device)

```kotlin
@RunWith(AndroidJUnit4::class)
class DynamicIslandInstrumentedTest {
    @Test fun `overlay renders for each IslandState`() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val island = DynamicIsland(context, windowManager, layoutParams)
            IslandState.values().forEach { state ->
                island.setState(state, "test")
                island.draw(Canvas(Bitmap.createBitmap(300, 40, Bitmap.Config.ARGB_8888)))
                // No exception = pass
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryInstrumentedTest {
    @Test fun `learned command persists across Room session`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, VoxynDatabase::class.java).build()
        val repo = MemoryRepository(db)
        repo.cacheCommand("open spotify", openSpotifyStep)
        val found = repo.matchLearned("open spotify")
        assertEquals(openSpotifyStep.actionType, found?.actionType)
    }
}
```

---

## 4. Static Analysis & Build Gates

### Mandatory CI Checks

```bash
# 1. Compile and unit test
./gradlew :app:test

# 2. Lint
./gradlew :app:lintDebug

# 3. Assemble debug APK
./gradlew :app:assembleDebug

# 4. Grep for println (must be zero results)
grep -rn "println(" app/src/main/java/com/voxyn/ && exit 1 || echo "PASS: No println found"

# 5. Grep for hardcoded API keys (must be zero results)
grep -rn "sk-ant\|gsk_\|AIzaSy" app/src/main/java/com/voxyn/ && exit 1 || echo "PASS: No keys found"
```

---

## 5. Test Coverage Targets

| Package | Target Coverage | Priority |
|:---|:---:|:---|
| `com.voxyn.engine` | 80% | Critical — pipeline correctness |
| `com.voxyn.ai` | 75% | High — provider fallback |
| `com.voxyn.security` | 85% | Critical — speaker gate must not pass wrong speaker |
| `com.voxyn.memory` | 75% | High — learned cache correctness |
| `com.voxyn.execution` | 70% | High — capability contracts |
| `com.voxyn.voice` | 60% | Medium — hardware-dependent |
| `com.voxyn.overlay` | 40% | Low — visual, use instrumented |

---

## 6. Test Data & Fixtures

| Fixture | Location | Purpose |
|:---|:---|:---|
| `alice_enroll.pcm` | `test/assets/audio/` | 3 seconds of alice voice for enrollment |
| `alice_query.pcm` | `test/assets/audio/` | 1 second query clip — should pass gate |
| `bob_query.pcm` | `test/assets/audio/` | 1 second different speaker — should fail gate |
| `tts_echo.pcm` | `test/assets/audio/` | Simulated TTS output to test echo bleed suppression |

All test audio fixtures must be synthetic (no real personal voice data committed to repository).

---

## 7. Non-Negotiable Testing Rules

1. **No `println`** — all test logging uses `VoxynLogger` or `Log` with a test tag.
2. **No network calls in unit tests** — all HTTP clients mocked.
3. **No real credentials** — all API key fields use `BuildConfig.TEST_KEY = "test_key"` placeholder.
4. **Deterministic timing** — use `TestCoroutineScheduler.advanceTimeBy()` for all time-dependent tests; never use `Thread.sleep()`.
5. **Test names are sentences** — backtick function names describe expected behavior clearly.
6. **Every new capability gets a test** — PRs without corresponding capability tests will not be accepted.
