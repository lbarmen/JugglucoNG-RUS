package mod.watch.face

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceLayout
import tk.glucodata.mod.watch.face.FaceValues

/**
 * JugglucoNGmod, watch block (part 3a): the face's rows on round screens (384 x 384 - Galaxy Watch 4/5 44 mm class,
 * 450 x 450 - Galaxy Watch 5 Pro): inside the circle, in order, and the widest texts fit by a generous estimate.
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
            val names = listOf("time", "date", "value", "graph", "hours", "battery", "mark")
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
    fun theWidestTextsFit() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            assertFits(FaceLayout.estimatedWidth("23:59", l.time.textSize), l.time, "24 h clock on $size")
            // 12-hour clock: the day part is drawn at 0.4 of the clock's size, as upstream's face does
            assertFits(FaceLayout.estimatedWidth("12:59", l.time.textSize) +
                FaceLayout.estimatedWidth("pm", l.time.textSize * 0.4f), l.time, "12 h clock on $size")
            for (date in listOf("Wed 30 Sep", "Пн 30 сен")) {
                assertFits(FaceLayout.estimatedWidth(date, l.date.textSize), l.date, "date $date on $size")
            }
            // mmol and mg/dL extremes, the longest change and age, English and Russian
            for (value in listOf("27,8", "500", FaceValues.NONE)) {
                for (age in listOf("20 min", "20 мин", "999 min")) {
                    assertFits(FaceLayout.valueRowWidth(l, value, "−10.5", age), l.value, "$value $age on $size")
                }
            }
            for (battery in listOf("phone 100%  watch 100%", "телефон 100 %  " +
                "часы 100 %")) {
                assertFits(FaceLayout.estimatedWidth(battery, l.battery.textSize), l.battery, "$battery on $size")
            }
            // range labels at the graph's right edge leave room for the trace
            assertTrue(FaceLayout.estimatedWidth("10,0", l.graphLabelSize) < l.graph.width / 4f)
        }
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
    fun aTapOnTheValueButNotOnTheTimeOrTheGraph() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            val v = l.value
            assertTrue(FaceLayout.isOnValue(l, v.centerX, (v.top + v.bottom) / 2f))
            assertTrue(FaceLayout.isOnValue(l, v.left + 1f, v.bottom + v.height * 0.2f))
            assertFalse(FaceLayout.isOnValue(l, l.time.centerX, (l.time.top + l.time.bottom) / 2f))
            assertFalse(FaceLayout.isOnValue(l, l.graph.centerX, (l.graph.top + l.graph.bottom) / 2f))
            assertFalse(FaceLayout.isOnValue(l, v.left - 5f, (v.top + v.bottom) / 2f))
        }
    }

    @Test
    fun aNonSquareScreenUsesTheCentredCircle() {
        val layout = FaceLayout.compute(480, 400)
        assertTrue(layout.centerX == 240f && layout.centerY == 200f && layout.radius == 200f)
        layout.rows.forEach { assertInsideCircle(layout, it, "row on 480x400") }
    }
}
