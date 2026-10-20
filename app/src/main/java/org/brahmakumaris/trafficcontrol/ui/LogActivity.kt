package org.brahmakumaris.trafficcontrol.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.data.AlarmLog
import org.brahmakumaris.trafficcontrol.data.AlarmOutcome
import org.brahmakumaris.trafficcontrol.databinding.ActivityLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only history of what actually fired and what didn't, so a
 * coordinator can check reliability by looking at the phone instead of
 * staying up to listen for 3:30 AM.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val entries = AlarmLog(this).recent()
        if (entries.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }

        val inflater = LayoutInflater.from(this)
        val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_log_entry, binding.logContainer, false)
            row.findViewById<TextView>(R.id.logTime).text = dateFormat.format(Date(entry.timestampMillis))
            row.findViewById<TextView>(R.id.logLabel).text = entry.label

            val outcomeView = row.findViewById<TextView>(R.id.logOutcome)
            val color = colorFor(entry.outcome)
            outcomeView.text = textFor(entry.outcome)
            outcomeView.setTextColor(color)

            val dot = row.findViewById<View>(R.id.outcomeDot)
            DrawableCompat.setTint(dot.background.mutate(), color)

            binding.logContainer.addView(row)
        }
    }

    private fun colorFor(outcome: AlarmOutcome): Int = when (outcome) {
        AlarmOutcome.FIRED -> Color.parseColor("#2E7D32")
        AlarmOutcome.MISSED -> Color.parseColor("#C62828")
        AlarmOutcome.ERROR -> Color.parseColor("#E65100")
    }

    private fun textFor(outcome: AlarmOutcome): String = when (outcome) {
        AlarmOutcome.FIRED -> getString(R.string.log_outcome_fired)
        AlarmOutcome.MISSED -> getString(R.string.log_outcome_missed)
        AlarmOutcome.ERROR -> getString(R.string.log_outcome_error)
    }
}
