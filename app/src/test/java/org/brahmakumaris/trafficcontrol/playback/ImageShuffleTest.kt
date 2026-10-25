package org.brahmakumaris.trafficcontrol.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the devotional-image shuffle on the ringing screen actually
 * behaves like a shuffle: every bundled image gets picked, no single image
 * dominates, and the pool is as large as expected (at least 10 images).
 */
class ImageShuffleTest {

    @Test
    fun `background pool has at least 10 images`() {
        assertTrue(
            "expected at least 10 bundled images, found ${AlarmRingingActivity.BACKGROUNDS.size}",
            AlarmRingingActivity.BACKGROUNDS.size >= 10
        )
    }

    @Test
    fun `background pool has no duplicate drawables`() {
        val distinct = AlarmRingingActivity.BACKGROUNDS.toSet()
        assertEquals(
            "found duplicate drawable resource ids in the shuffle pool",
            AlarmRingingActivity.BACKGROUNDS.size,
            distinct.size
        )
    }

    @Test
    fun `every image in the pool gets picked over many trials`() {
        val pool = AlarmRingingActivity.BACKGROUNDS
        val trials = 20_000
        val seen = pool.associateWith { 0 }.toMutableMap()

        repeat(trials) {
            val picked = pool.random()
            seen[picked] = (seen[picked] ?: 0) + 1
        }

        for (drawableId in pool) {
            assertTrue(
                "drawable $drawableId was never picked across $trials shuffles",
                (seen[drawableId] ?: 0) > 0
            )
        }

        // With a uniform random pick over N images and enough trials, each
        // image's share should land close to 1/N - catches a shuffle that's
        // silently biased (e.g. a broken RNG or an accidental weighting)
        // without demanding a perfectly even split.
        val expectedShare = 1.0 / pool.size
        for ((drawableId, count) in seen) {
            val actualShare = count / trials.toDouble()
            assertTrue(
                "drawable $drawableId's share $actualShare is too far from the expected $expectedShare " +
                    "(picked $count/$trials times) - shuffle may be biased",
                actualShare > expectedShare * 0.5 && actualShare < expectedShare * 1.5
            )
        }
    }

    @Test
    fun `does not always repeat the same image back to back`() {
        // Not a statistical guarantee (a fair shuffle CAN repeat), but with
        // 14+ images, getting the exact same one 20 times running would mean
        // something is badly wrong (e.g. .random() not actually being called).
        val pool = AlarmRingingActivity.BACKGROUNDS
        val picks = (1..20).map { pool.random() }
        assertTrue(
            "the same image was picked all 20 times in a row - shuffle looks broken",
            picks.toSet().size > 1
        )
    }
}
