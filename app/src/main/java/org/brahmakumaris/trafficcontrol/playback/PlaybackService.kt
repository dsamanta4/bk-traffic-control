package org.brahmakumaris.trafficcontrol.playback

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.TrafficControlApp
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.brahmakumaris.trafficcontrol.data.AlarmLog
import org.brahmakumaris.trafficcontrol.data.AlarmOutcome
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
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentSlotId: Int? = null
    private var currentLabel: String = ""
    private var currentIsRealAlarm: Boolean = false
    private var currentIsCatchUp: Boolean = false

    /**
     * Fixes the other half of the volume complaint: previously, moving the
     * in-app slider while a track was already playing only took effect on
     * the *next* track - this app had no way to reach a MediaPlayer it was
     * already running. Listening for the slider's broadcast while playing
     * and re-applying setVolume() immediately closes that gap.
     */
    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val percent = intent.getIntExtra(EXTRA_VOLUME_PERCENT, -1)
            if (percent < 0) return
            val v = percent.coerceIn(0, 100) / 100f
            mediaPlayer?.setVolume(v, v)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(volumeChangeReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val slotId = intent?.getIntExtra(EXTRA_SLOT_ID, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: getString(R.string.notif_test_text)
        val isRealAlarm = intent?.getBooleanExtra(EXTRA_IS_REAL_ALARM, false) ?: false
        val isCatchUp = intent?.getBooleanExtra(EXTRA_IS_CATCH_UP, false) ?: false
        currentSlotId = slotId
        currentLabel = label
        currentIsRealAlarm = isRealAlarm
        currentIsCatchUp = isCatchUp

        if (isRealAlarm) raiseSystemAlarmVolume()

        startForeground(NOTIFICATION_ID, buildNotification(slotId, label, isCatchUp, isRealAlarm))

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrafficControl:Playback")
        wakeLock?.acquire(10 * 60 * 1000L)

        val resId = if (slotId == AlarmScheduler.TEST_SLOT_ID) {
            Schedule.slots.first().rawResId
        } else {
            Schedule.byId(slotId)?.rawResId
        }

        if (resId == null) {
            if (isRealAlarm) AlarmLog(this).record(slotId, label, AlarmOutcome.ERROR)
            stopSelfSafely()
            return START_NOT_STICKY
        }

        playTrack(resId)
        return START_NOT_STICKY
    }

    private fun playTrack(rawResId: Int) {
        try {
            mediaPlayer?.release()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            requestAudioFocus(attributes)

            val volume = SchedulePrefs(this).volumePercent.coerceIn(0, 100) / 100f
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attributes)
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
            if (currentIsRealAlarm) {
                val outcome = if (currentIsCatchUp) AlarmOutcome.CAUGHT_UP else AlarmOutcome.FIRED
                AlarmLog(this).record(currentSlotId ?: return, currentLabel, outcome)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            if (currentIsRealAlarm) {
                AlarmLog(this).record(currentSlotId ?: AlarmScheduler.TEST_SLOT_ID, currentLabel, AlarmOutcome.ERROR)
            }
            stopSelfSafely()
        }
    }

    /**
     * Fixes a real-world report: pressing the volume rocker while a track
     * played did nothing. Android routes hardware volume keys to whichever
     * audio stream currently holds focus - without explicitly requesting
     * focus for STREAM_ALARM, a background service's playback isn't
     * guaranteed to be "the" stream the volume keys adjust, even though it's
     * audibly the only thing playing. Requesting focus is also just correct
     * practice for anything that plays audio, focus-ducking conflicts with
     * other apps aside.
     */
    private fun requestAudioFocus(attributes: AudioAttributes) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun stopSelfSafely() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        abandonAudioFocus()
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

    /**
     * VISIBILITY_PUBLIC + CATEGORY_ALARM is what makes the Stop action show
     * up and be tappable straight from the lock screen on a simple screen
     * wake - no unlock needed - which is the behaviour asked for: someone in
     * a meeting or out shopping can silence a real alarm without even
     * opening the phone, by waking the screen and tapping Stop. Firing
     * stopPendingIntent only ever sends ACTION_STOP to this already-running
     * service, so it works without needing the device unlocked.
     */
    private fun buildNotification(slotId: Int, label: String, isCatchUp: Boolean, isRealAlarm: Boolean): android.app.Notification {
        val stopIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (isCatchUp) {
            getString(R.string.notif_playing_text_late, label)
        } else {
            getString(R.string.notif_playing_text, label)
        }
        val builder = NotificationCompat.Builder(this, TrafficControlApp.CHANNEL_PLAYBACK)
            .setContentTitle(getString(R.string.notif_playing_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_stop_action), stopPendingIntent)

        // Real alarms only, never manual preview - this is what actually puts
        // AlarmRingingActivity on screen (over the lock screen if needed),
        // which is the only way to make hardware volume buttons work (see
        // that class). The system falls back to just showing this as a
        // normal heads-up notification if the phone is actively in use.
        if (isRealAlarm) {
            val ringingIntent = Intent(this, AlarmRingingActivity::class.java)
                .putExtra(EXTRA_SLOT_ID, slotId)
                .putExtra(EXTRA_LABEL, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ringingPendingIntent = PendingIntent.getActivity(
                this, 1, ringingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(ringingPendingIntent, true)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        abandonAudioFocus()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        try {
            unregisterReceiver(volumeChangeReceiver)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 42
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_PLAYING = "extra_playing"
        const val EXTRA_IS_REAL_ALARM = "extra_is_real_alarm"
        const val EXTRA_IS_CATCH_UP = "extra_is_catch_up"
        const val EXTRA_VOLUME_PERCENT = "extra_volume_percent"
        const val ACTION_STOP = "org.brahmakumaris.trafficcontrol.ACTION_STOP"
        const val ACTION_PLAYBACK_STATE = "org.brahmakumaris.trafficcontrol.ACTION_PLAYBACK_STATE"
        const val ACTION_VOLUME_CHANGED = "org.brahmakumaris.trafficcontrol.ACTION_VOLUME_CHANGED"
    }
}
