package mod.watch.face

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
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
import tk.glucodata.mod.watch.face.FaceLabels
import tk.glucodata.mod.watch.face.FaceLayout
import tk.glucodata.mod.watch.face.FaceModel
import tk.glucodata.mod.watch.face.FacePainter
import tk.glucodata.mod.watch.face.FacePoint
import tk.glucodata.mod.watch.face.FaceReading
import tk.glucodata.mod.watch.face.FaceSnapshot
import tk.glucodata.mod.watch.face.FaceStyle

/**
 * JugglucoNGmod, watch block (part 3b): the face painted into a bitmap with real pixels (Robolectric native
 * graphics) - black around, something in every row, the dim mode lighter on pixels, the "TEST" mark only for a
 * trial. Made-up values. With MOD_FACE_PREVIEW_DIR set, the pictures are also saved there as PNG (the face list's
 * preview is made so).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class FacePainterTest {

    private val zone = ZoneId.of("UTC")
    private val now = LocalDateTime.of(2026, 11, 17, 10, 8).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L
    private val size = 384
    private val green = 0xFF4CAF50.toInt()
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    private fun labels() = FaceLabels.from(ApplicationProvider.getApplicationContext())

    /** A calm made-up curve around 6.4 mmol/L, rising at the end, the last reading [ageMin] minutes old. */
    private fun snapshot(ageMin: Int?, empty: Boolean = false): FaceSnapshot {
        val history = if (empty) emptyList() else (180 downTo (ageMin ?: 0)).map { back ->
            FacePoint(now - back * minute, 6.4f - 1.6f * sin(back / 35.0).toFloat())
        }
        val current = ageMin?.let { history.lastOrNull() }?.let {
            FaceReading(it.timeMs, it.value, String.format(Locale.US, "%.1f", it.value), 0.8f, green)
        }
        return FaceSnapshot(now, true, true, 3, current, history, history, 3.9f, 10f, 64, 81)
    }

    private fun paint(snapshot: FaceSnapshot, ambient: Boolean, trial: Boolean = false, name: String? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val labels = labels()
        val model = FaceModel.build(snapshot, zone, labels)
        FacePainter().draw(Canvas(bitmap), size, size, model, true, ambient, showData = true, trial = trial,
            labels = labels, traceColor = { green })
        save(bitmap, name)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String?) {
        val dir = System.getenv("MOD_FACE_PREVIEW_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (name == null) return
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lit(bitmap: Bitmap, row: FaceLayout.Row? = null): Int {
        var n = 0
        val top = row?.top?.toInt()?.coerceAtLeast(0) ?: 0
        val bottom = row?.bottom?.toInt()?.coerceAtMost(size) ?: size
        for (y in top until bottom) for (x in 0 until size) {
            if (bitmap.getPixel(x, y) and 0xFFFFFF != 0) n++
        }
        return n
    }

    @Test
    fun blackAroundAndSomethingInEveryRow() {
        val bitmap = paint(snapshot(2), ambient = false, name = "mod_watch_face_preview")
        val layout = FaceLayout.compute(size, size)
        for ((x, y) in listOf(1 to 1, size - 2 to 1, 1 to size - 2, size - 2 to size - 2, size / 2 to 2)) {
            assertEquals("pixel $x,$y", FaceStyle.BLACK, bitmap.getPixel(x, y))
        }
        listOf(layout.time, layout.date, layout.value, layout.graph, layout.battery).forEach {
            assertTrue("row ${it.top}..${it.bottom} is empty", lit(bitmap, it) > 50)
        }
        assertEquals(0, lit(bitmap, layout.mark))
    }

    @Test
    fun theDimModeLightsFewerPixels() {
        val normal = paint(snapshot(2), ambient = false)
        val ambient = paint(snapshot(2), ambient = true, name = "ambient")
        assertTrue(lit(ambient) < lit(normal))
        assertEquals(FaceStyle.BLACK, ambient.getPixel(1, 1))
    }

    @Test
    fun theTrialMarkOnlyForATrial() {
        val layout = FaceLayout.compute(size, size)
        assertTrue(lit(paint(snapshot(2), ambient = false, trial = true, name = "trial"), layout.mark) > 20)
    }

    @Test
    fun oldGoneAndNoDataStillPaint() {
        val layout = FaceLayout.compute(size, size)
        paint(snapshot(15), ambient = false, name = "old")
        val gone = paint(snapshot(25), ambient = false, name = "gone")
        assertTrue(lit(gone, layout.value) > 50)
        val none = paint(snapshot(null, empty = true), ambient = true, name = "none")
        assertTrue(lit(none, layout.value) > 10)
    }
}
