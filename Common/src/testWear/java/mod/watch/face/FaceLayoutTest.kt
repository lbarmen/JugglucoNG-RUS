package mod.watch.face

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceLayout
import tk.glucodata.mod.watch.face.FaceValues

/**
 * JugglucoNGmod, watch block (part 3a, part 7a): the face's rows on round screens (384 x 384 - Galaxy Watch 4/5 44 mm
 * class, 450 x 450 - Galaxy Watch 5 Pro): inside the circle, in order, clear of the system's icon at the bottom, and
 * the texts fit by a generous estimate.
 */
class FaceLayoutTest {

    private val sizes = listOf(384, 450)

    private fun assertInsideCircle(layout: FaceLayout.Layout, row: FaceLayout.Row, name: String) {
        for (x in listOf(row.left, row.right)) for (y in listOf(row.top, row.bottom)) {
            val d = hypot(x - layout.centerX, y - layout.centerY)
            assertTrue("$name corner ($x, $y) at $d outside radius ${layout.radius}", d <= layout.radius)
        }
    }

    private fun assertFits(width: Float, row: FaceLayout.Row, what: String) =
        assertTrue("$what: $width wider than ${row.width}", width <= row.width)

    @Test
    fun everyRowIsInsideTheCircleOnBothSizes() {
        for (size in sizes) {
            val layout = FaceLayout.compute(size, size)
            val names = listOf("time", "status", "value", "graph", "hours", "mark")
            layout.rows.forEachIndexed { i, row ->
                assertTrue("${names[i]} empty on $size", row.width > 0f && row.height > 0f)
                assertInsideCircle(layout, row, "${names[i]} on $size")
                assertTrue("${names[i]} text taller than its row", row.textSize <= row.height + 0.001f)
            }
        }
    }

    @Test
    fun rowsGoTopToBottomWithoutOverlapAndTheirHeightsFitTheDiameter() {
        for (size in sizes) {
            val layout = FaceLayout.compute(size, size)
            layout.rows.zipWithNext().forEach { (a, b) -> assertTrue("overlap on $size", a.bottom <= b.top) }
            assertTrue(layout.rows.sumOf { it.height.toDouble() } <= size)
            assertTrue(layout.rows.first().top >= 0f && layout.rows.last().bottom <= size)
            assertTrue(layout.arrowSize <= layout.value.height)
            assertTrue(2 * layout.sideTextSize <= layout.value.height)
        }
    }

