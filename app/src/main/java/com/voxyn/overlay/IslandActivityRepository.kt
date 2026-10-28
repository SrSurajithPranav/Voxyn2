package com.voxyn.overlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

enum class IslandActivityKind { FOCUS_TIMER, REMINDER }
data class IslandActivity(val id: String, val title: String, val dueAtMillis: Long, val kind: IslandActivityKind)

interface IslandActivityAlarmScheduler {
    fun schedule(activity: IslandActivity)
    fun cancel(id: String)
    fun notifyCompletion(activity: IslandActivity) = Unit
}

internal val ISLAND_ACTIVITIES = stringPreferencesKey("island_scheduled_activities")

/** Persisted timers and reminders. Alarms are intentionally inexact to avoid special exact-alarm access. */
class IslandActivityRepository(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val scheduler: IslandActivityAlarmScheduler,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val _activities = MutableStateFlow<List<IslandActivity>>(emptyList())
    val activities: StateFlow<List<IslandActivity>> = _activities.asStateFlow()

    init {
        scope.launch { dataStore.data.collect { _activities.value = decode(it[ISLAND_ACTIVITIES].orEmpty()) } }
    }

    suspend fun schedule(kind: IslandActivityKind, title: String, delayMinutes: Int): IslandActivity? {
        val cleanTitle = title.trim().take(MAX_TITLE_LENGTH)
        if (cleanTitle.isEmpty() || delayMinutes !in 1..MAX_DELAY_MINUTES) return null
        val item = IslandActivity(UUID.randomUUID().toString(), cleanTitle, nowMillis() + delayMinutes * 60_000L, kind)
        dataStore.edit { prefs -> prefs[ISLAND_ACTIVITIES] = encode(decode(prefs[ISLAND_ACTIVITIES].orEmpty()) + item) }
        return try {
            scheduler.schedule(item)
            item
        } catch (e: Exception) {
            cancel(item.id)
            VoxynLogger.e("Could not schedule island activity: ${e.message}", tag = "OVERLAY")
            null
        }
    }

    suspend fun cancel(id: String): Boolean {
        var removed = false
        dataStore.edit { prefs ->
            val current = decode(prefs[ISLAND_ACTIVITIES].orEmpty())
            removed = current.any { it.id == id }
            if (removed) prefs[ISLAND_ACTIVITIES] = encode(current.filterNot { it.id == id })
        }
        if (removed) runCatching { scheduler.cancel(id) }
        return removed
    }

    /** Called only by the alarm receiver; expired or cancelled IDs cannot fire twice. */
    suspend fun completeFromAlarm(id: String): IslandActivity? {
        val now = nowMillis()
        var item: IslandActivity? = null
        var early: IslandActivity? = null
        dataStore.edit { prefs ->
            val current = decode(prefs[ISLAND_ACTIVITIES].orEmpty())
            val found = current.firstOrNull { it.id == id } ?: return@edit
            if (now < found.dueAtMillis) {
                early = found
            } else {
                item = found
                prefs[ISLAND_ACTIVITIES] = encode(current.filterNot { it.id == id })
            }
        }
        if (early != null) {
            val activity = early ?: return null
            runCatching { scheduler.schedule(activity) }
            return null
        }
        val completed = item ?: return null
        runCatching { scheduler.cancel(id) }
        return completed
    }

    fun reschedulePending() {
        scope.launch {
            decode(dataStore.data.first()[ISLAND_ACTIVITIES].orEmpty()).forEach { activity ->
                if (activity.dueAtMillis <= nowMillis()) completeFromAlarm(activity.id)
                    ?.let(scheduler::notifyCompletion)
                else runCatching { scheduler.schedule(activity) }
            }
        }
    }

    private fun encode(items: List<IslandActivity>): String = items.joinToString(",") { item ->
        val title = Base64.getUrlEncoder().withoutPadding().encodeToString(item.title.toByteArray(StandardCharsets.UTF_8))
        "${item.id}:$title:${item.dueAtMillis}:${item.kind.name}"
    }

    private fun decode(value: String): List<IslandActivity> = value.split(',').mapNotNull { row ->
        val parts = row.split(':', limit = 4)
        if (parts.size != 4) return@mapNotNull null
        runCatching {
            IslandActivity(parts[0], String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), parts[2].toLong(), IslandActivityKind.valueOf(parts[3]))
        }.getOrNull()
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 100
        const val MAX_DELAY_MINUTES = 10_080 // one week
    }
}

class AndroidIslandActivityAlarmScheduler(private val context: Context) : IslandActivityAlarmScheduler {
    override fun schedule(activity: IslandActivity) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activity.dueAtMillis, pendingIntent(activity.id))
    }

    override fun cancel(id: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntent(id))
    }

    override fun notifyCompletion(activity: IslandActivity) {
        IslandActivityAlarmReceiver.postCompletionNotification(context, activity)
    }

    private fun pendingIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, IslandActivityAlarmReceiver::class.java).setData(Uri.parse("voxyn-activity://activity/$id")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
