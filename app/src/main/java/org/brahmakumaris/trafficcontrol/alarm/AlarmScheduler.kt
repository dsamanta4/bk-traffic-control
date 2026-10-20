package org.brahmakumaris.trafficcontrol.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs
import java.util.Calendar

/**
 * Schedules every enabled slot using AlarmManager.setAlarmClock().
 *
 * Why setAlarmClock and not setExactAndAllowWhileIdle:
 * - It is the one AlarmManager API that is *never* deferred by Doze or App
 *   Standby, on every OEM, because the platform treats it exactly like a
 *   clock app's wake-up alarm (it shows the little alarm icon in the status
 *   bar for the same reason).
 * - On Android 12+ it does NOT require the user to grant the "Alarms &
 *   reminders" exact-alarm permission the way setExactAndAllowWhileIdle does
 *   - it always works, which matters a lot for a sideloaded app nobody will
 *     walk through a permissions screen for.
 * This is the single biggest reliability upgrade over a plain
 * setExactAndAllowWhileIdle-based scheduler; everything else in this app
 * (boot/time-set receivers, the WorkManager safety net, the OEM battery
 * whitelist screen) exists to protect this alarm from being cancelled
 * outright, which no AlarmManager flag can prevent on its own.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = SchedulePrefs(context)

    fun rescheduleAll() {
        for (slot in Schedule.slots) {
            if (prefs.isSlotEnabled(slot.id)) {
                scheduleNextOccurrence(slot.id, slot.hour, slot.minute)
            } else {
                cancel(slot.id)
            }
        }
    }

    fun rescheduleSlot(slotId: Int) {
        val slot = Schedule.byId(slotId) ?: return
        if (prefs.isSlotEnabled(slotId)) {
            scheduleNextOccurrence(slot.id, slot.hour, slot.minute)
        } else {
            cancel(slotId)
        }
    }

    /** Called by AlarmReceiver right when today's alarm fires, to queue tomorrow's. */
    fun scheduleNextOccurrence(slotId: Int, hour: Int, minute: Int) {
        val triggerAt = nextOccurrence(hour, minute)
        val showIntent = PendingIntent.getActivity(
            context, 10_000 + slotId,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val firePendingIntent = firingPendingIntent(slotId)

        val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
        try {
            alarmManager.setAlarmClock(info, firePendingIntent)
            Log.i(TAG, "Scheduled slot $slotId for $triggerAt")
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not schedule slot $slotId", e)
        }
    }

    fun scheduleTestAlarmInOneMinute() {
        val triggerAt = System.currentTimeMillis() + 60_000L
        val showIntent = PendingIntent.getActivity(
            context, 10_000 + TEST_SLOT_ID,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val firePendingIntent = firingPendingIntent(TEST_SLOT_ID)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), firePendingIntent)
    }

    fun cancel(slotId: Int) {
        alarmManager.cancel(firingPendingIntent(slotId))
    }

    /** Used by the WorkManager safety-net worker to detect a silently-cancelled alarm. */
    fun isScheduled(slotId: Int): Boolean {
        val pi = PendingIntent.getBroadcast(
            context, slotId,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_SLOT_ID, slotId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        return pi != null
    }

    private fun firingPendingIntent(slotId: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_SLOT_ID, slotId)
        return PendingIntent.getBroadcast(
            context, slotId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        const val ACTION_FIRE = "org.brahmakumaris.trafficcontrol.ACTION_FIRE"
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val TEST_SLOT_ID = -1
    }
}