    @Test
    fun nothingButTheTrialMarkReachesTheSystemIconsBand() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            assertEquals(FaceLayout.SYSTEM_TOP * size, l.system.top, 0.01f)
            assertEquals(FaceLayout.SYSTEM_BOTTOM * size, l.system.bottom, 0.01f)
            listOf(l.time, l.status, l.value, l.graph, l.hours).forEach {
                assertTrue("row ${it.top}..${it.bottom} in the system band on $size", it.bottom <= l.system.top)
            }
            assertTrue(l.mark.top >= l.system.bottom)
        }
    }

    @Test
    fun theWholeCircleIsUsedTheValueIsLargeAndTheGraphWide() {
        // the decision of part 7a on 450 px: value ~90 px, graph ~348 x 153 px
        val l = FaceLayout.compute(450, 450)
        assertEquals(90f, l.value.textSize, 1f)
        assertEquals(348f, l.graph.width, 3f)
        assertEquals(153f, l.graph.height, 2f)
        assertTrue(l.time.textSize < l.value.textSize / 2f)
    }

    @Test
    fun theTargetLinesReachTheRoundEdgeButNotPastIt() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            for (y in listOf(l.graph.top, l.graph.centerY, l.graph.bottom)) {
                val half = FaceLayout.halfWidthAt(l, y, 0.03f * size)
                assertTrue(half >= l.graph.width / 2f)
                assertTrue(hypot(half, y - l.centerY) <= l.radius)
                assertTrue(hypot(half, y - l.centerY) >= l.radius - 0.031f * size)
            }
            assertEquals(0f, FaceLayout.halfWidthAt(l, -10f, 0f), 0f)
        }
    }

    @Test
    fun theTextsFit() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            assertFits(FaceLayout.estimatedWidth("23:59", l.time.textSize), l.time, "24 h clock on $size")
            // 12-hour clock: the day part is drawn at 0.4 of the clock's size, as upstream's face does
            assertFits(FaceLayout.estimatedWidth("12:59", l.time.textSize) +
                FaceLayout.estimatedWidth("pm", l.time.textSize * 0.4f), l.time, "12 h clock on $size")
            // the status row: icon (at most 0.62 of the row's height), gap, "100" on each side of the date
            val battery = 0.62f * l.status.height + 0.01f * size + FaceLayout.estimatedWidth("100", l.status.textSize)
            for (date in listOf("Wed 30", "Пн 30")) {
                val total = FaceLayout.estimatedWidth(date, l.status.textSize) + 2 * (battery + 0.035f * size)
                assertTrue("status $date on $size: $total in ${l.status.width}", total <= l.status.width)
            }
            // three characters fit at full size even by the generous estimate; four are scaled down together, but
            // not much (measured digits and the comma are narrower, so the real scale is closer to 1)
            for (value in listOf("9,8", "180")) {
                assertFits(FaceLayout.valueRowWidth(l, value, "+0,5", "20 мин"), l.value, "$value on $size")
            }
            for (value in listOf("12,4", "27,8", "500", FaceValues.NONE)) {
                for (age in listOf("20 min", "20 мин", "999 min")) {
                    val width = FaceLayout.valueRowWidth(l, value, "−10.5", age)
                    assertTrue("$value $age on $size", l.value.width / width >= 0.75f)
                }
            }
            // range labels at the graph's left end leave room for the dots
            assertTrue(FaceLayout.estimatedWidth("10,0", l.graphLabelSize) < l.graph.width / 4f)
        }
    }

    @Test
    fun theStatusRowCentresTheDateAndKeepsTheBatteriesInside() {
        val row = FaceLayout.compute(450, 450).status
        val place = FaceLayout.placeStatus(row, 60f, 50f, 50f, 15f)
        assertEquals(row.centerX - 30f, place.dateX, 0.01f)
        assertEquals(place.dateX - 15f - 50f, place.phoneX, 0.01f)
        assertEquals(place.dateX + 60f + 15f, place.watchX, 0.01f)
        // far too wide: pushed back to the row's edges
        val wide = FaceLayout.placeStatus(row, 60f, 400f, 400f, 15f)
        assertEquals(row.left, wide.phoneX, 0.01f)
        assertEquals(row.right - 400f, wide.watchX, 0.01f)
    }

    @Test
    fun theValueRowIsCentredAndShrinksTogetherWhenTooWide() {
        val row = FaceLayout.compute(384, 384).value
        val fits = FaceLayout.placeValueRow(row, 100f, 30f, 50f, 10f)
        assertEquals(1f, fits.scale, 0.0001f)
        val total = 100f + 10f + 30f + 10f + 50f
        assertEquals(row.centerX - total / 2f, fits.valueX, 0.01f)
        assertEquals(fits.valueX + 110f, fits.arrowX, 0.01f)
        assertEquals(fits.arrowX + 40f, fits.sideX, 0.01f)
        // without an arrow the change follows the number after one gap
        val noArrow = FaceLayout.placeValueRow(row, 100f, 0f, 50f, 10f)
        assertEquals(noArrow.valueX + 110f, noArrow.sideX, 0.01f)
        // far too wide: scaled to the row exactly, still centred
        val wide = FaceLayout.placeValueRow(row, 600f, 60f, 200f, 20f)
        assertTrue(wide.scale < 1f)
        assertEquals(row.left, wide.valueX, 0.01f)
        assertEquals(row.right, wide.sideX + 200f * wide.scale, 0.01f)
    }

    @Test
    fun aTapOnTheValueButNotOnTheTimeTheStatusOrTheGraph() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            val v = l.value
            assertTrue(FaceLayout.isOnValue(l, v.centerX, v.centerY))
            assertTrue(FaceLayout.isOnValue(l, v.left + 1f, v.bottom + v.height * 0.04f))
            assertFalse(FaceLayout.isOnValue(l, l.time.centerX, l.time.centerY))
            assertFalse(FaceLayout.isOnValue(l, l.status.centerX, l.status.centerY))
            assertFalse(FaceLayout.isOnValue(l, l.graph.centerX, l.graph.top + 0.2f * l.graph.height))
            assertFalse(FaceLayout.isOnValue(l, v.left - 5f, v.centerY))
        }
    }

    @Test
    fun aNonSquareScreenUsesTheCentredCircle() {
        val layout = FaceLayout.compute(480, 400)
        assertTrue(layout.centerX == 240f && layout.centerY == 200f && layout.radius == 200f)
        layout.rows.forEach { assertInsideCircle(layout, it, "row on 480x400") }
    }
}
