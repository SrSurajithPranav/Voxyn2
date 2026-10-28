package com.voxyn.overlay

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voxyn.utils.VoxynLogger
import org.koin.android.ext.android.inject

class IslandMediaSessionService : NotificationListenerService() {
    private val repository: IslandMediaRepository by inject()
    private var manager: MediaSessionManager? = null
    private var activeListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var current: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = refreshCurrent()
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) = refreshCurrent()
        override fun onSessionDestroyed() = refreshSessions()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        repository.updateAccess(true)
        try {
            manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            activeListener = MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions(it.orEmpty()) }
            manager?.addOnActiveSessionsChangedListener(activeListener!!, ComponentName(this, javaClass))
            refreshSessions()
        } catch (e: SecurityException) {
            repository.updateAccess(false)
            VoxynLogger.w("Media sessions unavailable: notification access was revoked", "OVERLAY")
        } catch (e: Exception) {
            repository.updateSession(null)
            VoxynLogger.e("Media session connection failed: ${e.message}", tag = "OVERLAY")
        }
    }

    override fun onListenerDisconnected() {
        unregister()
        repository.updateAccess(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregister()
        repository.updateSession(null)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    private fun refreshSessions(sessions: List<MediaController>? = null) {
        try {
            val active = sessions ?: manager?.getActiveSessions(ComponentName(this, javaClass)).orEmpty()
            val selected = active.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                ?: active.firstOrNull { it.metadata != null }
            if (current !== selected) {
                current?.unregisterCallback(controllerCallback)
                current = selected
                current?.registerCallback(controllerCallback)
            }
            repository.updateSession(selected)
        } catch (e: SecurityException) {
            repository.updateAccess(false)
        } catch (e: Exception) {
            repository.updateSession(null)
            VoxynLogger.w("Could not refresh active media session: ${e.message}", "OVERLAY")
        }
    }

    private fun refreshCurrent() = refreshSessions()

    private fun unregister() {
        try { activeListener?.let { manager?.removeOnActiveSessionsChangedListener(it) } } catch (_: Exception) {}
        try { current?.unregisterCallback(controllerCallback) } catch (_: Exception) {}
        activeListener = null
        current = null
        manager = null
    }
}
