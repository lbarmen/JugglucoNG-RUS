package mod.health

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.health.HealthConfig
import tk.glucodata.mod.health.HealthCore
import tk.glucodata.mod.health.HealthHandOver
import tk.glucodata.mod.health.HealthIntake
import tk.glucodata.mod.health.HealthIntake.HeartRate
import tk.glucodata.mod.health.HealthIntake.StepsDay
import tk.glucodata.mod.health.HealthSender
import tk.glucodata.mod.health.HealthSource
import tk.glucodata.mod.health.HealthSources
import tk.glucodata.mod.health.HealthState
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample

/**
 * JugglucoNGmod, watch block (part 4): deliveries into the queue, batches out of it (numbers, paths, failures) and
 * the hand-over on a tick, with a fake source and a fake Data Layer. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_800_000_000_000L // on a 5-minute boundary
    private val minute = 60_000L
    private var now = t0
    private lateinit var context: Context
    private lateinit var dir: File

    /** Keeps what was put; fails the puts whose number is in [failOn]. */
    private class FakeSink(val failOn: Set<Int> = emptySet()) : HealthSender.Sink {
        val batches = mutableListOf<ModHealthBatch>()
        var calls = 0
        override fun put(batch: ModHealthBatch) {
            calls++
            if (calls in failOn) throw IOException("not accepted")
            batches += batch
        }
        fun rows(kind: String) = batches.flatMap { it.samples }.filter { it.kind == kind }
    }

    private open inner class FakeSource : HealthSource {
        var flushes = 0
        var maintainedAt = 0L
        override fun flush(context: Context): Boolean {
            flushes++
            return true
        }
        override fun maintain(context: Context, nowMs: Long) {
            maintainedAt = nowMs
        }
        override fun statusFields(context: Context) = "perm=hr,act;reg=1"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(HealthState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        dir = tmp.newFolder("q")
        HealthSources.current = null
    }

    private fun core() = HealthCore(context, dir) { now }

    @Test
    fun deliveryQueuesStepsAsTheyAreAndKeepsTheOpenWindow() {
        val core = core()
        now = t0 + 3 * minute
        val dayStart = t0 - 9 * 60 * minute
        val queued = HealthIntake.deliverNow(
            core,
            listOf(HeartRate(t0 + minute, 64f), HeartRate(t0 + 2 * minute, 68f)),
            listOf(StepsDay(t0 + 3 * minute, 4321, dayStart), StepsDay(t0, -1, dayStart), StepsDay(dayStart - 1, 10, dayStart)),
            now,
        )
        assertEquals(1, queued) // the steps row; the window is still open, the broken counts skipped
        assertEquals(1, core.openHeartRateWindows)
        assertEquals(now, core.state.lastDataAt)
        assertEquals(1L, core.dataEvents())
        val rows = core.buffer.takeOutbox(now).flatMap { core.buffer.read(it) }
        assertEquals(listOf(Sample(ModHealthBatch.KIND_STEPS_DAY, t0 + 3 * minute, 4321f, dayStart.toString())), rows)
    }

    @Test
    fun windowClosesTwoMinutesAfterItsEndOnANormalDelivery() {
        val core = core()
        HealthIntake.deliverNow(core, listOf(HeartRate(t0 + minute, 70f)), emptyList(), t0 + 6 * minute)
        assertEquals(1, core.openHeartRateWindows)
        HealthIntake.deliverNow(core, listOf(HeartRate(t0 + 7 * minute, 72f)), emptyList(), t0 + 7 * minute)
        val rows = core.buffer.takeOutbox(now).flatMap { core.buffer.read(it) }
        assertEquals(listOf(Sample(ModHealthBatch.KIND_HR5, t0, 70f, "n=1;min=70;max=70")), rows)
        assertEquals(1, core.openHeartRateWindows)
    }

    @Test
    fun openWindowsSurviveAProcessRestart() {
        HealthIntake.deliverNow(core(), listOf(HeartRate(t0 + minute, 60f)), emptyList(), t0 + minute)
        val restarted = core()
        HealthIntake.deliverNow(restarted, listOf(HeartRate(t0 + 2 * minute, 80f)), emptyList(), t0 + 2 * minute)
        restarted.closeHeartRate(t0 + 6 * minute, HealthConfig.HR_GRACE_REFRESH_MS)
        val rows = restarted.buffer.takeOutbox(now).flatMap { restarted.buffer.read(it) }
        assertEquals(listOf(Sample(ModHealthBatch.KIND_HR5, t0, 70f, "n=2;min=60;max=80")), rows)
    }

    @Test
    fun batchNumbersGrowAndSurviveAProcessRestart() {
        val sink = FakeSink()
        val first = core()
        first.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR5, t0, 66f, "n=4;min=64;max=68")))
        HealthSender.send(first, null, sink, t0)
        assertEquals(1, sink.batches.size)
        assertEquals(1L, sink.batches[0].seq)
        assertEquals("/mod/health/batch/1", sink.batches[0].path)
        assertEquals(1L, HealthState(context).seq)

        val restarted = core()
        restarted.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR5, t0 + 5 * minute, 67f, "n=4;min=65;max=69")))
        HealthSender.send(restarted, null, sink, t0 + 10 * minute)
        assertEquals(2L, sink.batches[1].seq)
        assertEquals("/mod/health/batch/2", sink.batches[1].path)
    }

    @Test
    fun failedPutKeepsTheFileAndTheNextSendDeliversIt() {
        val core = core()
        val hr = Sample(ModHealthBatch.KIND_HR5, t0, 66f, "n=4;min=64;max=68")
        core.buffer.append(listOf(hr))
        val failing = FakeSink(failOn = setOf(1))
        val r1 = HealthSender.send(core, null, failing, t0)
        assertTrue(r1.failed)
        assertTrue(core.buffer.hasOutbox())
        assertEquals("IOException", core.state.lastError)
        assertEquals(0L, core.state.lastSentAt)

        val good = FakeSink()
        val r2 = HealthSender.send(core, null, good, t0 + minute)
        assertFalse(r2.failed)
        assertEquals(listOf(hr), good.rows(ModHealthBatch.KIND_HR5))
        assertFalse(core.buffer.hasOutbox())
        assertEquals(0, core.buffer.count())
        assertEquals("", core.state.lastError)
        assertEquals(t0 + minute, core.state.lastSentAt)
    }

    @Test
    fun bigQueueIsCutIntoBatchesInOrderAndForeignKindsNeverGo() {
        val core = core()
        val means = (0 until 200).map { Sample(ModHealthBatch.KIND_HR5, t0 + it * 5 * minute, 60f + it % 30, "n=30;min=55;max=99") }
        core.buffer.append(means + Sample("floors_day", t0, 3f, ""))
        val sink = FakeSink()
        val r = HealthSender.send(core, null, sink, t0, maxBytes = 2_000)
        assertTrue(r.items > 1)
        assertEquals(means, sink.rows(ModHealthBatch.KIND_HR5))
        assertTrue(sink.batches.flatMap { it.samples }.all { it.kind in ModHealthBatch.KINDS })
        assertEquals(sink.batches.indices.map { it + 1L }, sink.batches.map { it.seq })
    }

    @Test
    fun statusRowFirstTimeThenEveryThirtyMinutes() {
        val core = core()
        val source = FakeSource()
        val sink = FakeSink()
        HealthSender.send(core, source, sink, t0)
        val status = sink.rows(ModHealthBatch.KIND_STATUS)
        assertEquals(1, status.size)
        assertTrue(status[0].extra.contains("sdk=34;perm=hr,act;reg=1;data=-"))
        HealthSender.send(core, source, sink, t0 + 10 * minute)
        assertEquals(1, sink.rows(ModHealthBatch.KIND_STATUS).size)
        HealthSender.send(core, source, sink, t0 + 30 * minute)
        assertEquals(2, sink.rows(ModHealthBatch.KIND_STATUS).size)
        HealthSender.send(core, source, sink, t0 + 31 * minute, forceStatus = true)
        assertEquals(3, sink.rows(ModHealthBatch.KIND_STATUS).size)
    }

    @Test
    fun deliveryDoesNotSendWithinFiveMinutesOfTheLastAttempt() {
        val core = core()
        val sink = FakeSink()
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR5, t0, 66f, "n=1;min=66;max=66")))
        HealthSender.send(core, null, sink, t0)
        val items = sink.batches.size
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR5, t0 + 5 * minute, 67f, "n=1;min=67;max=67")))
        assertNull(HealthSender.sendIfDue(core, null, sink, t0 + 5 * minute - 1))
        assertEquals(items, sink.batches.size)
        assertTrue(HealthSender.sendIfDue(core, null, sink, t0 + 5 * minute)!!.items > 0)
    }

    @Test
    fun tickFlushesWaitsForTheDataClosesTheWindowWithFiveSecondsGraceAndSends() {
        val core = core()
        HealthIntake.deliverNow(core, listOf(HeartRate(t0 + minute, 60f)), emptyList(), t0 + minute)
        now = t0 + 5 * minute + HealthConfig.HR_GRACE_REFRESH_MS
        val source = object : FakeSource() {
            override fun flush(context: Context): Boolean {
                super.flush(context)
                // Health Services answers the flush a little later, from its own thread.
                Thread {
                    Thread.sleep(100)
                    HealthIntake.deliverNow(core, listOf(HeartRate(t0 + 4 * minute, 70f)), emptyList(), now)
                }.start()
                return true
            }
        }
        val sink = FakeSink()
        val r = HealthHandOver.run(core, source, sink, flushWaitMs = 5_000)
        assertFalse(r.failed)
        assertEquals(1, source.flushes)
        assertEquals(now, source.maintainedAt)
        assertEquals(listOf(Sample(ModHealthBatch.KIND_HR5, t0, 65f, "n=2;min=60;max=70")), sink.rows(ModHealthBatch.KIND_HR5))
        assertEquals(0, core.openHeartRateWindows)
    }

    @Test
    fun tickWithoutDataWaitsAtMostTheFlushWaitAndStillSends() {
        val core = core()
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_STEPS_DAY, t0, 1234f, (t0 - 60 * minute).toString())))
        val sink = FakeSink()
        val started = System.nanoTime()
        HealthHandOver.run(core, FakeSource(), sink, flushWaitMs = 200)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs in 150..3_000)
        assertEquals(1, sink.rows(ModHealthBatch.KIND_STEPS_DAY).size)
    }

    @Test
    fun failedFlushDoesNotWait() {
        val core = core()
        val source = object : FakeSource() {
            override fun flush(context: Context): Boolean = throw IllegalStateException("no service")
        }
        val started = System.nanoTime()
        HealthHandOver.run(core, source, FakeSink(), flushWaitMs = 5_000)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun noSourceNoHandOver() {
        HealthSources.current = null
        HealthHandOver.onTick(context) // nothing registered: returns at once, no Data Layer
        HealthHandOver.sendNow(context)
        assertEquals(0L, HealthState(context).seq)
    }
}
