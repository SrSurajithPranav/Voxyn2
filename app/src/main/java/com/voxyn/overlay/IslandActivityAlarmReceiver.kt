package com.voxyn.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.voxyn.MainActivity
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class IslandActivityAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.data?.lastPathSegment ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val repository = GlobalContext.get().get<IslandActivityRepository>()
                repository.completeFromAlarm(id)?.let { postCompletionNotification(context, it) }
            } catch (e: Exception) {
                VoxynLogger.e("Island timer/reminder delivery failed: ${e.message}", tag = "OVERLAY")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "voxyn_island_activities"

        fun postCompletionNotification(context: Context, activity: IslandActivity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                VoxynLogger.w("Timer/reminder completed, but notification permission is denied", "OVERLAY")
                return
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VOXYN timers and reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, activity.id.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val label = if (activity.kind == IslandActivityKind.FOCUS_TIMER) "Focus timer complete" else "Reminder"
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.voxyn.R.drawable.ic_voxyn)
                .setContentTitle(label)
                .setContentText(activity.title)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { manager.notify(activity.id.hashCode(), notification) }
            catch (e: SecurityException) { VoxynLogger.w("Could not post timer/reminder notification: ${e.message}", "OVERLAY") }
        }
    }
}
