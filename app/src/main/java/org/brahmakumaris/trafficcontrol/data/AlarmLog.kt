package org.brahmakumaris.trafficcontrol.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

enum class AlarmOutcome { FIRED, MISSED, ERROR }

data class AlarmLogEntry(
    val timestampMillis: Long,
    val slotId: Int,
    val label: String,
    val outcome: AlarmOutcome
)

/**
 * A small on-device history of what actually happened at each scheduled
 * time - the thing this whole rebuild exists to make trustworthy. Lets a
 * coordinator check reliability by glancing at the phone (or sending a
 * screenshot) instead of staying up to listen for every alarm.
 *
 * Deliberately a flat JSON array in SharedPreferences, capped at MAX_ENTRIES
 * - this is a diagnostic log, not a database, and keeping it dead simple
 * means one less thing that can break.
 */
class AlarmLog(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("traffic_control_log", Context.MODE_PRIVATE)

    fun record(slotId: Int, label: String, outcome: AlarmOutcome, timestampMillis: Long = System.currentTimeMillis()) {
        val entries = readAll().toMutableList()
        entries.add(0, AlarmLogEntry(timestampMillis, slotId, label, outcome))
        writeAll(entries.take(MAX_ENTRIES))
    }

    fun recent(limit: Int = MAX_ENTRIES): List<AlarmLogEntry> = readAll().take(limit)

    /**
     * Call periodically (app start, and the 15-minute refresh worker). For
     * every enabled slot whose most recent expected occurrence has already
     * passed with no log entry near it at all, records a MISSED entry.
     * Already-logged occurrences (fired, errored, or already flagged missed)
     * are never re-flagged, so this is safe to call as often as needed.
     */
    fun detectAndLogMissed() {
        val schedulePrefs = SchedulePrefs(appContext)
        val monitoringSince = schedulePrefs.monitoringSinceMillis
        val existing = readAll()

        for (slot in Schedule.slots) {
            if (!schedulePrefs.isSlotEnabled(slot.id)) continue
            val expected = mostRecentPastOccurrence(slot.hour, slot.minute)
            if (expected < monitoringSince) continue

            val alreadyCovered = existing.any {
                it.slotId == slot.id && Math.abs(it.timestampMillis - expected) < TOLERANCE_MILLIS
            }
            if (!alreadyCovered) {
                record(slot.id, slot.label, AlarmOutcome.MISSED, expected)
            }
        }
    }

    private fun mostRecentPastOccurrence(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val candidate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (candidate.after(now)) {
            candidate.add(Calendar.DAY_OF_YEAR, -1)
        }
        return candidate.timeInMillis
    }

    private fun readAll(): List<AlarmLogEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                AlarmLogEntry(
                    timestampMillis = obj.optLong("t"),
                    slotId = obj.optInt("s"),
                    label = obj.optString("l"),
                    outcome = try {
                        AlarmOutcome.valueOf(obj.optString("o"))
                    } catch (e: IllegalArgumentException) {
                        AlarmOutcome.ERROR
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(entries: List<AlarmLogEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("t", entry.timestampMillis)
                    .put("s", entry.slotId)
                    .put("l", entry.label)
                    .put("o", entry.outcome.name)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 100
        private const val TOLERANCE_MILLIS = 10 * 60 * 1000L
    }
}
