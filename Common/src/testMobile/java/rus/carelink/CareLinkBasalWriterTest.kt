package rus.carelink

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import rus.carelink.CareLinkJournalTestEnv.app
import rus.carelink.CareLinkJournalTestEnv.rows
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.rus.carelink.CareLinkBasalWriter
import tk.glucodata.rus.carelink.CareLinkIob
import tk.glucodata.rus.carelink.CareLinkSettings
import tk.glucodata.rus.carelink.PumpNow
import tk.glucodata.rus.carelink.TempBasal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * JugglucoNG-RUS, CareLink 4d: the pump's basal as TEMP_BASAL segments in upstream's real journal - a rate change
 * closes and opens, the running segment is written ahead and moved on, a gap closes at the last data, a stop writes 0
 * and a note, a percentage temporary basal by the last normal rate (none - nothing), an absolute one without
 * `basalRate`, source by the switch; the pump's active insulin into upstream's IoB snapshot (a time from the future
 * dropped). Made-up rates and times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkBasalWriterTest {

    private val now = 1_800_000_000_000L
    private val t0 = now - 3 * 3_600_000L
    private val min = 60_000L

    @Before
    fun setUp() {
        CareLinkJournalTestEnv.setUp { now }
        ApiIobSnapshot.clear()
    }

    @After
    fun tearDown() {
        CareLinkJournalTestEnv.tearDown()
        ApiIobSnapshot.clear()
    }

    private fun write(p: PumpNow?) = runBlocking { CareLinkBasalWriter.apply(app, p, now) }

    private fun at(m: Long, rate: Double? = 0.8, temp: TempBasal? = null, suspended: Boolean? = false) =
        PumpNow(t0 + m * min, basalRate = rate, tempBasal = temp, suspended = suspended, basalKnown = true)

    private fun basal(): List<JournalEntryEntity> =
        rows(JournalEntryType.TEMP_BASAL.storageValue).sortedBy { it.timestamp }

    private fun notes() = rows(JournalEntryType.NOTE.storageValue)

    private fun JournalEntryEntity.startMin() = (timestamp - t0) / min

    private fun hhmm(m: Long) =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(t0 + m * min))

    @Test
    fun rateChangeClosesTheSegmentAndTheRunningOneIsWrittenAhead() {
        write(at(0))
        assertEquals(30, basal().single().durationMinutes)
        assertEquals(0.8f, basal().single().amount!!, 1e-6f)
        assertEquals("carelink:basal:$t0:0:basal", basal().single().sourceRecordId)
        write(at(5))
        assertEquals("25 min left - not moved", 30, basal().single().durationMinutes)
        write(at(25))
        assertEquals("5 min left - moved 30 min past the data", 55, basal().single().durationMinutes)
        write(at(30, rate = 1.05))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals("closed at the change", 30, b[0].durationMinutes)
        assertEquals(30L, b[1].startMin())
        assertEquals(1.05f, b[1].amount!!, 1e-6f)
        assertEquals(30, b[1].durationMinutes)
        assertEquals("switch off - API", JournalEntrySource.API.storageValue, b[1].source)
    }

    @Test
    fun emptyAnswerIsAGapNotAContinuation() {
        write(at(0))
        write(at(5))
        write(null)
        assertEquals("closed at the last data", 5, basal().single().durationMinutes)
        write(at(40))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals(40L, b[1].startMin())
    }

    @Test
    fun longPauseBetweenSnapshotsIsAGap() {
        write(at(0))
        write(at(10))
        write(at(45))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals("closed at its last data", 10, b[0].durationMinutes)
        assertEquals(45L, b[1].startMin())
    }

    @Test
    fun noBasalObjectIsAGap() {
        write(at(0))
        write(at(5))
        write(PumpNow(t0 + 10 * min, reservoirUnits = 100.0, basalKnown = false))
        assertEquals(5, basal().single().durationMinutes)
    }

    @Test
    fun stopWritesZeroAndANoteWhoseEndComesWithTheRestart() {
        write(at(0))
        write(at(10, rate = null, suspended = true))
        val note = notes().single()
        assertEquals("Pump stopped", note.title)
        assertEquals("${hhmm(10)}–…", note.note)
        assertEquals(t0 + 10 * min, note.timestamp)
        write(at(25, rate = null, suspended = true))
        write(at(40, rate = 0.8, suspended = false))
        val b = basal()
        assertEquals(3, b.size)
        assertEquals(10, b[0].durationMinutes)
        assertEquals(0.0f, b[1].amount!!, 1e-6f)
        assertEquals(30, b[1].durationMinutes)
        assertEquals(40L, b[2].startMin())
        assertEquals("${hhmm(10)}–${hhmm(40)}", notes().single().note)
    }

    @Test
    fun percentageTemporaryBasalUsesTheLastNormalRate() {
        write(at(0, rate = 1.0))
        write(at(5, rate = 1.0, temp = TempBasal(0.0, 80, 60)))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals(0.8f, b[1].amount!!, 1e-6f)
    }

    @Test
    fun percentageTemporaryBasalWithoutANormalRateIsAGap() {
        write(at(0, rate = 0.7, temp = TempBasal(0.0, 80, 60)))
        assertTrue(basal().isEmpty())
    }

    @Test
    fun absoluteTemporaryBasalWithoutBasalRate() {
        // critic 4c, C4: the basal object came without basalRate - the temporary basal is not lost
        write(at(0, rate = null, temp = TempBasal(0.4, null, 30)))
        assertEquals(0.4f, basal().single().amount!!, 1e-6f)
    }

    @Test
    fun sourceFollowsTheSwitch() {
        CareLinkSettings(app).sendToNightscout = true
        write(at(0))
        write(at(10, rate = null, suspended = true))
        assertTrue(basal().all { it.source == JournalEntrySource.HEALTH_CONNECT.storageValue })
        assertEquals(JournalEntrySource.HEALTH_CONNECT.storageValue, notes().single().source)
    }

    @Test
    fun durationIsAtLeastOneMinute() {
        write(at(0))
        write(at(0, rate = 1.2))
        assertTrue(basal().all { (it.durationMinutes ?: 0) >= 1 })
    }

    // ---------------------------------------------------------------- CareLinkIob

    @Test
    fun activeInsulinGoesToTheIobSnapshotWithItsOwnTime() {
        CareLinkIob.publish(PumpNow(now - 2 * min, iobUnits = 2.5, iobAtMs = now - 3 * min))
        val s = ApiIobSnapshot.fresh(now)!!
        assertEquals(2.5f, s.iobUnits, 1e-6f)
        assertEquals(now - 3 * min, s.timestampMillis)
        assertTrue("their own eIOB stays", s.eiobUnits.isNaN())
        assertTrue("their own COB stays", s.cobGrams.isNaN())
    }

    @Test
    fun activeInsulinFromTheFutureIsDroppedNearFutureStampedNow() {
        CareLinkIob.publish(PumpNow(now, iobUnits = 1.0, iobAtMs = now + 10 * min))
        assertNull(ApiIobSnapshot.fresh(now))
        CareLinkIob.publish(PumpNow(now, iobUnits = 1.0, iobAtMs = now + 2 * min))
        assertEquals(now, ApiIobSnapshot.fresh(now)!!.timestampMillis)
        CareLinkIob.publish(null)
        assertEquals(1.0f, ApiIobSnapshot.fresh(now)!!.iobUnits, 1e-6f)
    }
}
