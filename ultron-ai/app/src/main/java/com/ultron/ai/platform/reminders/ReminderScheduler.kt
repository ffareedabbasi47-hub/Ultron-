package com.ultron.ai.platform.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ultron.ai.MainActivity
import com.ultron.ai.R
import com.ultron.ai.UltronApp
import com.ultron.ai.data.db.ReminderDao
import com.ultron.ai.data.db.ReminderEntity
import java.util.concurrent.TimeUnit

data class ScheduleResult(val id: Long, val notificationsEnabled: Boolean)

/**
 * Reminders persisted in the encrypted DB and delivered with WorkManager (survives restarts).
 * Delivery is approximate by design (Doze / battery optimisation); the UI says so.
 */
class ReminderScheduler(private val context: Context, private val dao: ReminderDao) {

    suspend fun schedule(text: String, delayMinutes: Int): ScheduleResult {
        val now = System.currentTimeMillis()
        val trigger = now + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())
        val id = dao.insert(ReminderEntity(text = text, triggerAt = trigger, createdAt = now))
        enqueue(id, trigger - now)
        return ScheduleResult(id, notificationsAllowed(context))
    }

    suspend fun cancel(id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        dao.delete(id)
    }

    suspend fun cancelAll() {
        val wm = WorkManager.getInstance(context)
        dao.pending().forEach { wm.cancelUniqueWork(workName(it.id)) }
        dao.deleteAll()
    }

    private fun enqueue(id: Long, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_ID to id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        fun workName(id: Long) = "reminder-$id"
        fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        if (id < 0) return Result.success()
        val container = (applicationContext as UltronApp).container
        val dao = container.db.reminders()
        val reminder = dao.get(id) ?: return Result.success() // cancelled/deleted
        if (reminder.fired) return Result.success()

        if (ReminderScheduler.notificationsAllowed(applicationContext)) {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ReminderScheduler.CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH),
            )
            val open = PendingIntent.getActivity(
                applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ULTRON reminder")
                .setContentText(reminder.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(applicationContext).notify(id.toInt(), n)
            } catch (_: SecurityException) {
                // permission revoked between the check and notify: leave as unfired
                return Result.success()
            }
        }
        dao.update(reminder.copy(fired = true))
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
    }
}
