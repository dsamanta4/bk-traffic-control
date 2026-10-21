package org.brahmakumaris.trafficcontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.brahmakumaris.trafficcontrol.data.AlarmLog

class TrafficControlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Make sure every enabled slot has a pending alarm as soon as the
        // process is alive for any reason (launcher, boot, or OS-triggered).
        AlarmScheduler(this).rescheduleAll()
        // Catch-up playback for a recently-missed slot is only triggered from
        // AlarmRefreshWorker (runs in a WorkManager-sanctioned background-start
        // context) - not here, since Application.onCreate() can run in contexts
        // (e.g. the worker itself waking the process) that aren't a safe place
        // to start an unrelated foreground service. This call just logs any
        // genuinely old misses; the worker picks up recent ones within 15 min.
        AlarmLog(this).checkForUncovered()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        // A channel's importance is fixed forever once createNotificationChannel()
        // first runs on a device - calling it again with a different importance is
        // silently ignored by Android, it does NOT update the existing channel. Any
        // phone that had an earlier build installed (every phone in this project's
        // testing, for one) would be permanently stuck on the old importance unless
        // the channel ID itself changes. That's why this is "_v2", not "tc_playback".
        manager.deleteNotificationChannel("tc_playback")

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        manager.createNotificationChannel(
            // HIGH so it pops up (heads-up) and is unmissable on the lock
            // screen the moment the user wakes the phone, not just sitting
            // quietly in the shade - that's what makes "wake screen, tap
            // Stop" actually work in practice.
            NotificationChannel(
                CHANNEL_PLAYBACK,
                getString(R.string.notif_channel_playback_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "tc_service"
        const val CHANNEL_PLAYBACK = "tc_playback_v2"
    }
}
