package mod.watch.face

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.mod.watch.face.FaceLabels
import tk.glucodata.mod.watch.face.FaceLayout
import tk.glucodata.mod.watch.face.FaceModel
import tk.glucodata.mod.watch.face.FacePainter
import tk.glucodata.mod.watch.face.FacePoint
import tk.glucodata.mod.watch.face.FaceReading
import tk.glucodata.mod.watch.face.FaceSnapshot
import tk.glucodata.mod.watch.face.FaceStyle
import tk.glucodata.mod.watch.face.ModFaceSource

/**
 * JugglucoNGmod, watch block (part 3b, part 7a, part 7c): the face painted into a 450 px bitmap (Galaxy Watch 5 Pro)
 * with real pixels (Robolectric native graphics) and the real colours (upstream's traffic light through
 * [ModFaceSource], their default limits 3.9 / 10.0, very low 3.0, very high 13.9 mmol/L). Black around, something in
 * every row, the whole picture inside the circle, the date and batteries at the bottom, the forecast only when there
 * is one and not in the dim mode, batteries yellow and red, the dim number moved a step a minute. Made-up values,
 * Russian labels as on the watch.
 *
 * With MOD_FACE_PREVIEW_DIR set, every scenario is also saved there as PNG: round mask and, where Wear OS draws the
 * icon of upstream's ongoing activity, the faint dot our watch build makes of it (part 7c) - to look at before
 * installing; plus the face list's 384 px preview (`mod_watch_face_preview`, unmasked).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ru")
class FacePainterTest {

    private val zone = ZoneId.of("UTC")
    private val now = LocalDateTime.of(2026, 11, 18, 19, 57).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L
    private val size = 450
    private val green = GlucoseRangeColors.VALUE_IN_RANGE_DARK
    private val yellow = GlucoseRangeColors.VALUE_BORDERLINE_DARK
    private val red = GlucoseRangeColors.VALUE_OUT_DARK
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    private fun labels() = FaceLabels.from(ApplicationProvider.getApplicationContext())

    // ------------------------------------------------------------------ scenarios

    /**
     * Six hours of one-minute readings by [curve] (minutes back -> mmol/L), the last [ageMin] minutes old, the
     * value moving at [rate] mg/dL/min; [forecast] - minutes ahead -> mmol/L every 5 minutes up to 2 h, or none.
     */
    private fun scenario(
        curve: (Int) -> Float,
        rate: Float,
        forecast: ((Int) -> Float)?,
        hours: Int = 3,
        phone: Int? = 64,
        watch: Int? = 81,
        ageMin: Int = 1,
    ): FaceSnapshot {
        val history = (360 downTo ageMin).map { back -> FacePoint(now - back * minute, curve(back)) }
        val last = history.last()
        val color = ModFaceSource.trafficColor(last.value, true)
        val current = FaceReading(last.timeMs, last.value, String.format(Locale.forLanguageTag("ru-RU"), "%.1f", last.value),
            rate, color, arrowColor = ModFaceSource.arrowColor(last.value, rate, true, color))
        val ahead = forecast?.let { f -> (5..120 step 5).map { FacePoint(now + it * minute, f(it)) } }.orEmpty()
        return FaceSnapshot(now, true, true, hours, current, history, history, 3.9f, 10f, phone, watch, ahead)
    }

    private fun calm(back: Int) = 6.2f - 0.9f * sin(back / 23.0).toFloat()

    private fun inRange() = scenario(::calm, 0.3f, forecast = { 6.4f + it * 0.004f })

    /** A rise above 10 over the last hour (the photo of 18.11), the forecast turning down after a bolus. */
    private fun highForecastDown() = scenario(
        curve = { back -> if (back > 60) calm(back) else calm(60) + (11.8f - calm(60)) * (1f - back / 60f) },
        rate = 1.6f,
        forecast = { m -> if (m <= 10) 11.8f + m * 0.03f else 12.1f - (m - 10) * 0.075f },
    )

    private fun low() = scenario(
        curve = { back -> if (back > 50) calm(back) else calm(50) + (3.4f - calm(50)) * (1f - back / 50f) },
        rate = -1.5f,
        forecast = { m -> maxOf(2.6f, 3.4f - m * 0.03f) },
    )

    private fun paint(
        snapshot: FaceSnapshot,
        ambient: Boolean,
        trial: Boolean = false,
        name: String? = null,
        px: Int = size,
        offset: Pair<Float, Float> = 0f to 0f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val model = FaceModel.build(snapshot, zone, labels())
        val limits = ModFaceSource.limits(true)
        FacePainter().draw(Canvas(bitmap), px, px, model, true, ambient, showData = true, trial = trial,
            traceColor = { ModFaceSource.trafficColor(it, true, limits) }, offsetX = offset.first, offsetY = offset.second)
        save(bitmap, name)
        return bitmap
    }

    // ------------------------------------------------------------------ preview files

    private fun save(bitmap: Bitmap, name: String?) {
        val dir = System.getenv("MOD_FACE_PREVIEW_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (name == null) return
        File(dir).mkdirs()
        val out = if (name == FACE_LIST_PREVIEW) bitmap else masked(bitmap)
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The picture as the round screen shows it, with the system's ongoing-activity icon as our watch build draws it:
     * seen on the watch at ~0.82..0.90 of the diameter, ~0.07 of it tall; our `novalue` is a dot of 1.5 / 24 of the
     * icon's box at a quarter alpha.
     */
    private fun masked(source: Bitmap): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val layout = FaceLayout.compute(bitmap.width, bitmap.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val outside = Path().apply {
            addCircle(layout.centerX, layout.centerY, layout.radius, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        paint.color = 0xFF202020.toInt()
        canvas.drawPath(outside, paint)
        val d = layout.radius * 2f
        paint.color = 0x40FFFFFF
        canvas.drawCircle(layout.centerX, layout.centerY - layout.radius + 0.86f * d, 0.07f * d * 1.5f / 24f, paint)
        return bitmap
    }

    // ------------------------------------------------------------------ pixel helpers

    private fun lit(bitmap: Bitmap, row: FaceLayout.Row? = null): Int {
        var n = 0
        val top = row?.top?.toInt()?.coerceAtLeast(0) ?: 0
        val bottom = row?.bottom?.toInt()?.coerceAtMost(bitmap.height) ?: bitmap.height
        for (y in top until bottom) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) and 0xFFFFFF != 0) n++
        }
        return n
    }

    private fun count(bitmap: Bitmap, row: FaceLayout.Row, color: Int, fromX: Int = 0, toX: Int = bitmap.width): Int {
        var n = 0
        for (y in row.top.toInt() until row.bottom.toInt()) for (x in fromX until toX) {
            if (bitmap.getPixel(x, y) == color) n++
        }
        return n
    }

    /** Lit pixels outside the circle less [inset] px. */
    private fun litOutside(bitmap: Bitmap, inset: Float): Int {
        val layout = FaceLayout.compute(bitmap.width, bitmap.height)
        var n = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (hypot(x + 0.5f - layout.centerX, y + 0.5f - layout.centerY) > layout.radius - inset &&
                bitmap.getPixel(x, y) and 0xFFFFFF != 0) n++
        }
        return n
    }

    private val forecastColor = FaceStyle.of(false).forecast!!

    // ------------------------------------------------------------------ tests

    @Test
    fun blackAroundSomethingInEveryRowAndAllInsideTheCircle() {
        val bitmap = paint(inRange(), ambient = false, name = "7e_in_range")
        val layout = FaceLayout.compute(size, size)
        for ((x, y) in listOf(1 to 1, size - 2 to 1, 1 to size - 2, size - 2 to size - 2, size / 2 to 2, size / 2 to size - 2)) {
            assertEquals("pixel $x,$y", FaceStyle.BLACK, bitmap.getPixel(x, y))
        }
        listOf(layout.time, layout.value, layout.graph, layout.hours, layout.status).forEach {
            assertTrue("row ${it.top}..${it.bottom} is empty", lit(bitmap, it) > 50)
        }
        assertEquals(0, lit(bitmap, layout.mark))
        assertEquals(0, litOutside(bitmap, 0.01f * size))
        // in range: the number and the dots green, the arrow too (heading nowhere out of the range)
        assertTrue(count(bitmap, layout.value, green) > 200)
        assertTrue(count(bitmap, layout.graph, green) > 50)
        // the face list's preview, as before part 7a
        paint(inRange(), ambient = false, name = FACE_LIST_PREVIEW, px = 384)
    }

    @Test
    fun highWithTheForecastDownTheValueYellowTheArrowRed() {
        val layout = FaceLayout.compute(size, size)
        val bitmap = paint(highForecastDown(), ambient = false, name = "7e_high_forecast_down")
        assertTrue(count(bitmap, layout.value, yellow) > 200)
        // 11.8 rising 1.6 mg/dL/min: past very high (13.9) in 30 minutes - upstream's "out" arrow
        assertTrue(count(bitmap, layout.value, red) > 20)
        // the forecast dashed in the right fifth of the graph, only there
        val rightFifth = (layout.graph.left + 0.79f * layout.graph.width).toInt()
        assertTrue(count(bitmap, layout.graph, forecastColor, rightFifth) > 10)
        assertEquals(count(bitmap, layout.graph, forecastColor, rightFifth), count(bitmap, layout.graph, forecastColor))
    }

    @Test
    fun lowTheValueYellowAndRedDotsBelowTheRange() {
        val layout = FaceLayout.compute(size, size)
        val bitmap = paint(low(), ambient = false, name = "7e_low")
        // 3.4 is between very low (3.0) and low (3.9): yellow, as the app paints it
        assertTrue(count(bitmap, layout.value, yellow) > 200)
        assertTrue(count(bitmap, layout.graph, yellow) > 10)
    }

    @Test
    fun theRangeLabelsStayReadableOverDots() {
        // part 7c: dots right on a label at the left end (the oldest half hour just above 10.0, then just above
        // 3.9) - the labels are painted after the dots with a black edge, so they keep about as many pixels as over
        // an empty graph
        val layout = FaceLayout.compute(size, size)
        val labelColor = FaceStyle.of(false).secondary
        val box = FaceLayout.Row(layout.graph.left, layout.graph.top, layout.graph.left + 0.12f * size,
            layout.graph.bottom, 0f)
        fun labelPixels(b: Bitmap) = count(b, box, labelColor, box.left.toInt(), box.right.toInt())
        val inEmpty = labelPixels(paint(scenario({ 6.5f }, 0f, forecast = null), ambient = false))
        assertTrue("labels $inEmpty", inEmpty > 10)
        for ((value, name) in listOf(10.25f to "7e_labels_over_dots", 4.15f to null)) {
            val covered = paint(scenario({ back -> if (back >= 150) value else 6.5f }, 0f, forecast = null),
                ambient = false, name = name)
            val inCovered = labelPixels(covered)
            assertTrue("labels over dots at $value: $inCovered of $inEmpty", inCovered >= inEmpty * 0.8f)
        }
    }

    @Test
    fun withoutAForecastNoDashedLineAndTheGraphEndsAtNow() {
        val layout = FaceLayout.compute(size, size)
        val off = paint(scenario(::calm, 0.3f, forecast = null), ambient = false, name = "7e_no_forecast")
        assertEquals(0, count(off, layout.graph, forecastColor))
        val on = paint(inRange(), ambient = false)
        assertTrue(count(on, layout.graph, forecastColor) > 10)
        paint(scenario(::calm, 0.3f, forecast = { 6.4f }, hours = 6), ambient = false, name = "7e_six_hours")
    }

    @Test
    fun theDimModeHasNoForecastNoLabelsNoNormalBatteriesARegularNumberAndLightsFewerPixels() {
        val layout = FaceLayout.compute(size, size)
        val normal = paint(highForecastDown(), ambient = false)
        val ambient = paint(highForecastDown(), ambient = true, name = "7e_ambient")
        assertTrue(lit(ambient) < lit(normal))
        // the number regular, not bold: fewer of its pixels lit (part 7c, critic 7, S2)
        assertTrue(lit(ambient, layout.value) < lit(normal, layout.value) * 0.9f)
        assertEquals(FaceStyle.BLACK, ambient.getPixel(1, 1))
        assertEquals(0, count(ambient, layout.graph, forecastColor))
        assertEquals(0, count(ambient, layout.graph, FaceStyle.dim(forecastColor, FaceStyle.AMBIENT_DIM)))
        assertEquals(0, lit(ambient, layout.hours))
        // the date stays at the bottom
        assertTrue(lit(ambient, layout.status) > 20)
    }

    @Test
    fun theDimModeStepMovesTheWholePictureAndKeepsItInside() {
        val step = FaceLayout.burnInOffset(1L, size.toFloat(), strong = true)
        assertTrue(step.first > 0f && step.second == 0f)
        val still = paint(highForecastDown(), ambient = true)
        val moved = paint(highForecastDown(), ambient = true, offset = step, name = "7e_ambient_moved")
        val dx = step.first.toInt()
        // a column of the number: lit pixels move right by the step
        val y0 = FaceLayout.compute(size, size).value.top.toInt()
        val y1 = FaceLayout.compute(size, size).value.bottom.toInt()
        var same = 0
        var total = 0
        for (y in y0 until y1) for (x in 0 until size - dx) {
            if (still.getPixel(x, y) and 0xFFFFFF != 0) {
                total++
                if (moved.getPixel(x + dx, y) == still.getPixel(x, y)) same++
            }
        }
        assertTrue("$same of $total", total > 100 && same >= total * 0.9f)
        assertEquals(0, litOutside(moved, 0f))
    }

    @Test
    fun batteriesYellowUnderHalfRedUnder30AndOnlyRedInTheDimMode() {
        val layout = FaceLayout.compute(size, size)
        val style = FaceStyle.of(false)
        val half = size / 2
        // the owner's word of part 7c: the phone at 45 % yellow, the watch at 18 % red
        val lowBatteries = inRange().copy(phoneBatteryPercent = 45, watchBatteryPercent = 18)
        val bitmap = paint(lowBatteries, ambient = false, name = "7e_low_batteries")
        assertTrue("phone yellow", count(bitmap, layout.status, style.batteryWarn, 0, half) > 10)
        assertEquals(0, count(bitmap, layout.status, style.batteryLow, 0, half))
        assertTrue("watch red", count(bitmap, layout.status, style.batteryLow, half) > 10)
        assertEquals(0, count(bitmap, layout.status, style.batteryWarn, half))
        // 64 and 81: neither
        val fine = paint(inRange(), ambient = false)
        assertEquals(0, count(fine, layout.status, style.batteryLow) + count(fine, layout.status, style.batteryWarn))
        // the dim mode: only the red one, dimmed
        val dim = FaceStyle.of(true)
        val ambient = paint(lowBatteries, ambient = true, name = "7e_low_batteries_ambient")
        assertTrue(count(ambient, layout.status, dim.batteryLow, half) > 10)
        assertEquals(0, count(ambient, layout.status, dim.batteryWarn))
        val dimNormal = paint(inRange(), ambient = true)
        val noBatteries = paint(inRange().copy(phoneBatteryPercent = null, watchBatteryPercent = null), ambient = true)
        assertEquals(lit(noBatteries, layout.status), lit(dimNormal, layout.status))
        // unknown: no icon in the normal mode either
        val unknown = paint(inRange().copy(phoneBatteryPercent = null, watchBatteryPercent = null), ambient = false)
        assertTrue(lit(unknown, layout.status) < lit(fine, layout.status))
    }

    @Test
    fun theSystemDotFallsBetweenTheDayAndTheMonthAndFullBatteriesStayInside() {
        // part 7e: Wear OS draws the faint dot of upstream's ongoing activity at the centre, ~0.86 of the diameter;
        // the date's gap is there, so no glyph is under it - also with both batteries at 100 (the widest row)
        for (snapshot in listOf(inRange(), inRange().copy(phoneBatteryPercent = 100, watchBatteryPercent = 100))) {
            val bitmap = paint(snapshot, ambient = false)
            val cx = size / 2
            val cy = (0.86f * size).toInt()
            for (y in cy - 3..cy + 3) for (x in cx - 2..cx + 2) {
                assertEquals("pixel $x,$y under the dot", FaceStyle.BLACK, bitmap.getPixel(x, y))
            }
            // the date's words on both sides of it, on the row's middle line
            val mid = FaceLayout.compute(size, size).status.centerY.toInt()
            val litLeft = (cx - 40 until cx - 3).count { bitmap.getPixel(it, mid) and 0xFFFFFF != 0 }
            val litRight = (cx + 3 until cx + 40).count { bitmap.getPixel(it, mid) and 0xFFFFFF != 0 }
            assertTrue("day $litLeft, month $litRight", litLeft > 0 && litRight > 0)
            assertEquals(0, litOutside(bitmap, 0.01f * size))
        }
        paint(inRange().copy(phoneBatteryPercent = 100, watchBatteryPercent = 100), ambient = false,
            name = "7e_full_batteries")
    }

    @Test
    fun theTrialMarkOnlyForATrialUnderTheStatusRow() {
        val layout = FaceLayout.compute(size, size)
        val trial = paint(inRange(), ambient = false, trial = true, name = "7e_trial")
        assertTrue(lit(trial, layout.mark) > 20)
        assertEquals(0, litOutside(trial, 0.01f * size))
    }

    @Test
    fun oldGoneAndNoDataStillPaint() {
        val layout = FaceLayout.compute(size, size)
        val old = paint(scenario(::calm, 0.3f, forecast = { 6.4f }, ageMin = 15), ambient = false, name = "7e_old")
        assertEquals(0, count(old, layout.graph, forecastColor))
        val gone = paint(scenario(::calm, 0.3f, forecast = null, ageMin = 25), ambient = false)
        assertTrue(lit(gone, layout.value) > 50)
        val none = paint(FaceSnapshot(now, true, true, 3, null, emptyList(), emptyList(), 3.9f, 10f, null, null),
            ambient = true, name = "7e_no_data")
        assertTrue(lit(none, layout.value) > 10)
    }

    companion object {
        /** The face list's picture (`drawable-nodpi/mod_watch_face_preview.png`), made by hand from this test. */
        const val FACE_LIST_PREVIEW = "mod_watch_face_preview"
    }
}
