package com.voxyn.perception

/**
 * AppNameResolver — the controlled boundary between raw speech and installed
 * apps. The SpeechNormalizer uses this to correct mis-heard app names; it never
 * guesses blindly because the implementation scores against apps actually
 * installed on the device.
 */
interface AppNameResolver {
    /** Best matching installed app label for a spoken (possibly mis-heard) name, or null. */
    fun resolveLabel(spoken: String): String?
}
