package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.HealthConfig
import tk.glucodata.mod.health.HrAggregator
import tk.glucodata.mod.health.ModHealthBatch

/** JugglucoNGmod, watch block (part 4): 5-minute heart rate means (ported from Glucove). Made-up values. */
class HrAggregatorTest {

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L // on a 5-minute boundary

    @Test
    fun averagesPerFiveMinutesAlignedToClockAndSkipsNoise() {
        val a = HrAggregator()
        a.add(t0 + 10_000, 60f)
        a.add(t0 + 20_000, 80f)
        a.add(t0 + 30_000, 0f)
        a.add(t0 + 35_000, Float.NaN)
        a.add(t0 + 40_000, 120f, noContact = true)
        a.add(t0 + 5 * min + 1_000, 90f) // the next window already
        val closed = a.close(nowMs = t0 + 5 * min + 10_000, graceMs = 5_000)
        assertEquals(1, closed.size)
        val s = closed.single()
        assertEquals(ModHealthBatch.KIND_HR5, s.kind)
        assertEquals(t0, s.timeMs)
        assertEquals(70f, s.value)
        assertEquals("n=2;min=60;max=80", s.extra)
        assertEquals(1, a.openCount)
    }

    @Test
    fun openWindowWaitsForGrace() {
        val a = HrAggregator()
        a.add(t0 + 10_000, 70f)
        assertTrue(a.close(nowMs = t0 + 5 * min + 2_000, graceMs = 5_000).isEmpty())
        assertEquals(1, a.close(nowMs = t0 + 5 * min + 6_000, graceMs = 5_000).size)
    }

    @Test
    fun normalDeliveryWaitsTwoMinutesDeliverNowFiveSeconds() {
        val a = HrAggregator()
        a.add(t0 + 10_000, 72f)
        val now = t0 + 5 * min + 30_000
        assertTrue(a.close(now, HealthConfig.HR_GRACE_DATA_MS).isEmpty())
        assertEquals(1, a.close(now, HealthConfig.HR_GRACE_REFRESH_MS).size)
    }

    @Test
    fun survivesProcessRestartViaEncode() {
        val a = HrAggregator()
        a.add(t0 + 1_000, 64f)
        a.add(t0 + 2_000, 66f)
        val b = HrAggregator.decode(a.encode())
        b.add(t0 + 3_000, 68f)
        val s = b.close(nowMs = t0 + 6 * min, graceMs = 0).single()
        assertEquals(66f, s.value)
        assertEquals("n=3;min=64;max=68", s.extra)
    }

    @Test
    fun readingForAClosedWindowIsLateAndGoesAsASecondRow() {
        val a = HrAggregator()
        assertFalse(a.add(t0 + 1_000, 60f))
        assertEquals(1, a.close(nowMs = t0 + 7 * min, graceMs = HealthConfig.HR_GRACE_DATA_MS).size)
        assertEquals(t0 + 5 * min, a.closedUntil)
        assertTrue(a.add(t0 + 4 * min, 80f)) // late: its window went already
        assertFalse(a.add(t0 + 5 * min, 70f)) // the next window is open
        assertFalse(a.add(t0 + 4 * min, 0f)) // skipped readings are never late
        val again = a.close(nowMs = t0 + 20 * min, graceMs = 0)
        assertEquals(listOf(t0, t0 + 5 * min), again.map { it.timeMs }) // the phone merges the two t0 rows by n
        assertEquals("n=1;min=80;max=80", again[0].extra)
        // closedUntil survives a restart
        assertTrue(HrAggregator.decode(a.encode(), a.closedUntil).add(t0 + 9 * min, 75f))
    }

    @Test
    fun brokenStateIsIgnored() {
        assertEquals(0, HrAggregator.decode("").openCount)
        assertEquals(0, HrAggregator.decode("abc|1:2").openCount)
        assertEquals(0, HrAggregator.decode("1:2:0:3:4").openCount) // zero count
    }
}
