package org.brahmakumaris.trafficcontrol.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.alarm.AlarmRefreshWorker
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs
import org.brahmakumaris.trafficcontrol.data.SettingsTransfer
import org.brahmakumaris.trafficcontrol.databinding.ActivityMainBinding
import org.brahmakumaris.trafficcontrol.playback.PlaybackService
import org.brahmakumaris.trafficcontrol.update.UpdateChecker

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SchedulePrefs
    private lateinit var scheduler: AlarmScheduler

    private val slotPlayButtons = mutableMapOf<Int, ImageButton>()
    private var previewingSlotId: Int? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val ok = SettingsTransfer.importFrom(this, uri)
            Toast.makeText(
                this,
                if (ok) R.string.import_success else R.string.import_failure,
                Toast.LENGTH_SHORT
            ).show()
            if (ok) {
                setUpVolumeSlider()
                setUpSlots()
            }
        }

    // In-process listener, not a BroadcastReceiver - see PlaybackService's
    // stateListeners doc for why (a dynamically-registered receiver can't be
    // marked unexported on API < 33, exposing this signal to other apps).
    private val playbackStateListener: (Int, Boolean) -> Unit = { slotId, playing ->
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

        binding.viewLogLink.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.exportLink.setOnClickListener { exportSettings() }
        binding.importLink.setOnClickListener { importLauncher.launch("*/*") }
    }

    private fun exportSettings() {
        val uri = SettingsTransfer.exportToShareableFile(this)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.export_settings_action)))
    }

    override fun onResume() {
        super.onResume()
        updateReliabilityStatus()
        UpdateChecker.checkOncePerDay(this)
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.addStateListener(playbackStateListener)
    }

    override fun onStop() {
        super.onStop()
        PlaybackService.removeStateListener(playbackStateListener)
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
                if (!fromUser) return
                prefs.volumePercent = progress
                // Applies live to whatever's playing right now (real alarm or
                // preview), not just the next track - PlaybackService only
                // acts on this while it's actually running something.
                PlaybackService.applyLiveVolume(progress)
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
