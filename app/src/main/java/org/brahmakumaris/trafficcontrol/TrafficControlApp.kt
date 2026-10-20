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
        AlarmLog(this).detectAndLogMissed()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

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
        const val CHANNEL_PLAYBACK = "tc_playback"
    }
}
