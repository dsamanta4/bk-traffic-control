package org.brahmakumaris.trafficcontrol.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs
import java.util.concurrent.TimeUnit

/**
 * Defense in depth, not the primary mechanism. setAlarmClock() alarms are
 * very hard to kill, but some OEM "battery managers" (MIUI in particular)
 * are known to silently cancel scheduled alarms for apps the user hasn't
 * whitelisted, with no callback to the app. This worker wakes up roughly
 * every 15 minutes (the minimum period WorkManager allows) and re-arms any
 * enabled slot whose PendingIntent has disappeared.
 *
 * It cannot fully substitute for AlarmManager (WorkManager itself can be
 * deferred by Doze), which is exactly why the OEM whitelist setup screen
 * still matters - this worker just shortens the blast radius if an alarm
 * does get cancelled out from under us.
 */
class AlarmRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val scheduler = AlarmScheduler(applicationContext)
        val prefs = SchedulePrefs(applicationContext)
        for (slot in Schedule.slots) {
            if (prefs.isSlotEnabled(slot.id) && !scheduler.isScheduled(slot.id)) {
                scheduler.scheduleNextOccurrence(slot.id, slot.hour, slot.minute)
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "alarm_refresh_worker"

        fun enable(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlarmRefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
