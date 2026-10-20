package org.brahmakumaris.trafficcontrol.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.brahmakumaris.trafficcontrol.alarm.AlarmScheduler
import org.json.JSONObject
import java.io.File

/**
 * Export/import of the small bit of state a coordinator actually configures
 * (which slots are on, and the volume) - not the alarm log or anything
 * else. Meant for rolling this app out to several center phones with the
 * same configuration without repeating the same 9 taps on each one.
 */
object SettingsTransfer {

    private const val SCHEMA_VERSION = 1

    fun exportToShareableFile(context: Context): Uri {
        val prefs = SchedulePrefs(context)
        val slots = JSONObject()
        for (slot in Schedule.slots) {
            slots.put(slot.id.toString(), prefs.isSlotEnabled(slot.id))
        }
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("volumePercent", prefs.volumePercent)
            .put("slots", slots)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "traffic-control-settings.json")
        file.writeText(json.toString(2))

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Returns true if the file was understood and applied. */
    fun importFrom(context: Context, uri: Uri): Boolean {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: return false

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return false
        }

        val slots = json.optJSONObject("slots") ?: return false
        val prefs = SchedulePrefs(context)

        for (slot in Schedule.slots) {
            if (slots.has(slot.id.toString())) {
                prefs.setSlotEnabled(slot.id, slots.getBoolean(slot.id.toString()))
            }
        }
        if (json.has("volumePercent")) {
            prefs.volumePercent = json.getInt("volumePercent").coerceIn(0, 100)
        }

        AlarmScheduler(context).rescheduleAll()
        return true
    }
}
