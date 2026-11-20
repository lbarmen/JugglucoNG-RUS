package mod.watch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.WearMessagePath
import tk.glucodata.mod.watch.HistoryGapFill
import tk.glucodata.mod.watch.HistoryGapFill.Gap
import tk.glucodata.mod.watch.HistoryGapFill.State
import tk.glucodata.mod.watch.PhoneBatteryStore

/** JugglucoNGmod, watch block: the history hole check of the watch - holes, the request bytes, repeats. Made-up times. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HistoryGapFillTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private lateinit var context: Context
    private val sent = ArrayList<ByteArray>()
    private var stamps: List<Long> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        HistoryGapFill.resetForTest()
        HistoryGapFill.history = { start -> stamps.filter { it >= start } }
        HistoryGapFill.sender = { _, data -> sent.add(data); true }
        HistoryGapFill.runner = { it.run() }
    }

    @After
    fun tearDown() = HistoryGapFill.resetForTest()

    /** One reading a minute from [fromMs] to [toMs], both included. */
    private fun everyMinute(fromMs: Long, toMs: Long) = (fromMs..toMs step minute).toList()

    private fun fromSecOf(data: ByteArray) = ByteBuffer.wrap(data).apply { get() }.long

    @Test
    fun noHoleAndEmptyHistoryFindNothing() {
        val window = now - HistoryGapFill.WINDOW_MS
        assertTrue(HistoryGapFill.findGaps(emptyList(), window, now).isEmpty())
        assertTrue(HistoryGapFill.findGaps(listOf(now - minute), window, now).isEmpty())
        assertTrue(HistoryGapFill.findGaps(everyMinute(now - 10 * hour, now), window, now).isEmpty())
        // 12 minutes exactly is not a hole (a Dexcom skipping two readings), 13 is
        assertTrue(HistoryGapFill.findGaps(listOf(now - 12 * minute, now), window, now).isEmpty())
        assertEquals(1, HistoryGapFill.findGaps(listOf(now - 13 * minute, now), window, now).size)
    }

    @Test
    fun severalHolesComeOldestFirstUnsortedInputIsFine() {
        val readings = everyMinute(now - 10 * hour, now - 8 * hour) +
            everyMinute(now - 6 * hour, now - 3 * hour) +
            everyMinute(now - 2 * hour, now)
        val gaps = HistoryGapFill.findGaps(readings.shuffled() + readings.take(5), now - HistoryGapFill.WINDOW_MS, now)
        assertEquals(listOf(Gap(now - 8 * hour, now - 6 * hour), Gap(now - 3 * hour, now - 2 * hour)), gaps)
    }

    @Test
    fun edgesOfTheWindowAreNotHoles() {
        // readings only in the last hour of a 48-hour window, and none in the last 30 minutes: no hole either side
        val gaps = HistoryGapFill.findGaps(everyMinute(now - hour, now - 30 * minute), now - HistoryGapFill.WINDOW_MS, now)
        assertTrue(gaps.isEmpty())
        // a reading older than the window does not open a hole into it
        val old = listOf(now - HistoryGapFill.WINDOW_MS - hour) + everyMinute(now - hour, now)
        assertTrue(HistoryGapFill.findGaps(old, now - HistoryGapFill.WINDOW_MS, now).isEmpty())
    }

    @Test
    fun theRequestStartsFiveMinutesBeforeTheHoleAndNeverDeeperThan72Hours() {
        val gap = Gap(now - 5 * hour, now - 4 * hour)
        assertEquals((now - 5 * hour - 5 * minute) / 1000L, HistoryGapFill.requestFromSec(gap, now))
        val deep = Gap(now - 100 * hour, now - 99 * hour)
        assertEquals((now - HistoryGapFill.MAX_DEPTH_MS) / 1000L, HistoryGapFill.requestFromSec(deep, now))
    }

    @Test
    fun theRequestHasUpstreamsBytes() {
        val fromSec = 1_799_990_000L
        val expected = ByteArray(10)
        expected[0] = 1
        for (i in 0 until 8) expected[1 + i] = (fromSec shr (56 - 8 * i)).toByte()
        expected[9] = 0
        assertArrayEquals(expected, HistoryGapFill.encodeRequest(fromSec))
        assertEquals(1.toByte(), HistoryGapFill.encodeRequest(fromSec, force = true)[9])
    }

    /**
     * Breaks when upstream changes what this class copies from WearSync2 (mod/SYNC.md): the request format and
     * version, the path, the 14-day line between a plain and a deep request (ours stay above it).
     */
    @Test
    fun upstreamsRequestFormatIsStillTheOneWeSend() {
        val source = listOf(File("src/main/java/tk/glucodata/WearSync2.kt"), File("Common/src/main/java/tk/glucodata/WearSync2.kt"))
            .first { it.isFile }.readText()
        val version = Regex("private const val VERSION = (\\d+)").find(source)?.groupValues?.get(1)
        assertEquals(HistoryGapFill.WIRE_VERSION.toString(), version)
        assertTrue(source.contains("request  (receiver→owner, SYNC2_REQ_PATH):  [u8 ver][i64 fromSec]"))
        assertTrue(source.contains("val buf = ByteBuffer.allocate(10)"))
        assertTrue(source.contains("buf.put((if (force) 1 else 0).toByte())"))
        assertTrue(source.contains("private const val BACKFILL_HORIZON_SEC = 14L * 24L * 3600L"))
        assertTrue(source.contains("fromSec <= System.currentTimeMillis() / 1000L - BACKFILL_HORIZON_SEC + 60L"))
        assertTrue(HistoryGapFill.MAX_DEPTH_MS / 1000L < 14L * 24L * 3600L - 60L)
        assertEquals("/sync2/req", WearMessagePath.SYNC2_REQ.wire)
    }

    @Test
    fun theSameHoleIsAskedForThreeTimesTenMinutesApartThenDropped() {
        val gaps = listOf(Gap(now - 5 * hour, now - 4 * hour), Gap(now - 2 * hour, now - hour))
        var state = State()
        val sentAt = ArrayList<Long>()
        for (tick in 0..12) {
            val at = now + tick * 5 * minute
            val decision = HistoryGapFill.decide(gaps, state, at)
            state = decision.state
            if (decision.fromSec > 0) sentAt.add(at)
            if (tick == 6) {
                // the first hole is dropped, the second one is asked for at once
                assertEquals(gaps[1], decision.gap)
                assertEquals(setOf(gaps[0].startMs), state.dead)
            }
        }
        assertEquals(listOf(0, 10, 20, 30, 40, 50).map { now + it * minute }, sentAt)
        // both dropped: nothing more, until a new hole appears
        val after = HistoryGapFill.decide(gaps, state, now + 70 * minute)
        assertNull(after.gap)
        assertEquals(0L, after.fromSec)
    }

    @Test
    fun aClosedHoleOrANewOneStartsTheCountAgainAndOldDroppedHolesAreForgotten() {
        val first = Gap(now - 5 * hour, now - 4 * hour)
        val one = HistoryGapFill.decide(listOf(first), State(), now)
        assertEquals(1, one.tries)
        val other = Gap(now - 3 * hour, now - 2 * hour)
        val two = HistoryGapFill.decide(listOf(other), one.state, now + minute)
        assertEquals(1, two.tries)
        assertFalse(two.wait)
        val old = State(dead = setOf(now - HistoryGapFill.WINDOW_MS - hour, first.startMs))
        assertEquals(setOf(first.startMs), HistoryGapFill.decide(emptyList(), old, now).state.dead)
        // a clock set back: the last request "in the future" does not block the next one
        val back = HistoryGapFill.decide(listOf(first), State(first.startMs, 1, now + hour), now)
        assertFalse(back.wait)
        assertEquals(2, back.tries)
    }

    @Test
    fun aTickScansSendsAndKeepsTheCountAcrossARestart() {
        stamps = everyMinute(now - 20 * hour, now - 3 * hour) + everyMinute(now - 10 * minute, now)
        HistoryGapFill.onTick(context, now)
        assertEquals(1, sent.size)
        assertEquals((now - 3 * hour - 5 * minute) / 1000L, fromSecOf(sent[0]))
        // a second tick within a minute is ignored
        HistoryGapFill.onTick(context, now + 30_000L)
        assertEquals(1, sent.size)
        // the process restarts: the count is in the preferences, 5 minutes later is too soon to ask again
        HistoryGapFill.resetForTest()
        HistoryGapFill.onTick(context, now + 5 * minute)
        assertEquals(1, sent.size)
        assertEquals(1, HistoryGapFill.load(context).tries)
        HistoryGapFill.onTick(context, now + 10 * minute)
        assertEquals(2, sent.size)
        // the phone filled the hole: nothing more is sent, the state is cleared
        stamps = everyMinute(now - 20 * hour, now + 15 * minute)
        HistoryGapFill.onTick(context, now + 15 * minute)
        assertEquals(2, sent.size)
        assertEquals(State(), HistoryGapFill.load(context))
    }
}
