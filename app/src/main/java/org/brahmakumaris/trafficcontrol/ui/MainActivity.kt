package org.brahmakumaris.trafficcontrol.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.alarm.AlarmRefreshWorker
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs
import org.brahmakumaris.trafficcontrol.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SchedulePrefs
    private lateinit var scheduler: AlarmScheduler

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

            binding.slotsContainer.addView(row)
        }
    }
}
