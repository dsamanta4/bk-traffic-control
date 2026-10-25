package org.brahmakumaris.trafficcontrol.playback

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import java.util.concurrent.CopyOnWriteArrayList

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

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Fixes the other half of the volume complaint: previously, moving the
     * in-app slider while a track was already playing only took effect on
     * the *next* track - this app had no way to reach a MediaPlayer it was
     * already running. Called directly from MainActivity via the companion
     * instance reference while playing, so it closes that gap immediately.
     *
     * This used to be a dynamically-registered BroadcastReceiver for
     * ACTION_VOLUME_CHANGED. A security review found that on API < 33 a
     * dynamically-registered receiver has no way to be marked unexported -
     * any other app on the device could send that broadcast and silently
     * change this app's live playback volume. Since this signal only ever
     * needs to travel between this app's own components, a direct in-process
     * call removes that exposure entirely instead of only patching it on
     * newer Android versions.
     */
    private fun applyVolumeNow(percent: Int) {
        val v = percent.coerceIn(0, 100) / 100f
        mediaPlayer?.setVolume(v, v)
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
            isPlaying = true
            notifyState(playing = true)
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
        isPlaying = false
        notifyState(playing = false)
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

    /** Lets MainActivity's preview buttons and AlarmRingingActivity's
     * auto-close logic react to what's actually playing. */
    private fun notifyState(playing: Boolean) {
        val slotId = currentSlotId ?: return
        for (listener in stateListeners) {
            listener(slotId, playing)
        }
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
        // that class). The system only auto-launches a full-screen intent
        // when the device is locked or the screen is off; if the phone is
        // already unlocked and in active use when the alarm fires, Android
        // deliberately shows this as an ordinary heads-up notification
        // instead (confirmed against AOSP behaviour, not a guess) - no app
        // can override that, and no app should want to, since the same
        // restriction is what stops a malicious app from hijacking the
        // screen at will. setContentIntent makes sure that in that case
        // there is still a one-tap way to reach the volume-capable screen:
        // without it, tapping the notification did nothing at all, and the
        // only route back to hardware volume control was gone until the
        // song finished on its own.
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
            builder.setContentIntent(ringingPendingIntent)
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
        isPlaying = false
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 42

        /**
         * Ground truth for AlarmRingingActivity to check directly, not just
         * react to notifyState()'s one-shot signal. A real-device report
         * showed the ringing screen stuck on-screen over an hour after its
         * song had finished: the screen had been paused (listener not yet
         * registered again) when the "stopped" signal fired, which is
         * fire-and-forget and is never redelivered to a listener that
         * registers later. This flag lets the Activity ask "is anything
         * actually still playing?" whenever it becomes visible again, instead
         * of only ever finding out through a signal it might have missed.
         */
        @Volatile
        var isPlaying: Boolean = false

        /**
         * The one running instance, if any - this service never runs more
         * than one at a time (a new track always replaces the old one), so
         * a plain nullable reference is enough to let MainActivity apply a
         * live volume change without going through a system-wide broadcast.
         */
        @Volatile
        private var instance: PlaybackService? = null

        /**
         * In-process pub/sub for playback state, replacing what used to be
         * an ACTION_PLAYBACK_STATE broadcast. A security review found that a
         * dynamically-registered BroadcastReceiver has no way to be marked
         * unexported on API < 33 - any other app on the device could send
         * that broadcast and, for example, force AlarmRingingActivity to
         * close early while the alarm kept playing. This signal only ever
         * needs to reach this app's own components, so an in-process
         * listener list removes that exposure on every Android version
         * instead of only patching newer ones.
         */
        private val stateListeners = CopyOnWriteArrayList<(slotId: Int, playing: Boolean) -> Unit>()

        fun addStateListener(listener: (slotId: Int, playing: Boolean) -> Unit) {
            stateListeners.add(listener)
        }

        fun removeStateListener(listener: (slotId: Int, playing: Boolean) -> Unit) {
            stateListeners.remove(listener)
        }

        /** Applies a live volume change to whatever's currently playing, if anything is. */
        fun applyLiveVolume(percent: Int) {
            instance?.applyVolumeNow(percent)
        }

        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_IS_REAL_ALARM = "extra_is_real_alarm"
        const val EXTRA_IS_CATCH_UP = "extra_is_catch_up"
        const val ACTION_STOP = "org.brahmakumaris.trafficcontrol.ACTION_STOP"
    }
}
