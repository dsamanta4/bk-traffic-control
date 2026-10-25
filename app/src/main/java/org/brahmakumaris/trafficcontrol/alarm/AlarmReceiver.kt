package org.brahmakumaris.trafficcontrol.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs
import org.brahmakumaris.trafficcontrol.playback.PlaybackService

/**
 * Fires when a scheduled slot (or the one-off test alarm) is due.
 *
 * Order of operations matters here: we reschedule tomorrow's occurrence of
 * this slot FIRST, before touching playback at all. That way, even if
 * MediaPlayer throws, the service crashes, or the device is yanked off
 * power mid-song, tomorrow's alarm already exists - a single bad playback
 * never cascades into the whole schedule silently stopping.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slotId = intent.getIntExtra(AlarmScheduler.EXTRA_SLOT_ID, Int.MIN_VALUE)
        if (slotId == Int.MIN_VALUE) return

        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrafficControl:AlarmReceiver")
        wakeLock.acquire(20_000L)

        try {
            if (slotId == AlarmScheduler.TEST_SLOT_ID) {
                startPlayback(context, slotId, "Test reminder")
                return
            }

            val slot = Schedule.byId(slotId) ?: return

            // A real-world report: a disabled slot kept firing again a day
            // or two after being turned off. AlarmManager.cancel() can lose
            // a race against an alarm whose broadcast is already in flight
            // the instant someone disables a slot, so this receiver can
            // still run once for a slot the user has since turned off. The
            // old code rescheduled tomorrow's occurrence unconditionally
            // right here, which turned that one stray firing into an
            // unstoppable chain - every subsequent firing rearmed the next
            // one, forever, regardless of the toggle. Checking the current
            // preference before doing anything else, playback included,
            // means a disabled slot's chain actually ends instead of
            // perpetuating itself.
            if (!SchedulePrefs(context).isSlotEnabled(slot.id)) return

            AlarmScheduler(context).scheduleNextOccurrence(slot.id, slot.hour, slot.minute)
            startPlayback(context, slot.id, slot.label)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun startPlayback(context: Context, slotId: Int, label: String) {
        val serviceIntent = Intent(context, PlaybackService::class.java)
            .putExtra(PlaybackService.EXTRA_SLOT_ID, slotId)
            .putExtra(PlaybackService.EXTRA_LABEL, label)
            .putExtra(PlaybackService.EXTRA_IS_REAL_ALARM, true)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
