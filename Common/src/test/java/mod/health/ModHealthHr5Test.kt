package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample

/**
 * JugglucoNGmod, watch block (part 5, joints critic C1): the extra of a 5-minute heart rate row, one format for both
 * sides - built on the watch, read and merged on the phone. Made-up values.
 */
class ModHealthHr5Test {

    private val start = 1_800_000_000_000L

    private fun hr(mean: Float, n: Int, min: Int, max: Int) =
        Sample(ModHealthBatch.KIND_HR5, start, mean, ModHealthBatch.hr5Extra(n, min, max))

    @Test
    fun theExtraIsBuiltAndReadBack() {
        assertEquals("n=12;min=58;max=91", ModHealthBatch.hr5Extra(12, 58, 91))
        assertEquals(ModHealthBatch.Hr5(12, 58, 91), ModHealthBatch.parseHr5Extra("n=12;min=58;max=91"))
        // order and unknown fields of a newer watch do not matter
        assertEquals(ModHealthBatch.Hr5(3, 60, 70), ModHealthBatch.parseHr5Extra("max=70;x=1;n=3;min=60"))
    }

    @Test
    fun aBrokenExtraIsNotRead() {
        for (bad in listOf(null, "", "n=0;min=60;max=70", "n=-1;min=60;max=70", "n=x;min=60;max=70", "min=60;max=70",
            "n=3;max=70", "n=3;min=60", "garbage")) {
            assertNull(bad, ModHealthBatch.parseHr5Extra(bad))
        }
    }

    @Test
    fun lateReadingsMergeByTheirCounts() {
        val merged = ModHealthBatch.mergeHr5(hr(70f, 3, 60, 80), hr(90f, 1, 88, 92))!!
        assertEquals(75f, merged.value, 0.0001f) // (70*3 + 90*1) / 4
        assertEquals("n=4;min=60;max=92", merged.extra)
        assertEquals(start, merged.timeMs)
        assertEquals(ModHealthBatch.KIND_HR5, merged.kind)
    }

    @Test
    fun onlyTwoHeartRateRowsOfOneStartMerge() {
        assertNull(ModHealthBatch.mergeHr5(hr(70f, 3, 60, 80), hr(90f, 1, 88, 92).copy(timeMs = start + 300_000L)))
        assertNull(ModHealthBatch.mergeHr5(hr(70f, 3, 60, 80), Sample(ModHealthBatch.KIND_BATTERY, start, 80f, "")))
        assertNull(ModHealthBatch.mergeHr5(hr(70f, 3, 60, 80), hr(90f, 1, 88, 92).copy(extra = "broken")))
        assertNull(ModHealthBatch.mergeHr5(hr(Float.NaN, 3, 60, 80), hr(90f, 1, 88, 92)))
    }
}
