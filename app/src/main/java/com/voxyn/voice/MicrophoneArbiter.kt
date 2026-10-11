package com.voxyn.voice

/** Serializes access to the device microphone across wake and command recognition. */
class MicrophoneArbiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val commandLeaseMs: Long = 90_000L
) {
    enum class Owner { NONE, WAKE, COMMAND, ENROLLMENT }

    private val lock = Any()
    private var owner = Owner.NONE
    private var leaseExpiresAt = 0L

    val currentOwner: Owner
        get() = synchronized(lock) {
            expireCommandLeaseIfNeeded()
            owner
        }

    fun acquire(requested: Owner): Boolean = synchronized(lock) {
        require(requested != Owner.NONE) { "NONE cannot acquire the microphone" }
        expireCommandLeaseIfNeeded()
        if (owner != Owner.NONE && owner != requested) return false
        owner = requested
        leaseExpiresAt = if (requested == Owner.COMMAND) clock() + commandLeaseMs else 0L
        true
    }

    fun release(releasing: Owner): Boolean = synchronized(lock) {
        if (owner != releasing) return false
        owner = Owner.NONE
        leaseExpiresAt = 0L
        true
    }

    private fun expireCommandLeaseIfNeeded() {
        if (owner == Owner.COMMAND && leaseExpiresAt > 0L && clock() >= leaseExpiresAt) {
            owner = Owner.NONE
            leaseExpiresAt = 0L
        }
    }
}
