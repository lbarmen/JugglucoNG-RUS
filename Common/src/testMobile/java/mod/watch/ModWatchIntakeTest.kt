package mod.watch

import android.net.Uri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemAsset
import com.google.android.gms.wearable.DataMap
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.health.ModHealthDataMap
import tk.glucodata.mod.watch.ModWatchIntake
import tk.glucodata.mod.watch.ModWatchIntake.Action
import tk.glucodata.mod.watch.ModWatchStore
import tk.glucodata.mod.watch.db.ModWatchSample

/**
 * JugglucoNGmod, watch block (part 5): the phone's rules of taking batches without a database - rows inserted,
 * repeats dropped (an outbox file sent again under new numbers), late heart rate merged by the counts once, items
 * kept or deleted, deleted events skipped, and the check screen's "today". Made-up values.
 */
class ModWatchIntakeTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(h: Int, m: Int, day: Int = 18) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private val received = at(12, 0)

    private fun hr(mean: Float, n: Int, min: Int, max: Int, time: Long = at(10, 0)) =
        Sample(ModHealthBatch.KIND_HR5, time, mean, ModHealthBatch.hr5Extra(n, min, max))

    /** Applies [samples] of batch [seq] to an in-memory table keyed by kind and time, as the DAO does. */
    private class Table {
        val rows = linkedMapOf<Pair<String, Long>, ModWatchSample>()
        fun apply(seq: Long, vararg samples: Sample) {
            for (s in samples) {
                when (val a = ModWatchIntake.decide(rows[s.kind to s.timeMs], s, seq, 0L)) {
                    is Action.Insert -> rows[s.kind to s.timeMs] = a.row
                    is Action.Replace -> rows[s.kind to s.timeMs] = a.row
                    Action.Drop -> Unit
                }
            }
        }
        fun hr(time: Long) = rows[ModHealthBatch.KIND_HR5 to time]!!
    }

    @Test
    fun aNewRowIsInsertedAsItCame() {
        val s = hr(72f, 50, 60, 85)
        val action = ModWatchIntake.decide(null, s, 7, received) as Action.Insert
        assertEquals(s, action.row.sample())
        assertEquals(7L, action.row.batchSeq)
        assertEquals(received, action.row.receivedAt)
    }

    @Test
    fun aRowEqualInEveryFieldIsARepeat() {
        val s = hr(72f, 50, 60, 85)
        val stored = (ModWatchIntake.decide(null, s, 7, received) as Action.Insert).row
        assertEquals(Action.Drop, ModWatchIntake.decide(stored, s, 9, received))
        val steps = Sample(ModHealthBatch.KIND_STEPS_DAY, at(10, 5), 4321f, at(0, 0).toString())
        val storedSteps = (ModWatchIntake.decide(null, steps, 7, received) as Action.Insert).row
        assertEquals(Action.Drop, ModWatchIntake.decide(storedSteps, steps, 9, received))
    }

    @Test
    fun lateHeartRateIsMergedByTheCounts() {
        val t = Table()
        t.apply(1, hr(70f, 3, 60, 80))
        t.apply(2, hr(90f, 1, 88, 92))
        assertEquals(75.0, t.hr(at(10, 0)).value, 0.0001)
        assertEquals("n=4;min=60;max=92", t.hr(at(10, 0)).extra)
        assertEquals(2L, t.hr(at(10, 0)).batchSeq)
    }

    @Test
    fun anOutboxSentAgainDoesNotDoubleTheCountEvenAfterAMerge() {
        val first = hr(70f, 3, 60, 80)
        val late = hr(90f, 1, 88, 92)
        val t = Table()
        t.apply(1, first)
        t.apply(1, first) // the same batch twice in a row (listener and drain): nothing new
        assertEquals("n=3;min=60;max=80", t.hr(at(10, 0)).extra)
        t.apply(2, late)
        // the watch sends the same file again under new numbers: both rows are known
        t.apply(3, first)
        t.apply(4, late)
        assertEquals("n=4;min=60;max=92", t.hr(at(10, 0)).extra)
        assertEquals(75.0, t.hr(at(10, 0)).value, 0.0001)
    }

    @Test
    fun otherKindsKeepTheFirstRowOfATime() {
        val t = Table()
        val b1 = Sample(ModHealthBatch.KIND_BATTERY, at(10, 0), 81f, "")
        t.apply(1, b1, b1.copy(value = 79f))
        assertEquals(81.0, t.rows[ModHealthBatch.KIND_BATTERY to at(10, 0)]!!.value, 0.0)
    }

    @Test
    fun deletedEventsAndOtherPathsAreSkipped() {
        assertTrue(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, "/mod/health/batch/12"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_DELETED, "/mod/health/batch/12"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, "/mod/health/batch/x"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, "/mod/tick"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, null))
    }

    @Test
    fun anItemIsDeletedOnlyWhenStoredOrJunk() {
        val batch = ModHealthBatch.forSending(5, received, listOf(hr(72f, 50, 60, 85)))
        val map = DataMap()
        ModHealthDataMap.write(batch, map)
        val decoded = ModHealthDataMap.read(map)
        var stored: ModHealthBatch? = null
        assertEquals(ModWatchIntake.Outcome.DELETE, ModWatchIntake.outcome(decoded) { stored = it; true })
        assertEquals(batch, stored)
        // the database did not open: the item stays for the next start
        assertEquals(ModWatchIntake.Outcome.KEEP, ModWatchIntake.outcome(decoded) { false })
        // a newer watch app: left for a newer phone app, never stored
        var called = false
        assertEquals(ModWatchIntake.Outcome.KEEP,
            ModWatchIntake.outcome(ModHealthBatch.Decoded.Newer(2)) { called = true; true })
        assertFalse(called)
        // broken (arrays of different lengths): never readable, deleted
        val broken = ModHealthBatch.decode(1, 5, received, arrayOf("hr5"), longArrayOf(1L, 2L), floatArrayOf(1f), arrayOf(""))
        assertEquals(ModWatchIntake.Outcome.DELETE, ModWatchIntake.outcome(broken) { true })
    }

    @Test
    fun theListenerHandsTheItemsOverAndReturnsWithoutWriting() {
        val queued = mutableListOf<Runnable>()
        val later = Executor { queued += it }
        val context = android.content.ContextWrapper(null)
        // no item, nothing queued
        ModWatchStore.receiveAsync(context, emptyList(), later)
        assertTrue(queued.isEmpty())
        // items go to our thread as one task; nothing of it ran on the caller's thread (the item is never even read)
        val item = object : DataItem {
            override fun getAssets(): Map<String, DataItemAsset> = emptyMap()
            override fun getData(): ByteArray? = throw AssertionError("read on the listener thread")
            override fun getUri(): Uri = throw AssertionError("read on the listener thread")
            override fun setData(data: ByteArray?): DataItem = this
            override fun freeze(): DataItem = this
            override fun isDataValid(): Boolean = true
        }
        ModWatchStore.receiveAsync(context, listOf(item, item), later)
        assertEquals(1, queued.size)
    }

    // ------------------------------------------------------------------ today

    private fun row(kind: String, time: Long, value: Double, extra: String = "") =
        ModWatchSample(kind = kind, at = time, value = value, extra = extra, batchSeq = 1, receivedAt = received)

    @Test
    fun todayIsTheLastTotalOfTodayTheLastHeartRateAndBattery() {
        val now = at(12, 0)
        val s = ModWatchIntake.summary(
            latestHr5 = row(ModHealthBatch.KIND_HR5, at(11, 50), 71.6, "n=40;min=60;max=80"),
            latestSteps = row(ModHealthBatch.KIND_STEPS_DAY, at(11, 55), 5432.0, at(0, 0).toString()),
            latestBattery = row(ModHealthBatch.KIND_BATTERY, at(11, 55), 81.0, ModHealthBatch.CHARGING),
            lastBatchAt = at(11, 56), nowMs = now, zone = zone,
        )
        assertEquals(72, s.heartRate)
        assertEquals(at(11, 50), s.heartRateAt)
        assertEquals(5432, s.stepsToday)
        assertEquals(81, s.watchBattery)
        assertTrue(s.watchCharging)
        assertEquals(at(11, 56), s.lastBatchAt)
        assertFalse(s.empty)
    }

    @Test
    fun theDayEndsAtMidnightOfTheZoneGiven() {
        val now = at(0, 30, day = 19)
        val yesterdays = row(ModHealthBatch.KIND_STEPS_DAY, at(23, 50), 9000.0, at(0, 0).toString())
        assertNull(ModWatchIntake.summary(null, yesterdays, null, null, now, zone).stepsToday)
        // the same moment is still the 18th an hour west
        val west = ZoneId.of("Europe/Kaliningrad")
        assertEquals(9000, ModWatchIntake.summary(null, yesterdays, null, null, now, west).stepsToday)
        val (from, to) = ModWatchIntake.dayBounds(now, zone)
        assertEquals(at(0, 0, day = 19), from)
        assertEquals(at(0, 0, day = 20), to)
    }

    @Test
    fun aTotalOfAWatchDayStartedYesterdayIsNotToday() {
        val now = at(0, 10, day = 19)
        // sent at 00:05, but the watch has not passed its midnight: its day started on the 18th
        val late = row(ModHealthBatch.KIND_STEPS_DAY, at(0, 5, day = 19), 9100.0, at(0, 0, day = 18).toString())
        assertNull(ModWatchIntake.summary(null, late, null, null, now, zone).stepsToday)
        val fresh = row(ModHealthBatch.KIND_STEPS_DAY, at(0, 5, day = 19), 40.0, at(0, 0, day = 19).toString())
        assertEquals(40, ModWatchIntake.summary(null, fresh, null, null, now, zone).stepsToday)
        // without a day start the total counts
        assertEquals(40, ModWatchIntake.summary(null, fresh.copy(extra = ""), null, null, now, zone).stepsToday)
    }

    @Test
    fun nothingStoredIsEmpty() {
        val s = ModWatchIntake.summary(null, null, null, null, at(12, 0), zone)
        assertTrue(s.empty)
        assertNull(s.heartRate)
        assertFalse(s.watchCharging)
    }
}
