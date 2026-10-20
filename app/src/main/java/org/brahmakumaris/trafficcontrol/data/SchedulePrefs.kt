package org.brahmakumaris.trafficcontrol.data

import android.content.Context

/**
 * Tiny SharedPreferences wrapper. Deliberately not a database: there are only
 * 9 fixed slots and a couple of global settings, so the simplest possible
 * storage is the most reliable one.
 */
class SchedulePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("traffic_control_prefs", Context.MODE_PRIVATE)

    fun isSlotEnabled(slotId: Int): Boolean =
        prefs.getBoolean(KEY_ENABLED_PREFIX + slotId, true)

    fun setSlotEnabled(slotId: Int, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + slotId, enabled).apply()
    }

    var volumePercent: Int
        get() = prefs.getInt(KEY_VOLUME, 100)
        set(value) = prefs.edit().putInt(KEY_VOLUME, value).apply()

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    /**
     * The moment missed-alarm detection starts caring. Lazily set to "now"
     * on first read so a fresh install never flags yesterday's occurrences
     * (which obviously couldn't have fired - the app didn't exist yet) as
     * missed.
     */
    var monitoringSinceMillis: Long
        get() {
            var value = prefs.getLong(KEY_MONITORING_SINCE, 0L)
            if (value == 0L) {
                value = System.currentTimeMillis()
                monitoringSinceMillis = value
            }
            return value
        }
        set(value) = prefs.edit().putLong(KEY_MONITORING_SINCE, value).apply()

    companion object {
        private const val KEY_ENABLED_PREFIX = "slot_enabled_"
        private const val KEY_VOLUME = "volume_percent"
        private const val KEY_SETUP_DONE = "setup_completed"
        private const val KEY_MONITORING_SINCE = "monitoring_since_millis"
    }
}
