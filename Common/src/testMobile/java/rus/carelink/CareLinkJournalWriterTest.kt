package rus.carelink

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import rus.carelink.CareLinkJournalTestEnv.app
import rus.carelink.CareLinkJournalTestEnv.dao
import rus.carelink.CareLinkJournalTestEnv.rows
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.rus.carelink.CareLinkInsulin
import tk.glucodata.rus.carelink.CareLinkJournalWriter
import tk.glucodata.rus.carelink.CareLinkSettings
import tk.glucodata.rus.carelink.ClEvent
import tk.glucodata.rus.carelink.ClKind

/**
 * JugglucoNG-RUS, CareLink 4d: pump boluses and calculator carbs in upstream's real journal (Room under Robolectric):
 * once per key, running -> finished in one row, a cancelled bolus removed (deletion queued for Nightscout), the
 * person's edit and deletion kept, square wave, source by the switch, a moved marker keeps its row. Made-up doses and
 * times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkJournalWriterTest {

    private val now = 1_800_000_000_000L
    private val t0 = now - 2 * 3_600_000L

    @Before
    fun setUp() = CareLinkJournalTestEnv.setUp { now }

    @After
    fun tearDown() = CareLinkJournalTestEnv.tearDown()

    private fun bolus(
        sec: Int, programmed: Double, delivered: Double, completed: Boolean = true, type: String = "FAST",
    ) = ClEvent(
        ClKind.BOLUS, "INSULIN|2027-01-15T06:00:${"%02d".format(sec)}|0", t0 + sec * 1_000L,
        programmedUnits = programmed, deliveredUnits = delivered, completed = completed, bolusType = type,
    )

    private fun meal(sec: Int, grams: Double) =
        ClEvent(ClKind.MEAL, "MEAL|2027-01-15T06:00:${"%02d".format(sec)}|0", t0 + sec * 1_000L, grams = grams)

    private fun write(vararg events: ClEvent) = runBlocking { CareLinkJournalWriter.apply(app, events.toList(), false) }

    private fun insulin() = rows(JournalEntryType.INSULIN.storageValue)
    private fun carbs() = rows(JournalEntryType.CARBS.storageValue)

    @Test
    fun bolusIsWrittenOnceWithTheDefaultInsulin() {
        write(bolus(10, 2.0, 2.0))
        write(bolus(10, 2.0, 2.0))
        val row = insulin().single()
        assertEquals(2.0f, row.amount!!, 1e-6f)
        assertEquals(t0 + 10_000L, row.timestamp)
        assertEquals("carelink:INSULIN:2027-01-15T06:00:10:0:insulin", row.sourceRecordId)
        assertEquals("Pump", row.title)
        assertNull(row.note)
        assertEquals("switch off - a mirror source, sent nowhere", JournalEntrySource.API.storageValue, row.source)
        val default = runBlocking { CareLinkInsulin.presetId(app) }
        assertNotNull(default)
        assertEquals(default, row.insulinPresetId)
    }

    @Test
    fun calculatorCarbsGoWithTheirBolus() {
        write(meal(8, 45.0), bolus(10, 4.5, 4.5), meal(30, 20.0), meal(100, 15.0), bolus(125, 1.0, 1.0))
        val c = carbs()
        assertEquals("meal 2 s before the bolus - paired; 25 s before - not; no bolus - not", 1, c.size)
        assertEquals(45.0f, c.single().amount!!, 1e-6f)
        assertEquals(t0 + 8_000L, c.single().timestamp)
        assertNull("upstream's default absorption time", c.single().durationMinutes)
        assertEquals("carelink:MEAL:2027-01-15T06:00:08:0:carbs", c.single().sourceRecordId)
        assertEquals("Pump calculator", c.single().title)
        assertEquals(2, insulin().size)
    }

    @Test
    fun carbsSwitchOffWritesNoNewCarbs() {
        CareLinkSettings(app).writeCarbs = false
        write(meal(8, 45.0), bolus(10, 4.5, 4.5))
        assertTrue(carbs().isEmpty())
        assertEquals(1, insulin().size)
    }

    @Test
    fun runningThenFinishedIsOneRow() {
        write(bolus(10, 3.0, 1.0, completed = false))
        val running = insulin().single()
        assertEquals("programmed while running", 3.0f, running.amount!!, 1e-6f)
        assertTrue(running.note!!.contains("in progress"))
        write(bolus(10, 3.0, 3.0))
        val done = insulin().single()
        assertEquals(running.id, done.id)
        assertEquals(3.0f, done.amount!!, 1e-6f)
        assertNull(done.note)
    }

    @Test
    fun interruptedBolusKeepsTheDeliveredDoseAndSaysSo() {
        write(bolus(10, 3.0, 1.2))
        val row = insulin().single()
        assertEquals(1.2f, row.amount!!, 1e-6f)
        assertTrue(row.note!!.contains("1.2") && row.note!!.contains("3"))
    }

    @Test
    fun cancelledBolusIsRemovedWithItsCarbsAndTheDeletionIsQueued() {
        CareLinkSettings(app).sendToNightscout = true
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = false))
        val ins = insulin().single()
        val c = carbs().single()
        // both already in Nightscout
        runBlocking {
            dao.markEntryUploadedToNightscout(ins.id, "made-up-remote-1", now)
            dao.markEntryUploadedToNightscout(c.id, "made-up-remote-2", now)
        }
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = true))
        assertTrue(insulin().isEmpty())
        assertTrue(carbs().isEmpty())
        val queued = runBlocking { dao.getPendingNightscoutDeletes() }.map { it.entryId }.toSet()
        assertEquals(setOf(ins.id, c.id), queued)
        // the next answers carry it still: not brought back
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = true))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun cancelledBeforeAnyPollIsNeverWritten() {
        write(meal(8, 30.0), bolus(10, 3.0, 0.0))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun personsEditIsNotWrittenOver() {
        write(bolus(10, 3.0, 1.0, completed = false))
        val ours = insulin().single()
        Thread.sleep(5)
        runBlocking {
            JournalRepository().upsertEntry(
                JournalEntryInput(
                    id = ours.id, timestamp = ours.timestamp, type = JournalEntryType.INSULIN, title = ours.title,
                    note = "made-up own note", amount = 2.5f, insulinPresetId = ours.insulinPresetId,
                    source = JournalEntrySource.fromStorage(ours.source), sourceRecordId = ours.sourceRecordId,
                )
            )
        }
        write(bolus(10, 3.0, 3.0))
        val row = insulin().single()
        assertEquals(2.5f, row.amount!!, 1e-6f)
        assertEquals("made-up own note", row.note)
    }

    @Test
    fun personsDeletionIsNotBroughtBack() {
        write(meal(8, 20.0), bolus(10, 2.0, 2.0))
        val ins = insulin().single()
        runBlocking { JournalRepository().deleteEntry(ins.id) }
        write(meal(8, 20.0), bolus(10, 2.0, 2.0))
        assertTrue(insulin().isEmpty())
        assertEquals("the carbs were not deleted by the person - they stay", 1, carbs().size)
    }

    @Test
    fun squareWaveIsOneRowWithTheSumAndANote() {
        write(bolus(10, 3.0, 3.0, type = "SQUARE_WAVE"))
        val row = insulin().single()
        assertEquals(3.0f, row.amount!!, 1e-6f)
        assertTrue(row.note!!.contains("SQUARE_WAVE"))
    }

    @Test
    fun sourceFollowsTheSwitchForNewRowsAndNeverChangesForOldOnes() {
        write(bolus(10, 2.0, 1.0, completed = false))
        CareLinkSettings(app).sendToNightscout = true
        write(bolus(10, 2.0, 2.0), bolus(50, 1.0, 1.0))
        val byKey = insulin().associateBy { it.sourceRecordId }
        assertEquals(JournalEntrySource.API.storageValue, byKey["carelink:INSULIN:2027-01-15T06:00:10:0:insulin"]!!.source)
        assertEquals(2.0f, byKey["carelink:INSULIN:2027-01-15T06:00:10:0:insulin"]!!.amount!!, 1e-6f)
        assertEquals(
            JournalEntrySource.HEALTH_CONNECT.storageValue,
            byKey["carelink:INSULIN:2027-01-15T06:00:50:0:insulin"]!!.source,
        )
        // and back off: the Health Connect row stays Health Connect
        CareLinkSettings(app).sendToNightscout = false
        write(bolus(50, 1.0, 1.0), bolus(70, 0.5, 0.5))
        val again = insulin().associateBy { it.sourceRecordId }
        assertEquals(
            JournalEntrySource.HEALTH_CONNECT.storageValue,
            again["carelink:INSULIN:2027-01-15T06:00:50:0:insulin"]!!.source,
        )
        assertEquals(JournalEntrySource.API.storageValue, again["carelink:INSULIN:2027-01-15T06:00:70:0:insulin"]!!.source)
    }

    @Test
    fun movedMarkerOfARunningBolusKeepsItsRow() {
        write(bolus(10, 3.0, 1.0, completed = false))
        val running = insulin().single()
        // the finished marker came 20 s later; the old one is gone from the answer
        write(bolus(30, 3.0, 3.0))
        val row = insulin().single()
        assertEquals(running.id, row.id)
        assertEquals("carelink:INSULIN:2027-01-15T06:00:30:0:insulin", row.sourceRecordId)
        assertEquals(3.0f, row.amount!!, 1e-6f)
        assertEquals(t0 + 30_000L, row.timestamp)
        assertNull(row.note)
    }

    @Test
    fun finishedBolusWithTheSameDoseIsNotRewritten() {
        write(bolus(10, 2.0, 2.0))
        val first = insulin().single()
        Thread.sleep(5)
        write(bolus(10, 2.0, 2.0))
        assertEquals("no needless write (it would send the row to Nightscout again)", first.updatedAt, insulin().single().updatedAt)
    }

    @Test
    fun lostRecordAdoptsTheRowInsteadOfAddingOne() {
        write(bolus(10, 2.0, 2.0))
        tk.glucodata.rus.carelink.CareLinkSeenStore.clear(app)
        write(bolus(10, 2.0, 2.0))
        assertEquals(1, insulin().size)
        assertFalse(rows().isEmpty())
    }
}
