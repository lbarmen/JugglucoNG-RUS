package mod.health

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Decoded
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.health.ModHealthDataMap

/** JugglucoNGmod, watch block (part 4): the heart rate and steps batch format (runs in both test tasks). Made-up values. */
class ModHealthBatchTest {

    private val at = 1_800_000_000_000L
    private val minute = 60_000L

    private val rows = listOf(
        Sample(ModHealthBatch.KIND_HR5, at, 71.5f, "n=12;min=64;max=80"),
        Sample(ModHealthBatch.KIND_STEPS_DAY, at + 2 * minute, 4321f, (at - 10 * 60 * minute).toString()),
        Sample(ModHealthBatch.KIND_BATTERY, at + 3 * minute, 55f, ModHealthBatch.CHARGING),
        Sample(ModHealthBatch.KIND_STATUS, at + 3 * minute, 0f, "app=x;sdk=34;perm=1"),
    )

    private fun decode(b: ModHealthBatch) =
        ModHealthBatch.decode(b.version, b.seq, b.sentAtMs, b.kinds(), b.times(), b.values(), b.extras())

    @Test
    fun arraysRoundTrip() {
        val batch = ModHealthBatch(7, at + 4 * minute, rows)
        assertEquals(Decoded.Ok(batch), decode(batch))
        assertEquals("/mod/health/batch/7", batch.path)
    }

    @Test
    fun dataMapRoundTrip() {
        val batch = ModHealthBatch(42, at, rows)
        val map = DataMap()
        ModHealthDataMap.write(batch, map)
        assertEquals(Decoded.Ok(batch), ModHealthDataMap.read(map))
    }

    @Test
    fun emptyDataMapIsBrokenNotAnException() {
        assertEquals(Decoded.Broken("version"), ModHealthDataMap.read(DataMap()))
    }

    @Test
    fun arraysOfDifferentLengthsAreRefused() {
        val b = ModHealthBatch(1, at, rows)
        val shortTimes = b.times().copyOf(rows.size - 1)
        assertEquals(Decoded.Broken("lengths"), ModHealthBatch.decode(1, 1, at, b.kinds(), shortTimes, b.values(), b.extras()))
        val shortExtras = b.extras().copyOf(rows.size - 1)
        assertEquals(Decoded.Broken("lengths"), ModHealthBatch.decode(1, 1, at, b.kinds(), b.times(), b.values(), shortExtras))
        assertEquals(Decoded.Broken("missing"), ModHealthBatch.decode(1, 1, at, null, b.times(), b.values(), b.extras()))
    }

    @Test
    fun newerVersionIsLeftAloneVersionZeroIsBroken() {
        val b = ModHealthBatch(1, at, rows)
        assertEquals(Decoded.Newer(2), ModHealthBatch.decode(2, 1, at, b.kinds(), b.times(), b.values(), b.extras()))
        assertEquals(Decoded.Broken("version"), ModHealthBatch.decode(0, 1, at, b.kinds(), b.times(), b.values(), b.extras()))
    }

    @Test
    fun brokenHeaderAndNullKindAreRefused() {
        val b = ModHealthBatch(1, at, rows)
        assertEquals(Decoded.Broken("header"), ModHealthBatch.decode(1, 0, at, b.kinds(), b.times(), b.values(), b.extras()))
        assertEquals(Decoded.Broken("header"), ModHealthBatch.decode(1, 1, -1, b.kinds(), b.times(), b.values(), b.extras()))
        val kinds: Array<String?> = arrayOf(null, "hr5", "hr5", "hr5")
        assertEquals(Decoded.Broken("kind"), ModHealthBatch.decode(1, 1, at, kinds, b.times(), b.values(), b.extras()))
    }

    @Test
    fun nullExtraBecomesEmpty() {
        val extras: Array<String?> = arrayOf(null)
        val decoded = ModHealthBatch.decode(1, 3, at, arrayOf("hr5"), longArrayOf(at), floatArrayOf(66f), extras)
        assertEquals(Decoded.Ok(ModHealthBatch(3, at, listOf(Sample("hr5", at, 66f, "")))), decoded)
    }

    @Test
    fun onlyKnownKindsAreSentAndReadAsKnown() {
        val withForeign = rows + Sample("floors_day", at, 3f, "")
        val batch = ModHealthBatch.forSending(5, at, withForeign)
        assertEquals(rows, batch.samples)
        val read = (ModHealthBatch.decode(1, 5, at, arrayOf("hr5", "sleep"), longArrayOf(at, at), floatArrayOf(66f, 1f), arrayOf("", "")) as Decoded.Ok).batch
        assertEquals(2, read.samples.size)
        assertEquals(listOf(Sample("hr5", at, 66f, "")), read.knownSamples())
    }

    @Test
    fun seqFromPath() {
        assertEquals(12L, ModHealthBatch.seqFromPath("/mod/health/batch/12"))
        assertEquals("/mod/health/batch/12", ModHealthBatch.path(12))
        assertNull(ModHealthBatch.seqFromPath("/mod/health/batch/"))
        assertNull(ModHealthBatch.seqFromPath("/mod/health/batch/0"))
        assertNull(ModHealthBatch.seqFromPath("/mod/health/batch/x"))
        assertNull(ModHealthBatch.seqFromPath("/mod/tick"))
        assertNull(ModHealthBatch.seqFromPath(null))
    }

    @Test
    fun chunksStayUnderLimitAndKeepOrder() {
        val samples = (1..1000).map { Sample(ModHealthBatch.KIND_HR5, at + it * 5 * minute, 60f + it % 40, "n=30;min=60;max=99") }
        val chunks = ModHealthBatch.chunks(samples, maxBytes = 5_000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { c -> c.sumOf { it.approxBytes() } <= 5_000 })
        assertEquals(samples, chunks.flatten())
        assertEquals(2, ModHealthBatch.chunks(samples).size) // 1000 means of 55 bytes: over one 50 KB batch
        assertEquals(1, ModHealthBatch.chunks(samples.take(800)).size) // ~2.8 days of means fit one batch
    }

    @Test
    fun oversizedRowGoesAloneInsteadOfLooping() {
        val big = Sample(ModHealthBatch.KIND_STATUS, at, 0f, "x".repeat(10_000))
        val small = Sample(ModHealthBatch.KIND_HR5, at, 70f)
        assertEquals(listOf(listOf(big), listOf(small)), ModHealthBatch.chunks(listOf(big, small), maxBytes = 1_000))
        assertEquals(emptyList<List<Sample>>(), ModHealthBatch.chunks(emptyList()))
    }

    @Test
    fun newDayIsADayStartShiftOfMoreThanAnHour() {
        val dayStart = at - 10 * 60 * minute
        assertFalse(ModHealthBatch.isNewDay(dayStart, dayStart + 7))
        assertFalse(ModHealthBatch.isNewDay(dayStart, dayStart + 60 * minute))
        assertTrue(ModHealthBatch.isNewDay(dayStart, dayStart + 60 * minute + 1))
        assertTrue(ModHealthBatch.isNewDay(dayStart, dayStart + 24 * 60 * minute))
        assertTrue(ModHealthBatch.isNewDay(dayStart, dayStart - 24 * 60 * minute))
    }
}
