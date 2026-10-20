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
    private var currentSlotId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val slotId = intent?.getIntExtra(EXTRA_SLOT_ID, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: getString(R.string.notif_test_text)
        val isRealAlarm = intent?.getBooleanExtra(EXTRA_IS_REAL_ALARM, false) ?: false
        currentSlotId = slotId

        if (isRealAlarm) raiseSystemAlarmVolume()

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
            broadcastState(playing = true)
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
        broadcastState(playing = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Real alarms only - never for manual preview. The app's own volume slider
     * (see playTrack) only ever scales DOWN from whatever the phone's system
     * alarm channel happens to be set to; it can never make a quiet device
     * loud. Devices drift quiet on their own (physical volume buttons, a
     * battery saver mode, or just different OEM defaults - this is literally
     * why a Xiaomi and a Motorola sounded different in testing even with the
     * in-app slider identically at 100%). So for a real scheduled alarm, make
     * sure the one thing that actually controls ceiling loudness - the
     * device's own alarm stream - is all the way up before playing.
     */
    private fun raiseSystemAlarmVolume() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Could not raise system alarm volume", e)
        }
    }

    /** Lets MainActivity's preview buttons reflect what's actually playing. */
    private fun broadcastState(playing: Boolean) {
        val slotId = currentSlotId ?: return
        sendBroadcast(
            Intent(ACTION_PLAYBACK_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_SLOT_ID, slotId)
                .putExtra(EXTRA_PLAYING, playing)
        )
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
        const val EXTRA_PLAYING = "extra_playing"
        const val EXTRA_IS_REAL_ALARM = "extra_is_real_alarm"
        const val ACTION_STOP = "org.brahmakumaris.trafficcontrol.ACTION_STOP"
        const val ACTION_PLAYBACK_STATE = "org.brahmakumaris.trafficcontrol.ACTION_PLAYBACK_STATE"
    }
}
