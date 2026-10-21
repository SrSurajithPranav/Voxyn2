package com.voxyn.engine

import com.voxyn.intent.IntentRouter
import com.voxyn.perception.AppNameResolver
import com.voxyn.perception.SpeechNormalizer
import com.voxyn.router.RequestRouter
import com.voxyn.router.RouteType
import com.voxyn.security.CommandVerificationGate
import com.voxyn.security.GateResult
import com.voxyn.security.InputSource
import com.voxyn.security.SpeakerProfile
import com.voxyn.security.SpeakerProfileRepository
import com.voxyn.security.SpeakerVerifier
import com.voxyn.voice.VoiceChoice
import com.voxyn.wake.WakeWordService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceCommandPlanVerificationTest {

    private lateinit var normalizer: SpeechNormalizer
    private lateinit var intentRouter: IntentRouter
    private lateinit var requestRouter: RequestRouter
    private lateinit var gate: CommandVerificationGate
    private lateinit var verifier: SpeakerVerifier
    private lateinit var repository: SpeakerProfileRepository
    private lateinit var tempFolder: File

    @Before
    fun setup() {
        val fakeResolver = object : AppNameResolver {
            override fun resolveLabel(spoken: String): String? = when (spoken.lowercase()) {
                "youtube", "you tube", "you do" -> "YouTube"
                "whatsapp", "whats app" -> "WhatsApp"
                else -> null
            }
        }
        normalizer = SpeechNormalizer(fakeResolver)
        intentRouter = IntentRouter()
        requestRouter = RequestRouter()

        tempFolder = File.createTempFile("test_plan_sec", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        repository = SpeakerProfileRepository(baseDir = tempFolder)
        verifier = SpeakerVerifier(repository)
        gate = CommandVerificationGate(verifier, repository)
    }

    @Test
    fun `Test 1 - Hey VOXYN open YouTube`() {
        val rawInput = "Hey VOXYN, open YouTube."
        assertTrue(WakeWordService.isWakeTrigger(rawInput))

        val normalized = normalizer.normalize(rawInput)
        assertEquals("open YouTube", normalized)

        val intent = intentRouter.route(normalized)
        assertEquals("OPEN_APP", intent.action)
        assertEquals("youtube", intent.params["app_name"]?.lowercase())
    }

    @Test
    fun `Test 2 - Hey VOXYN turn on Bluetooth`() {
        val rawInput = "Hey VOXYN, turn on Bluetooth."
        assertTrue(WakeWordService.isWakeTrigger(rawInput))

        val normalized = normalizer.normalize(rawInput)
        assertEquals("turn on bluetooth", normalized)

        val intent = intentRouter.route(normalized)
        assertEquals("BLUETOOTH_ON", intent.action)
    }

    @Test
    fun `Test 3 - Hey VOXYN set the volume to thirty percent`() {
        val rawInput = "Hey VOXYN, set the volume to thirty percent."
        assertTrue(WakeWordService.isWakeTrigger(rawInput))

        val normalized = normalizer.normalize(rawInput)
        assertTrue(normalized.contains("30") || normalized.contains("thirty"))

        val intent = intentRouter.route(normalized)
        assertEquals("SET_VOLUME", intent.action)
        assertEquals("30", intent.params["level"])
    }

    @Test
    fun `Test 4 - Hey VOXYN what is the weather today`() {
        val rawInput = "Hey VOXYN, what is the weather today?"
        assertTrue(WakeWordService.isWakeTrigger(rawInput))

        val normalized = normalizer.normalize(rawInput)
        val route = requestRouter.route(normalized)
        assertEquals(RouteType.WEATHER, route.type)
    }

    @Test
    fun `Test 5 - Longer natural-language request`() {
        val rawInput = "Hey VOXYN, explain quantum computing in simple terms for beginners"
        assertTrue(WakeWordService.isWakeTrigger(rawInput))

        val normalized = normalizer.normalize(rawInput)
        val route = requestRouter.route(normalized)
        assertEquals(RouteType.CONVERSATIONAL, route.type)
    }

    @Test
    fun `Test 9 - Background conversation does not trigger wake word`() {
        assertFalse(WakeWordService.isWakeTrigger("hey mom what is for dinner"))
        assertFalse(WakeWordService.isWakeTrigger("talking to my colleague hey how are you"))
        assertFalse(WakeWordService.isWakeTrigger("okay sounds good see you then"))
        assertFalse(WakeWordService.isWakeTrigger("just wanted to say hi to everyone"))
    }

    @Test
    fun `Test 15 - Enrolled speaker passes verification gate while non-enrolled speaker is denied`() {
        val enrolledTone = ShortArray(16000) { idx ->
            (Math.sin(2.0 * Math.PI * 220.0 * idx / 16000.0) * 8000).toInt().toShort()
        }
        val vp = verifier.extractVoiceprint(enrolledTone)
        repository.enrollProfile(SpeakerProfile(id = "owner", name = "Surya", isOwner = true, voiceprint = vp))

        val authorizedResult = gate.verifyAndAuthorize(
            source = InputSource.VOICE,
            audioSamples = enrolledTone,
            rawTranscript = "open YouTube"
        )
        assertTrue(authorizedResult is GateResult.Authorized)
        assertEquals("Surya", (authorizedResult as GateResult.Authorized).speakerName)

        val intruderTone = ShortArray(16000) { idx ->
            (Math.sin(2.0 * Math.PI * 1800.0 * idx / 16000.0) * 8000).toInt().toShort()
        }
        val deniedResult = gate.verifyAndAuthorize(
            source = InputSource.VOICE,
            audioSamples = intruderTone,
            rawTranscript = "turn on Bluetooth"
        )
        assertTrue(deniedResult is GateResult.Denied)
        assertEquals("UNKNOWN_SPEAKER", (deniedResult as GateResult.Denied).reason)
    }

    @Test
    fun `Test 17 - Ambiguous or unsupported command handling`() {
        val intent = intentRouter.route("teleport me to the moon right now")
        assertEquals("UNKNOWN", intent.action)
    }

    @Test
    fun `Test 20 - Switching between five supported reply voices`() {
        val voices = VoiceChoice.values()
        assertEquals(5, voices.size)

        var currentVoice = VoiceChoice.SYSTEM_DEFAULT
        val cycledList = mutableListOf<VoiceChoice>()
        repeat(5) {
            val nextOrdinal = (currentVoice.ordinal + 1) % voices.size
            currentVoice = VoiceChoice.fromOrdinal(nextOrdinal)
            cycledList.add(currentVoice)
        }

        assertEquals(
            listOf(VoiceChoice.JARVIS, VoiceChoice.FRIDAY, VoiceChoice.CADENCE, VoiceChoice.EXPRESSIVE, VoiceChoice.SYSTEM_DEFAULT),
            cycledList
        )
    }
}
