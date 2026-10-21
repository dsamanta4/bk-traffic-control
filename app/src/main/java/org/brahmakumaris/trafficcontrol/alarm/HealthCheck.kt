package org.brahmakumaris.trafficcontrol.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.TrafficControlApp
import org.brahmakumaris.trafficcontrol.ui.SetupActivity

/**
 * Android will not let any app silently re-grant battery-optimization
 * exemption or the exact-alarm permission to itself - those always require
 * a human tapping a system dialog, by design, no matter what this code
 * does. What this CAN do is notice the moment one of those protections gets
 * silently reverted (an OS update, a "cleaner" app, the user poking around
 * settings) and put a single, unmissable, one-tap-away fix in front of
 * someone who - per the brief - is likely older and not going to notice or
 * go looking for the problem themselves.
 *
 * Deliberately does not try to detect OEM autostart being reverted: no
 * Android API exposes that state for any OEM, so a detector here would
 * just be guessing. That one stays a one-time setup step, not something
 * monitored.
 */
object HealthCheck {

    fun runAndNotify(context: Context) {
        val problem = firstProblem(context)
        val manager = NotificationManagerCompat.from(context)

        if (problem == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val openSetup = PendingIntent.getActivity(
            context, 0,
            Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TrafficControlApp.CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.health_check_title))
            .setContentText(problem)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setContentIntent(openSetup)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - nothing more we can do than
            // what the Setup screen already asks for.
        }
    }

    /** Returns a human-readable description of the first broken protection, or null if fine. */
    private fun firstProblem(context: Context): String? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            return context.getString(R.string.health_check_battery)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                return context.getString(R.string.health_check_exact_alarm)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                return context.getString(R.string.health_check_full_screen)
            }
        }

        return null
    }

    private const val NOTIFICATION_ID = 43
}
