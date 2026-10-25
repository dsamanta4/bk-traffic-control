package org.brahmakumaris.trafficcontrol.playback

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.databinding.ActivityAlarmRingingBinding

/**
 * Shown automatically (via the playback notification's full-screen intent)
 * only for real alarms, never for manual preview - this is the one piece
 * that makes the *hardware* volume buttons work. Android's volume-key
 * routing never includes the alarm stream in its automatic "what's active"
 * detection (confirmed against AOSP source, not a guess) - the only way any
 * app gets physical volume buttons to control alarm volume is for a visible,
 * focused Activity to explicitly claim the stream via setVolumeControlStream()
 * while it's on screen, which is exactly what this does and nothing else.
 *
 * Deliberately minimal: a label and a Stop button. It finishes itself the
 * moment playback stops, from any cause (completion, Stop tapped here or on
 * the notification, or the 10-minute wakelock-style safety net elsewhere).
 */
class AlarmRingingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding

    // In-process listener, not a BroadcastReceiver - see PlaybackService's
    // stateListeners doc for why (a dynamically-registered receiver can't be
    // marked unexported on API < 33, exposing this signal to other apps).
    private val stateListener: (Int, Boolean) -> Unit = { _, playing ->
        if (!playing) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        binding = ActivityAlarmRingingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindToIntent(intent)

        binding.btnStop.setOnClickListener {
            startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
            finish()
        }
    }

    /**
     * This Activity is launchMode="singleInstance" (needed so the hardware
     * volume-key binding always targets one well-known screen), which means
     * a second alarm firing while this one is still showing doesn't create
     * a new instance - Android redelivers into this one via onNewIntent().
     * A real-world report caught exactly this: the 7:00 AM alarm fired
     * while the earlier test alarm's screen was still up, PlaybackService
     * correctly switched to the new track, but the screen kept showing the
     * old one's time/label/image indefinitely, because nothing here ever
     * updated it for the new intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindToIntent(intent)
    }

    private fun bindToIntent(intent: Intent) {
        binding.ringingBackground.setImageResource(BACKGROUNDS.random())

        val slotId = intent.getIntExtra(PlaybackService.EXTRA_SLOT_ID, Int.MIN_VALUE)
        val label = intent.getStringExtra(PlaybackService.EXTRA_LABEL) ?: ""
        val slot = Schedule.byId(slotId)
        binding.ringingTime.text = if (slot != null) {
            String.format("%02d:%02d", slot.hour, slot.minute)
        } else {
            ""
        }
        binding.ringingLabel.text = label
    }

    /**
     * Registered here (not onResume/onPause): a normal screen-timeout pauses
     * this Activity without stopping it, and a listener registered only
     * while resumed would be removed for that whole window - exactly
     * when a track is most likely to finish naturally, since nobody is
     * interacting with the screen. onStart/onStop keeps it listening across
     * a screen-off, only removing it once the Activity is actually gone.
     */
    override fun onStart() {
        super.onStart()
        PlaybackService.addStateListener(stateListener)
        // Covers the signal this listener could still have missed: it's a
        // one-shot call, never redelivered, so if playback already stopped
        // before this registration (or while this Activity didn't exist at
        // all yet), the only way to find out is to ask directly.
        if (!PlaybackService.isPlaying) finish()
    }

    override fun onStop() {
        super.onStop()
        PlaybackService.removeStateListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_ALARM
    }

    override fun onPause() {
        super.onPause()
        volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
    }

    companion object {
        // Bundled Brahma Kumaris artwork - one is picked at random each time
        // this screen appears, so it varies alarm to alarm rather than
        // always showing the same image. internal (not private) so a unit
        // test can verify the shuffle actually covers every entry.
        internal val BACKGROUNDS = listOf(
            R.drawable.ringing_bg_1,
            R.drawable.ringing_bg_2,
            R.drawable.ringing_bg_3,
            R.drawable.ringing_bg_4,
            R.drawable.ringing_bg_5,
            R.drawable.ringing_bg_6,
            R.drawable.ringing_bg_7,
            R.drawable.ringing_bg_8,
            R.drawable.ringing_bg_9,
            R.drawable.ringing_bg_10,
            R.drawable.ringing_bg_11,
            R.drawable.ringing_bg_12,
            R.drawable.ringing_bg_13,
            R.drawable.ringing_bg_14
        )
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}
