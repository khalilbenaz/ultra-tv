package com.ultratv.tv.nativeapp.data.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Schedules + fires programme reminders. The DB is the source of truth; this
 * helper turns rows into AlarmManager pending intents and re-arms them on
 * boot (BootReceiver hooks rescheduleAll).
 *
 * Notification channel: `reminders` (importance HIGH). Fire-time intent is
 * received by [ReminderReceiver], which posts the notification and removes
 * the entry from the DB.
 */
@Singleton
class RemindersScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: ReminderDao,
) {
    private val am = ctx.getSystemService<AlarmManager>()!!
    private val nm = ctx.getSystemService<NotificationManager>()!!

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Programme reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Notification 60 s before a scheduled programme starts" }
            nm.createNotificationChannel(ch)
        }
    }

    suspend fun add(reminder: ReminderEntity): Long {
        val id = dao.upsert(reminder)
        schedule(reminder.copy(id = id))
        return id
    }

    suspend fun cancel(id: Long) {
        val r = dao.byId(id) ?: return
        am.cancel(pendingFor(r))
        dao.deleteById(id)
    }

    suspend fun rescheduleAll() {
        dao.deleteExpired()
        dao.upcoming().forEach { schedule(it) }
    }

    private fun schedule(r: ReminderEntity) {
        val triggerAt = (r.startMs - 60_000L).coerceAtLeast(System.currentTimeMillis() + 1_000)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingFor(r))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingFor(r))
        }
    }

    private fun pendingFor(r: ReminderEntity): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, r.id)
            putExtra(EXTRA_TITLE, r.programmeTitle)
            putExtra(EXTRA_CHANNEL, r.channelName)
            putExtra(EXTRA_START, r.startMs)
        }
        val flags = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, r.id.toInt(), intent, flags)
    }

    fun postFireNotification(id: Long, channel: String, title: String) {
        val openApp = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val tap = if (openApp != null) PendingIntent.getActivity(
            ctx, id.toInt(), openApp,
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0) or
                PendingIntent.FLAG_UPDATE_CURRENT,
        ) else null
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setContentTitle(channel)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .also { if (tap != null) it.setContentIntent(tap) }
            .build()
        nm.notify(id.toInt(), n)
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val ACTION_FIRE = "com.ultratv.tv.nativeapp.REMINDER_FIRE"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_START = "start"
    }
}

/**
 * AlarmManager dispatch target. Posts the notification and clears the DB row.
 */
@dagger.hilt.android.AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: RemindersScheduler
    @Inject lateinit var dao: ReminderDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RemindersScheduler.ACTION_FIRE) return
        val id = intent.getLongExtra(RemindersScheduler.EXTRA_ID, -1L)
        val title = intent.getStringExtra(RemindersScheduler.EXTRA_TITLE).orEmpty()
        val channel = intent.getStringExtra(RemindersScheduler.EXTRA_CHANNEL).orEmpty()
        scheduler.postFireNotification(id, channel, title)
        // Async cleanup — goAsync() keeps the process alive while we delete the
        // row. Use a job scoped to this receiver (SupervisorJob + IO) instead of
        // GlobalScope so the work is structured and always finishes the pending
        // result (even on failure), rather than leaking an unsupervised
        // coroutine onto the global scope.
        val pending = goAsync()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        scope.launch {
            try {
                dao.deleteById(id)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
