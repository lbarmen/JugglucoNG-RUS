package mod.watch.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceGraph
import tk.glucodata.mod.watch.face.FacePoint

/** JugglucoNGmod, watch block (part 3a): the face graph's window and vertical scale. Made-up values. */
class FaceGraphTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun assertInView(plot: FaceGraph.Plot, vararg values: Float) {
        for (v in values) assertTrue("$v in ${plot.bottom}..${plot.top}", v > plot.bottom && v < plot.top)
    }

    @Test
    fun anEmptyGraphStillShowsTheRangeLines() {
        val plot = FaceGraph.plot(emptyList(), now, 3, 3.9f, 10.0f, isMmol = true)
        assertTrue(plot.points.isEmpty())
        assertInView(plot, 3.9f, 10.0f)
        assertEquals(now - 3 * 60 * minute, plot.fromMs)
        assertEquals(now, plot.toMs)
    }

    @Test
    fun oneValueAndTheRangeAreInView() {
        val plot = FaceGraph.plot(listOf(FacePoint(now - 10 * minute, 6.2f)), now, 3, 3.9f, 10.0f, isMmol = true)
        assertEquals(1, plot.points.size)
        assertInView(plot, 3.9f, 6.2f, 10.0f)
    }

    @Test
    fun everythingAboveTheRangeKeepsTheRangeAndTheTraceInView() {
        val points = (0..30).map { FacePoint(now - it * 5 * minute, 15f + it % 4) }
        val plot = FaceGraph.plot(points, now, 3, 3.9f, 10.0f, isMmol = true)
        assertInView(plot, 3.9f, 10.0f, 15f, 18f)
        // higher on the screen is a smaller y
        assertTrue(FaceGraph.yFraction(plot, 18f) < FaceGraph.yFraction(plot, 10f))
        assertTrue(FaceGraph.yFraction(plot, 10f) < FaceGraph.yFraction(plot, 3.9f))
        for (p in plot.points) {
            assertTrue(FaceGraph.yFraction(plot, p.value) in 0f..1f)
            assertTrue(FaceGraph.xFraction(plot, p.timeMs) in 0f..1f)
        }
    }

    @Test
    fun aCalmTraceInMgdlGetsUpstreamsLeastSpan() {
        val points = (0..10).map { FacePoint(now - it * minute, 100f + it % 2) }
        val plot = FaceGraph.plot(points, now, 1, 95f, 105f, isMmol = false)
        assertTrue(plot.top - plot.bottom >= 54f)
        assertInView(plot, 95f, 105f)
        assertTrue(plot.bottom >= 0f)
    }

    @Test
    fun theWindowIsOneThreeOrSixHours() {
        val points = listOf(5, 2 * 60, 4 * 60, 7 * 60).map { FacePoint(now - it * minute, 6f) }
        assertEquals(1, FaceGraph.plot(points, now, 1, 3.9f, 10f, true).points.size)
        assertEquals(2, FaceGraph.plot(points, now, 3, 3.9f, 10f, true).points.size)
        assertEquals(3, FaceGraph.plot(points, now, 6, 3.9f, 10f, true).points.size)
        assertEquals(3, FaceGraph.sanitizeHours(2))
        assertEquals(3, FaceGraph.sanitizeHours(0))
        assertEquals(6, FaceGraph.sanitizeHours(6))
        assertEquals(2, FaceGraph.plot(points, now, 4, 3.9f, 10f, true).points.size)
    }

    @Test
    fun brokenValuesAreLeftOutAndPointsComeOldestFirst() {
        val points = listOf(FacePoint(now - minute, 6f), FacePoint(now - 3 * minute, Float.NaN),
            FacePoint(now - 2 * minute, 0f), FacePoint(now - 5 * minute, 5f))
        val plot = FaceGraph.plot(points, now, 1, 3.9f, 10f, true)
        assertEquals(listOf(now - 5 * minute, now - minute), plot.points.map { it.timeMs })
    }

    @Test
    fun aPointAheadOfTheWatchClockSitsAtTheRightEdge() {
        val plot = FaceGraph.plot(listOf(FacePoint(now + 2 * minute, 6f)), now, 3, 3.9f, 10f, true)
        assertEquals(1, plot.points.size)
        assertEquals(1f, FaceGraph.xFraction(plot, now + 2 * minute))
        assertEquals(0f, FaceGraph.xFraction(plot, now - 3 * 60 * minute))
    }
}
