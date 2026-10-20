package org.brahmakumaris.trafficcontrol.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.alarm.AlarmRefreshWorker
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs
import org.brahmakumaris.trafficcontrol.databinding.ActivityMainBinding
import org.brahmakumaris.trafficcontrol.playback.PlaybackService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SchedulePrefs
    private lateinit var scheduler: AlarmScheduler

    private val slotPlayButtons = mutableMapOf<Int, ImageButton>()
    private var previewingSlotId: Int? = null

    private val playbackStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val slotId = intent.getIntExtra(PlaybackService.EXTRA_SLOT_ID, Int.MIN_VALUE)
            val playing = intent.getBooleanExtra(PlaybackService.EXTRA_PLAYING, false)
            if (playing) {
                // A new slot started: whichever button was showing "stop" before is now stale.
                previewingSlotId?.let { setButtonPlaying(it, false) }
                previewingSlotId = slotId
                setButtonPlaying(slotId, true)
            } else if (slotId == previewingSlotId) {
                previewingSlotId = null
                setButtonPlaying(slotId, false)
            }
        }
    }

    private fun setButtonPlaying(slotId: Int, playing: Boolean) {
        slotPlayButtons[slotId]?.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SchedulePrefs(this)
        scheduler = AlarmScheduler(this)

        AlarmRefreshWorker.enable(this)

        setUpVolumeSlider()
        setUpSlots()

        binding.reliabilityCard.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateReliabilityStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PlaybackService.ACTION_PLAYBACK_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(playbackStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playbackStateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(playbackStateReceiver)
    }

    private fun updateReliabilityStatus() {
        if (prefs.setupCompleted) {
            binding.reliabilityStatus.text = getString(R.string.reliability_done)
        } else {
            binding.reliabilityStatus.text = getString(R.string.reliability_not_done)
        }
    }

    private fun setUpVolumeSlider() {
        binding.volumeSeekBar.progress = prefs.volumePercent
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.volumePercent = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setUpSlots() {
        val inflater = LayoutInflater.from(this)
        binding.slotsContainer.removeAllViews()
        for (slot in Schedule.slots) {
            val row = inflater.inflate(R.layout.item_slot, binding.slotsContainer, false)
            row.findViewById<TextView>(R.id.slotTime).text =
                String.format("%02d:%02d", slot.hour, slot.minute)
            row.findViewById<TextView>(R.id.slotLabel).text = slot.label

            val switch = row.findViewById<SwitchMaterial>(R.id.slotSwitch)
            switch.isChecked = prefs.isSlotEnabled(slot.id)
            switch.setOnCheckedChangeListener { _, isChecked ->
                prefs.setSlotEnabled(slot.id, isChecked)
                scheduler.rescheduleSlot(slot.id)
            }

            val playButton = row.findViewById<ImageButton>(R.id.slotPlay)
            slotPlayButtons[slot.id] = playButton
            playButton.setOnClickListener {
                if (previewingSlotId == slot.id) {
                    stopPreview()
                } else {
                    previewSlot(slot.id, slot.label)
                }
            }

            binding.slotsContainer.addView(row)
        }
    }

    /** Manual preview, independent of the slot's enabled/scheduled state - lets you check
     * volume or hear a track without waiting for its real alarm time. Starting a new preview
     * automatically replaces whatever was previously playing (see PlaybackService.playTrack). */
    private fun previewSlot(slotId: Int, label: String) {
        val intent = Intent(this, PlaybackService::class.java)
            .putExtra(PlaybackService.EXTRA_SLOT_ID, slotId)
            .putExtra(PlaybackService.EXTRA_LABEL, label)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopPreview() {
        startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
    }
}
