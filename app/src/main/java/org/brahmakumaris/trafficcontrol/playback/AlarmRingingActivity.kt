package org.brahmakumaris.trafficcontrol.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val playing = intent.getBooleanExtra(PlaybackService.EXTRA_PLAYING, false)
            if (!playing) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        binding = ActivityAlarmRingingBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        binding.btnStop.setOnClickListener {
            startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_ALARM
        val filter = IntentFilter(PlaybackService.ACTION_PLAYBACK_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        unregisterReceiver(stateReceiver)
    }

    companion object {
        // Bundled Brahma Kumaris artwork - one is picked at random each time
        // this screen appears, so it varies alarm to alarm rather than
        // always showing the same image.
        private val BACKGROUNDS = listOf(
            R.drawable.ringing_bg_1,
            R.drawable.ringing_bg_2,
            R.drawable.ringing_bg_3,
            R.drawable.ringing_bg_4
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
