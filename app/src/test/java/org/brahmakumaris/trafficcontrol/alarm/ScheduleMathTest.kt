package org.brahmakumaris.trafficcontrol.alarm

import org.brahmakumaris.trafficcontrol.data.AlarmLog
import org.brahmakumaris.trafficcontrol.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Stress test requested after a real-world report: a devotional alarm's
 * schedule has to stay correct indefinitely, not just for the next few
 * days someone happens to test it manually. This drives the pure date math
 * behind every scheduled slot across several simulated years - including
 * every month/year rollover and every leap day in range - without waiting
 * for real calendar time to pass.
 */
class ScheduleMathTest {

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `nextOccurrence always lands in the future and on the correct minute`() {
        var now = calendarAt(2026, Calendar.JANUARY, 1, 0, 0)
        val end = calendarAt(2030, Calendar.JANUARY, 1, 0, 0) // ~4 years, 2 leap days (2028)

        var checked = 0
        while (now.before(end)) {
            for (slot in Schedule.slots) {
                val triggerAt = AlarmScheduler.nextOccurrence(slot.hour, slot.minute, now)
                val trigger = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
                    timeInMillis = triggerAt
                }

                assertTrue(
                    "nextOccurrence for slot ${slot.id} at ${now.time} must be strictly in the future, got ${trigger.time}",
                    triggerAt > now.timeInMillis
                )
                assertEquals("hour mismatch at ${now.time}", slot.hour, trigger.get(Calendar.HOUR_OF_DAY))
                assertEquals("minute mismatch at ${now.time}", slot.minute, trigger.get(Calendar.MINUTE))

                // Never more than ~24h ahead - a correct scheduler always
                // picks the *next* occurrence, never skips a whole extra day.
                val hoursAhead = (triggerAt - now.timeInMillis) / (60 * 60 * 1000.0)
                assertTrue(
                    "slot ${slot.id} at ${now.time} should fire within 24h, was ${hoursAhead}h away",
                    hoursAhead <= 24.0
                )
            }
            checked++
            now = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        }

        // Sanity check this test actually ran the long stretch it claims to.
        assertTrue("expected to cover at least 1400 simulated days, only covered $checked", checked >= 1400)
    }

    @Test
    fun `mostRecentPastOccurrence always lands in the past and on the correct minute`() {
        var now = calendarAt(2026, Calendar.JANUARY, 1, 0, 0)
        val end = calendarAt(2030, Calendar.JANUARY, 1, 0, 0)

        while (now.before(end)) {
            for (slot in Schedule.slots) {
                val expectedAt = AlarmLog.mostRecentPastOccurrence(slot.hour, slot.minute, now)
                val expected = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
                    timeInMillis = expectedAt
                }

                assertTrue(
                    "mostRecentPastOccurrence for slot ${slot.id} at ${now.time} must be in the past, got ${expected.time}",
                    expectedAt <= now.timeInMillis
                )
                assertEquals("hour mismatch at ${now.time}", slot.hour, expected.get(Calendar.HOUR_OF_DAY))
                assertEquals("minute mismatch at ${now.time}", slot.minute, expected.get(Calendar.MINUTE))
            }
            now = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        }
    }

    @Test
    fun `correctly rolls across every month and year boundary, including leap day`() {
        // Spot-check the exact boundary instants rather than the whole range -
        // these are where off-by-one date bugs actually tend to hide.
        val boundaries = listOf(
            calendarAt(2026, Calendar.DECEMBER, 31, 23, 59),
            calendarAt(2027, Calendar.JANUARY, 1, 0, 0),
            calendarAt(2028, Calendar.FEBRUARY, 28, 23, 59),
            calendarAt(2028, Calendar.FEBRUARY, 29, 0, 0), // 2028 is a leap year
            calendarAt(2028, Calendar.MARCH, 1, 0, 0)
        )

        for (now in boundaries) {
            for (slot in Schedule.slots) {
                val triggerAt = AlarmScheduler.nextOccurrence(slot.hour, slot.minute, now)
                assertTrue(
                    "boundary ${now.time}, slot ${slot.id}: next occurrence must be in the future",
                    triggerAt > now.timeInMillis
                )
            }
        }
    }
}
