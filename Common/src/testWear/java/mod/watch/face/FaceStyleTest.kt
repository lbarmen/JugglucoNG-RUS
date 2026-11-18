package mod.watch.face

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceStyle

/** JugglucoNGmod, watch block (part 3b): the face's colours and line weights, normal and dim (ambient). */
class FaceStyleTest {

    private val normal = FaceStyle.of(ambient = false)
    private val ambient = FaceStyle.of(ambient = true)

    private fun brightness(color: Int) = ((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)

    @Test
    fun theBackgroundIsAlwaysBlack() {
        assertEquals(FaceStyle.BLACK, normal.background)
        assertEquals(FaceStyle.BLACK, ambient.background)
    }

    @Test
    fun ambientKeepsThePictureWithoutForecastOrLabelsAndWithThinnerLines() {
        // part 7a: the forecast and the graph's labels only in the normal mode
        assertNotNull(normal.forecast)
        assertNotNull(normal.hourLabel)
        assertNull(ambient.forecast)
        assertNull(ambient.hourLabel)
        assertTrue(ambient.dotRadius < normal.dotRadius)
        assertTrue(ambient.rangeLineWidth < normal.rangeLineWidth)
        assertTrue(ambient.arrowStroke < normal.arrowStroke)
        assertTrue(ambient.dotRadius > 0f && ambient.rangeLineWidth > 0f && ambient.arrowStroke > 0f)
    }

    @Test
    fun everyAmbientColourIsDimmer() {
        val pairs = listOf(
            normal.time to ambient.time, normal.secondary to ambient.secondary, normal.age to ambient.age,
            normal.ageAlarm to ambient.ageAlarm, normal.neutral to ambient.neutral,
            normal.rangeLine to ambient.rangeLine, normal.mark to ambient.mark,
            normal.battery to ambient.battery, normal.batteryLow to ambient.batteryLow,
        )
        pairs.forEach { (n, a) -> assertTrue("$n vs $a", brightness(a) < brightness(n)) }
        val green = 0xFF33CC66.toInt()
        assertEquals(green, normal.valueColor(green))
        assertTrue(brightness(ambient.valueColor(green)) < brightness(green))
        assertTrue(brightness(ambient.traceColor(green)) < brightness(green))
    }

    @Test
    fun theAgeAlarmAndALowBatteryAreRed() {
        for (c in listOf(normal.ageAlarm, normal.batteryLow)) {
            assertTrue(((c shr 16) and 0xFF) > 200 && ((c shr 8) and 0xFF) < 120 && (c and 0xFF) < 120)
        }
    }

    @Test
    fun noRangeColourGivesTheNeutralTone() {
        assertEquals(normal.neutral, normal.valueColor(null))
        assertEquals(ambient.neutral, ambient.valueColor(null))
    }

    @Test
    fun dimScalesChannelsAndKeepsAlpha() {
        // rounded to the nearest: 255 -> 128, 127 -> 64, 63 -> 32
        assertEquals(0xFF804020.toInt(), FaceStyle.dim(0xFFFF7F3F.toInt(), 0.5f))
        assertEquals(0x80000000.toInt(), FaceStyle.dim(0x80FFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), FaceStyle.dim(0xFFFFFFFF.toInt(), 1.5f))
    }

    @Test
    fun rangeLabelsAsTheAppWritesValues() {
        assertEquals("10,0", FaceStyle.rangeLabel(10f, true, Locale.forLanguageTag("ru-RU")))
        assertEquals("3.9", FaceStyle.rangeLabel(3.9f, true, Locale.US))
        assertEquals("180", FaceStyle.rangeLabel(180f, false, Locale.US))
    }
}
