package com.ultron.ai.platform.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ultron.ai.R
import com.ultron.ai.domain.ActionResult

/** Set by MainActivity: true while an ULTRON screen is visible. */
object AppVisibility { @Volatile var visible = false }

/**
 * Android 10+ silently blocks startActivity() from a background app (e.g. while ULTRON listens in its service),
 * and does NOT throw. Starting an activity is allowed when ULTRON is visible or holds the "display over other apps"
 * permission. Otherwise the only honest way is a tap-to-open notification, and the result says so.
 */
object BackgroundLaunch {
    private const val CHANNEL = "ultron_actions"

    fun canStartDirectly(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || AppVisibility.visible || Settings.canDrawOverlays(context)

    fun launch(context: Context, intent: Intent, label: String, doneText: String): ActionResult {
        val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (canStartDirectly(context)) {
            context.startActivity(i)
            return ActionResult(true, doneText)
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
            return ActionResult(false, "Background mein Android app kholne nahi deta. Settings → ULTRON → \"Display over other apps\" allow karo, ya notifications on karo.")
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "ULTRON actions", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, label.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ULTRON").setContentText("$label kholne ke liye tap karo")
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        return try {
            nm.notify(label.hashCode(), n)
            ActionResult(true, "Android background se app seedha nahi kholne deta. Notification aayi hai: $label kholne ke liye usse tap karo. (Seedha kholne ke liye Settings mein \"Display over other apps\" allow karo.)")
        } catch (e: SecurityException) { ActionResult(false, "Notification permission nahi hai.") }
    }
}
