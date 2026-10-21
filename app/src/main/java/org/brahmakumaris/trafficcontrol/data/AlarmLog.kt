package org.brahmakumaris.trafficcontrol.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

enum class AlarmOutcome { FIRED, CAUGHT_UP, MISSED, ERROR }

data class AlarmLogEntry(
    val timestampMillis: Long,
    val slotId: Int,
    val label: String,
    val outcome: AlarmOutcome
)

/** A slot whose expected occurrence passed with nothing logged for it yet. */
data class UncoveredSlot(val slot: ScheduleSlot, val expectedMillis: Long, val minutesLate: Long)

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

        // A real fire (on time or caught up late) makes any earlier MISSED
        // entry for the same occurrence stale and confusing - e.g. without
        // this, a slot that fired 3 seconds after being checked would show
        // both MISSED and FIRED for the same alarm. Drop the stale one.
        if (outcome == AlarmOutcome.FIRED || outcome == AlarmOutcome.CAUGHT_UP) {
            entries.removeAll {
                it.slotId == slotId && it.outcome == AlarmOutcome.MISSED &&
                    Math.abs(it.timestampMillis - timestampMillis) < TOLERANCE_MILLIS
            }
        }

        entries.add(0, AlarmLogEntry(timestampMillis, slotId, label, outcome))
        writeAll(entries.take(MAX_ENTRIES))
    }

    fun recent(limit: Int = MAX_ENTRIES): List<AlarmLogEntry> = readAll().take(limit)

    /**
     * Call periodically (app start, and the 15-minute refresh worker).
     * Returns slots whose expected occurrence passed more than
     * GRACE_PERIOD_MILLIS ago with nothing logged near it, split into two
     * buckets by the caller's responsibility: this method handles the
     * "too late to bother" case directly (logs MISSED), and hands back the
     * still-recent ones so the caller can trigger a catch-up play before
     * falling back to logging it as missed.
     *
     * The grace period exists because this runs every time the app process
     * starts for ANY reason - including the alarm's OWN broadcast waking the
     * process up. Without it, this method can run and conclude "missed"
     * microseconds before the real AlarmReceiver.onReceive() fires and logs
     * it - a false positive confirmed in the field (a slot flagged missed
     * 3 seconds before it fired normally).
     */
    fun checkForUncovered(): List<UncoveredSlot> {
        val schedulePrefs = SchedulePrefs(appContext)
        val monitoringSince = schedulePrefs.monitoringSinceMillis
        val existing = readAll()
        val now = System.currentTimeMillis()
        val uncovered = mutableListOf<UncoveredSlot>()

        for (slot in Schedule.slots) {
            if (!schedulePrefs.isSlotEnabled(slot.id)) continue
            val expected = mostRecentPastOccurrence(slot.hour, slot.minute)
            if (expected < monitoringSince) continue

            val pastDueMillis = now - expected
            if (pastDueMillis < GRACE_PERIOD_MILLIS) continue

            val alreadyCovered = existing.any {
                it.slotId == slot.id && Math.abs(it.timestampMillis - expected) < TOLERANCE_MILLIS
            }
            if (alreadyCovered) continue

            if (pastDueMillis <= CATCHUP_WINDOW_MILLIS) {
                uncovered.add(UncoveredSlot(slot, expected, pastDueMillis / 60_000))
            } else {
                record(slot.id, slot.label, AlarmOutcome.MISSED, expected)
            }
        }
        return uncovered
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
        private const val GRACE_PERIOD_MILLIS = 3 * 60 * 1000L
        private const val CATCHUP_WINDOW_MILLIS = 30 * 60 * 1000L
    }
}
