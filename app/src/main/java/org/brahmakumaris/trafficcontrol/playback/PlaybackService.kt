package org.brahmakumaris.trafficcontrol.playback

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.TrafficControlApp
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs

/**
 * Foreground service that plays one scheduled track to completion, then
 * stops itself. Uses plain MediaPlayer - these are short local raw-resource
 * bhajans, not streamed media, so MediaPlayer is simpler and has fewer
 * moving parts than ExoPlayer for this job, which matters for reliability.
 */
class PlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val slotId = intent?.getIntExtra(EXTRA_SLOT_ID, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: getString(R.string.notif_test_text)

        startForeground(NOTIFICATION_ID, buildNotification(label))

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrafficControl:Playback")
        wakeLock?.acquire(10 * 60 * 1000L)

        val resId = if (slotId == AlarmScheduler.TEST_SLOT_ID) {
            Schedule.slots.first().rawResId
        } else {
            Schedule.byId(slotId)?.rawResId
        }

        if (resId == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        playTrack(resId)
        return START_NOT_STICKY
    }

    private fun playTrack(rawResId: Int) {
        try {
            mediaPlayer?.release()
            val volume = SchedulePrefs(this).volumePercent.coerceIn(0, 100) / 100f
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setVolume(volume, volume)
                val afd = resources.openRawResourceFd(rawResId)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { stopSelfSafely() }
                setOnErrorListener { _, _, _ -> stopSelfSafely(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(label: String) =
        NotificationCompat.Builder(this, TrafficControlApp.CHANNEL_PLAYBACK)
            .setContentTitle(getString(R.string.notif_playing_title))
            .setContentText(getString(R.string.notif_playing_text, label))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 42
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_LABEL = "extra_label"
    }
}
