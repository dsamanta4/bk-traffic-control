package org.brahmakumaris.trafficcontrol.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device against the real SharedPreferences implementation
 * (not a JVM fake) - this is what "settings must carry forward" actually
 * depends on. Directly answers the real-world report: volume and a
 * disabled slot have to survive, unchanged, no matter how many times the
 * app is touched afterward.
 *
 * IMPORTANT: this must use InstrumentationRegistry.getInstrumentation().context
 * (the test package's own context), never .targetContext. SchedulePrefs
 * resolves its SharedPreferences file from whatever Context it's given -
 * .targetContext is the real app under test, which on a first run of this
 * suite overwrote a real, in-use device's live alarm toggles and volume
 * with the test's throwaway values. .context belongs to this test APK's
 * own package (org.brahmakumaris.trafficcontrol.test), which gets its own
 * separate storage even though the test code runs inside the target app's
 * process - the two never collide.
 */
@RunWith(AndroidJUnit4::class)
class SchedulePrefsStressTest {

    private lateinit var prefs: SchedulePrefs

    private fun isolatedTestContext(): Context = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setUp() {
        prefs = SchedulePrefs(isolatedTestContext())
    }

    @After
    fun tearDown() {
        // Leave no trace even in this isolated store, so each run starts clean.
        isolatedTestContext().applicationContext
            .getSharedPreferences("traffic_control_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun disabledSlotStaysDisabledAcrossManySimulatedDays() {
        // Pin the 3:30 AM slot off, exactly the real report's scenario.
        prefs.setSlotEnabled(0, false)
        prefs.volumePercent = 42

        // Stand-in for "next month, next year": every other slot gets
        // toggled on/off heavily in between (simulating ordinary day-to-day
        // use elsewhere in the schedule) while slot 0 and the volume are
        // never touched again - they must still read back exactly as set.
        repeat(2_000) { i ->
            val otherSlot = 1 + (i % 8) // slots 1..8, never slot 0
            prefs.setSlotEnabled(otherSlot, i % 2 == 0)

            // Re-read with a fresh SchedulePrefs instance periodically, the
            // same way a new Activity/Worker/BroadcastReceiver always does -
            // catches any bug that only shows up across object recreation,
            // not just within one long-lived instance.
            val freshPrefs = SchedulePrefs(isolatedTestContext())
            assertEquals("slot 0 flipped back on at iteration $i", false, freshPrefs.isSlotEnabled(0))
            assertEquals("volume drifted at iteration $i", 42, freshPrefs.volumePercent)
        }
    }

    @Test
    fun volumeSurvivesManyRapidChangesToExactLastValue() {
        val values = listOf(0, 25, 50, 75, 100, 10, 90, 33, 67, 1)
        repeat(500) { i ->
            val v = values[i % values.size]
            prefs.volumePercent = v
            assertEquals(v, SchedulePrefs(isolatedTestContext()).volumePercent)
        }
    }

    @Test
    fun allNineSlotsIndependentlyKeepTheirOwnState() {
        // Set a distinct, known pattern across all 9 real slots, then
        // confirm none of them leak into or overwrite one another - the
        // underlying bug class a shared/mis-keyed preference would cause.
        val pattern = (0..8).associateWith { it % 2 == 0 }
        for ((slotId, enabled) in pattern) {
            prefs.setSlotEnabled(slotId, enabled)
        }

        val fresh = SchedulePrefs(isolatedTestContext())
        for ((slotId, enabled) in pattern) {
            assertEquals("slot $slotId did not keep its independent state", enabled, fresh.isSlotEnabled(slotId))
        }
    }
}
