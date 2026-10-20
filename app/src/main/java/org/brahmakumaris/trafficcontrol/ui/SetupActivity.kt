package org.brahmakumaris.trafficcontrol.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.brahmakumaris.trafficcontrol.data.SchedulePrefs
import org.brahmakumaris.trafficcontrol.databinding.ActivitySetupBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: SchedulePrefs
    private var wasExactAlarmAllowed = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStepStates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SchedulePrefs(this)
        wasExactAlarmAllowed = canScheduleExactAlarms()

        binding.btnNotifications.setOnClickListener { requestNotificationPermission() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnExactAlarm.setOnClickListener { requestExactAlarmPermission() }
        binding.btnAutostart.text =
            getString(R.string.step_autostart_action, OemSettings.manufacturerLabel())
        binding.btnAutostart.setOnClickListener { OemSettings.openManufacturerSettings(this) }
        binding.btnTest.setOnClickListener { scheduleTestAlarm() }
        binding.btnDone.setOnClickListener {
            prefs.setupCompleted = true
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStepStates()

        // The exact-alarm permission can only be granted by leaving this screen (to the
        // Settings page) and coming back, so newly-granted here means every alarm that
        // failed to schedule earlier (e.g. at first app launch) needs to be re-armed now.
        val nowAllowed = canScheduleExactAlarms()
        if (nowAllowed && !wasExactAlarmAllowed) {
            AlarmScheduler(this).rescheduleAll()
        }
        wasExactAlarmAllowed = nowAllowed
    }

    private fun refreshStepStates() {
        binding.btnNotifications.isEnabled = !notificationsGranted()
        binding.btnNotifications.text = if (notificationsGranted())
            getString(R.string.step_notifications_done) else getString(R.string.step_notifications_action)

        binding.btnBattery.isEnabled = !ignoringBatteryOptimizations()
        binding.btnBattery.text = if (ignoringBatteryOptimizations())
            getString(R.string.step_battery_done) else getString(R.string.step_battery_action)

        binding.btnExactAlarm.isEnabled = !canScheduleExactAlarms()
        binding.btnExactAlarm.text = if (canScheduleExactAlarms())
            getString(R.string.step_exact_alarm_done) else getString(R.string.step_exact_alarm_action)
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ignoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun scheduleTestAlarm() {
        AlarmScheduler(this).scheduleTestAlarmInOneMinute()
        val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
            .format(Date(System.currentTimeMillis() + 60_000L))
        binding.testStatus.text = getString(R.string.step_test_scheduled, time)
        binding.testStatus.visibility = android.view.View.VISIBLE
    }
}
