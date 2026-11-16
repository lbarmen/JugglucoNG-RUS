package tk.glucodata.rus.carelink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * JugglucoNG-RUS, CareLink (stage 4d): the pump's actual basal rate as upstream's TEMP_BASAL segments (bars on the
 * chart, `Temp Basal` absolute in Nightscout) and stops as notes (plan, decision 1).
 *
 * Each snapshot gives the rate now: stopped - 0; absolute temporary - its rate; percentage temporary - the last normal
 * rate (remembered here) times the percent, no normal rate known - a gap; otherwise `basal.basalRate`. A new rate is a
 * new segment, the last one is closed at the change (the boundary is the poll that saw it, up to ~7 min late). The
 * running segment is written 30 min ahead and moved on when less than 10 min are left, so its Nightscout copy changes
 * about every 20 min, not every 5; upstream's chart cuts a segment at the next one's start. A gap in the data (empty
 * answer, no basal object, more than 30 min between snapshots, a rate not known) closes the segment at its last data
 * time: a gap, not a continuation. Duration at least 1 min and a finite rate: one refused record stops all of
 * upstream's upload.
 *
 * Stop: upstream's chart draws no bar for 0 U/h, so a note "Pump stopped" with "HH:MM–…" is written too; its end
 * is filled in when the pump runs again (unless the person changed the note).
 *
 * Times are the snapshot's data time (`dataAtMs`), never the poll time (critic 4c, C3). Keys:
 * `carelink:basal:<start ms>:0:basal`, `carelink:stop:<start ms>:0:note`. Source as for boluses: Health Connect with
 * "send pump records to Nightscout", else API; a row keeps its own.
 */
object CareLinkBasalWriter {

    /** The running segment is written this far ahead of its last data. */
    const val AHEAD_MS = 30 * 60_000L

    /** ... and moved on when less than this is left. */
    const val EXTEND_BEFORE_MS = 10 * 60_000L

    /** More than this between two snapshots is a gap. */
    const val GAP_MS = 30 * 60_000L

    /** Rates closer than this are the same (pump steps are 0.025 U/h). */
    const val SAME_RATE = 0.001

    @Volatile
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    fun basalKey(startMs: Long): String = "carelink:basal:$startMs:0:basal"

    fun stopKey(startMs: Long): String = "carelink:stop:$startMs:0:note"

    /**
     * [pumpNow] - the snapshot of this answer: null - the empty answer (a gap); `basalKnown == false` - no basal
     * object (a gap, unless the pump says it is stopped). [atMs] - the poll time (only for the 48 h record window).
     * A snapshot older than the state's is not passed at all (the poller). A failure throws.
     */
    suspend fun apply(context: Context, pumpNow: PumpNow?, atMs: Long) {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val seen = CareLinkSeenStore.load(app, atMs)
            try {
                Run(app, seen, CareLinkJournal(app)).step(pumpNow)
            } finally {
                CareLinkSeenStore.save(app, seen, atMs)
            }
        }
    }

    /**
     * The rate the pump runs now, U/h; null - not known (a gap). A stop counts even without the basal object: the
     * cloud says it outright.
     */
    internal fun rateOf(p: PumpNow, lastNormal: Double?): Double? {
        if (p.suspended == true) return 0.0
        if (!p.basalKnown) return null
        val t = p.tempBasal ?: return p.basalRate
        val pct = t.percent
        if (pct != null) return lastNormal?.let { CareLinkMarkers.round3(it * pct / 100.0) }
        return t.rate
    }

    private class Run(val app: Context, val seen: SeenData, val j: CareLinkJournal) {
        private val settings = CareLinkSettings(app)

        private fun newSource(): JournalEntrySource =
            if (settings.sendToNightscout) JournalEntrySource.HEALTH_CONNECT else JournalEntrySource.API

        suspend fun step(p: PumpNow?) {
            if (p == null) {
                gap()
                return
            }
            val t = p.dataAtMs
            var open = seen.basalOpen
            if (open != null && t < open.lastDataAtMs) return
            if (p.basalKnown && p.tempBasal == null && p.suspended != true) {
                p.basalRate?.takeIf { it.isFinite() && it >= 0.0 }?.let { seen.lastNormalRate = it }
            }
            stopNote(p, t)
            val rate = CareLinkBasalWriter.rateOf(p, seen.lastNormalRate)?.takeIf { it.isFinite() && it >= 0.0 }
            if (rate == null) {
                gap()
                return
            }
            if (open != null && t - open.lastDataAtMs > CareLinkBasalWriter.GAP_MS) {
                gap()
                open = null
            }
            if (open == null) {
                start(rate, t)
                return
            }
            if (abs(rate - open.rate) > CareLinkBasalWriter.SAME_RATE) {
                writeSegment(open.key, open.startMs, open.rate, t)
                start(rate, t)
                return
            }
            var end = open.endMs
            if (end - t < CareLinkBasalWriter.EXTEND_BEFORE_MS) {
                end = t + CareLinkBasalWriter.AHEAD_MS
                writeSegment(open.key, open.startMs, open.rate, end)
            }
            seen.basalOpen = open.copy(endMs = end, lastDataAtMs = t)
        }

        /** No data: the running segment ends at its last data time. The stop note waits for the pump to run again. */
        private suspend fun gap() {
            val open = seen.basalOpen ?: return
            if (open.endMs != open.lastDataAtMs) writeSegment(open.key, open.startMs, open.rate, open.lastDataAtMs)
            seen.basalOpen = null
        }

        private suspend fun start(rate: Double, t: Long) {
            val key = CareLinkBasalWriter.basalKey(t)
            val end = t + CareLinkBasalWriter.AHEAD_MS
            writeSegment(key, t, rate, end)
            seen.basalOpen = BasalOpen(key, t, rate, end, t)
        }

        private suspend fun writeSegment(key: String, startMs: Long, rate: Double, endMs: Long) {
            val minutes = ((endMs - startMs + 59_999L) / 60_000L).coerceAtLeast(1L).toInt()
            val row = j.byKey(key)
            j.upsert(
                JournalEntryInput(
                    id = row?.id,
                    timestamp = startMs,
                    type = JournalEntryType.TEMP_BASAL,
                    title = app.getString(R.string.rus_carelink_journal_basal),
                    amount = rate.toFloat(),
                    durationMinutes = minutes,
                    source = row?.let { JournalEntrySource.fromStorage(it.source) } ?: newSource(),
                    sourceRecordId = key,
                )
            )
        }

        private suspend fun stopNote(p: PumpNow, t: Long) {
            val open = seen.stopOpen
            if (p.suspended == true) {
                if (open != null) return
                val key = CareLinkBasalWriter.stopKey(t)
                val row = j.byKey(key)
                val written = j.upsert(note(key, t, row, app.getString(R.string.rus_carelink_note_stop_open, hhmm(t))))
                seen.stopOpen = StopOpen(key, t, written.id, written.updatedAt)
                return
            }
            if (p.suspended != false || open == null) return
            seen.stopOpen = null
            val row = j.row(open.rowId, open.key) ?: return
            // the person changed the note: theirs
            if (open.writtenAt != null && row.updatedAt > open.writtenAt) return
            val text = app.getString(R.string.rus_carelink_note_stop, hhmm(open.startMs), hhmm(t))
            j.upsert(note(open.key, open.startMs, row, text))
        }

        private fun note(key: String, startMs: Long, row: JournalEntryEntity?, text: String) =
            JournalEntryInput(
                id = row?.id,
                timestamp = startMs,
                type = JournalEntryType.NOTE,
                title = app.getString(R.string.rus_carelink_journal_stop),
                note = text,
                source = row?.let { JournalEntrySource.fromStorage(it.source) } ?: newSource(),
                sourceRecordId = key,
            )

        private fun hhmm(ms: Long): String =
            DateTimeFormatter.ofPattern("HH:mm").withZone(CareLinkBasalWriter.zone()).format(Instant.ofEpochMilli(ms))
    }
}
