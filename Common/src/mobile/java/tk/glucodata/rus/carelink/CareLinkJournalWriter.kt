package tk.glucodata.rus.carelink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * JugglucoNG-RUS, CareLink (stage 4d): pump boluses and calculator carbs into upstream's journal, each once (plan,
 * decisions 3-6 and "Technique").
 *
 * - Key `carelink:<type>:<pump time>:<n>:insulin|carbs` (the marker key, no pump serial). Upstream's upsert finds the
 *   row by it, so a repeated answer never makes a second row.
 * - Bolus: running - the programmed dose and the note "in progress"; finished - the delivered dose, the same row.
 *   Finished with 0 delivered (cancelled) - the row and its carbs are deleted with `deleteEntry` (it queues the
 *   deletion for Nightscout; deleting by key does not). Square and dual wave - one row with the sum and a note.
 * - Calculator carbs: a MEAL marker up to 10 s before the bolus -> a `carbs` row (only with "record calculator carbs"
 *   on; no bolus - nothing; the absorption time is upstream's default). Meter readings are not written.
 * - A row the person changed (`updatedAt` later than our last write) or deleted is never written over or brought back
 *   ([CareLinkSeenStore]).
 * - Source of a new row: Health Connect when "send pump records to Nightscout" is on, else API (a mirror source:
 *   upstream sends it nowhere). A row keeps the source it was made with.
 * - A running bolus whose marker time moved by up to 60 s (its old key gone from the answer) keeps its row under the
 *   new key (upstream's `retagSourceRecordId`), as Glucove's rekey.
 *
 * The whole answer (24 h) is written every poll: the record of what was written makes it cheap, and no poll can lose
 * a bolus (critic 4c, C1). A row found by key but not in the record (the record file was lost, or the write ended
 * before the record was saved) is taken as ours as it stands. A failure throws: the poll then does not move its data
 * time and the next poll writes again.
 */
object CareLinkJournalWriter {

    /** Calculator carbs belong to a bolus when their marker is at most this much earlier (Glucove: 1-3 s). */
    const val PAIR_WINDOW_MS = 10_000L

    /** A running bolus whose marker moved by at most this much keeps its row (Glucove's rekey). */
    const val REKEY_MS = 60_000L

    const val KIND_INSULIN = "insulin"
    const val KIND_CARBS = "carbs"

    fun insulinKey(e: ClEvent): String = "carelink:" + e.key.replace('|', ':') + ":" + KIND_INSULIN

    fun carbsKey(meal: ClEvent): String = "carelink:" + meal.key.replace('|', ':') + ":" + KIND_CARBS

    /**
     * Writes [events] of one answer. [firstPoll] - the first poll after a sign-in; every poll takes the whole answer,
     * so it changes nothing here (kept in the contract for the log). A failure throws.
     */
    suspend fun apply(context: Context, events: List<ClEvent>, firstPoll: Boolean) {
        val boluses = events.filter { it.kind == ClKind.BOLUS && it.dose != null }
        if (boluses.isEmpty()) return
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val now = CareLinkAuth.clock()
            val seen = CareLinkSeenStore.load(app, now)
            try {
                Run(app, seen, CareLinkJournal(app)).write(boluses, events)
            } finally {
                CareLinkSeenStore.save(app, seen, now)
            }
        }
    }

    private class Run(val app: Context, val seen: SeenData, val j: CareLinkJournal) {
        private val settings = CareLinkSettings(app)
        private var preset: Long? = null
        private var presetRead = false

        private suspend fun presetId(): Long? {
            if (!presetRead) {
                preset = CareLinkInsulin.presetId(app)
                presetRead = true
            }
            return preset
        }

        private fun newSource(): JournalEntrySource =
            if (settings.sendToNightscout) JournalEntrySource.HEALTH_CONNECT else JournalEntrySource.API

        suspend fun write(boluses: List<ClEvent>, events: List<ClEvent>) {
            val keysNow = boluses.map { CareLinkJournalWriter.insulinKey(it) }.toSet()
            val meals = events.filter { it.kind == ClKind.MEAL && (it.grams ?: 0.0) > 0.0 }
            val pairedMeals = HashSet<String>()
            for (b in boluses.sortedWith(compareBy({ it.atMs }, { it.key }))) {
                val key = CareLinkJournalWriter.insulinKey(b)
                val rec = seen.records[key] ?: rekey(b, key, keysNow)
                val window = (b.atMs - CareLinkJournalWriter.PAIR_WINDOW_MS)..b.atMs
                val meal = meals.filter { it.key !in pairedMeals && it.atMs in window }.maxByOrNull { it.atMs }
                if (meal != null) pairedMeals += meal.key
                val mealKey = meal?.let { CareLinkJournalWriter.carbsKey(it) }
                val cancelled = b.completed == true && (b.dose ?: 0.0) <= CareLinkMarkers.EPS
                bolus(b, key, rec, cancelled, mealKey)
                if (meal != null && mealKey != null) {
                    carbs(meal, mealKey, cancelled)
                } else if (cancelled) {
                    // the carbs of a cancelled bolus go even when their marker has left the answer
                    seen.records[key]?.carbsKey?.let { removeCarbs(it) }
                }
            }
        }

        /**
         * The row of [key] as the record knows it: null record - found by key and adopted, or nothing; a row gone -
         * deleted by the person; a later `updatedAt` - changed by the person. Returns the row to write over, or null
         * when the row is the person's now (or does not exist yet: then [Found.row] is null and [Found.free] true).
         */
        private suspend fun find(key: String, rec: SeenRecord?, adopt: (JournalEntryEntity) -> SeenRecord): Found {
            if (rec != null && rec.state != SeenState.LIVE) return Found(null, rec, free = false)
            if (rec == null) {
                val row = j.byKey(key) ?: return Found(null, null, free = true)
                val adopted = adopt(row)
                seen.records[key] = adopted
                return Found(row, adopted, free = true)
            }
            val row = j.row(rec.rowId, key)
            if (row == null) {
                if (rec.rowId == null) return Found(null, rec, free = true)
                seen.records[key] = rec.copy(state = SeenState.DELETED)
                return Found(null, rec, free = false)
            }
            if (rec.writtenAt != null && row.updatedAt > rec.writtenAt) {
                seen.records[key] = rec.copy(state = SeenState.EDITED)
                return Found(null, rec, free = false)
            }
            return Found(row, rec, free = true)
        }

        private class Found(val row: JournalEntryEntity?, val rec: SeenRecord?, val free: Boolean)

        private suspend fun bolus(b: ClEvent, key: String, recIn: SeenRecord?, cancelled: Boolean, mealKey: String?) {
            val f = find(key, recIn) { row ->
                SeenRecord(
                    CareLinkJournalWriter.KIND_INSULIN, b.atMs, row.id, row.updatedAt, row.source,
                    dose = row.amount?.toDouble(), completed = b.completed, note = row.note, carbsKey = mealKey,
                )
            }
            if (!f.free) return
            val row = f.row
            val rec = f.rec
            if (cancelled) {
                row?.let { j.delete(it.id) }
                seen.records[key] = (rec ?: SeenRecord(CareLinkJournalWriter.KIND_INSULIN, b.atMs)).copy(
                    rowId = row?.id, dose = 0.0, completed = true, carbsKey = rec?.carbsKey ?: mealKey,
                    state = SeenState.REMOVED,
                )
                return
            }
            val dose = b.dose ?: return
            val note = note(b)
            if (row != null && rec != null && rec.dose != null && abs(rec.dose - dose) <= CareLinkMarkers.EPS &&
                rec.completed == b.completed && rec.note == note && row.timestamp == b.atMs
            ) return
            val written = j.upsert(
                JournalEntryInput(
                    id = row?.id,
                    timestamp = b.atMs,
                    type = JournalEntryType.INSULIN,
                    title = app.getString(R.string.rus_carelink_journal_bolus),
                    note = note,
                    amount = dose.toFloat(),
                    insulinPresetId = if (row != null) row.insulinPresetId else presetId(),
                    source = row?.let { JournalEntrySource.fromStorage(it.source) } ?: newSource(),
                    sourceRecordId = key,
                )
            )
            seen.records[key] = SeenRecord(
                CareLinkJournalWriter.KIND_INSULIN, b.atMs, written.id, written.updatedAt, written.source, dose = dose,
                completed = b.completed, note = note, carbsKey = rec?.carbsKey ?: mealKey,
            )
        }

        private suspend fun carbs(meal: ClEvent, key: String, cancelled: Boolean) {
            val grams = meal.grams ?: return
            val f = find(key, seen.records[key]) { row ->
                SeenRecord(
                    CareLinkJournalWriter.KIND_CARBS, meal.atMs, row.id, row.updatedAt, row.source,
                    grams = row.amount?.toDouble(),
                )
            }
            if (!f.free) return
            val row = f.row
            val rec = f.rec
            if (cancelled) {
                removeFound(key, row, rec, meal.atMs)
                return
            }
            // new calculator carbs only with the switch on; rows already written stay ours either way
            if (row == null && !settings.writeCarbs) return
            if (row != null && rec != null && rec.grams != null && abs(rec.grams - grams) <= CareLinkMarkers.EPS &&
                row.timestamp == meal.atMs
            ) return
            val written = j.upsert(
                JournalEntryInput(
                    id = row?.id,
                    timestamp = meal.atMs,
                    type = JournalEntryType.CARBS,
                    title = app.getString(R.string.rus_carelink_journal_carbs),
                    amount = grams.toFloat(),
                    source = row?.let { JournalEntrySource.fromStorage(it.source) } ?: newSource(),
                    sourceRecordId = key,
                )
            )
            seen.records[key] = SeenRecord(
                CareLinkJournalWriter.KIND_CARBS, meal.atMs, written.id, written.updatedAt, written.source, grams = grams,
            )
        }

        private suspend fun removeCarbs(key: String) {
            val rec = seen.records[key] ?: return
            val f = find(key, rec) { r -> rec.copy(rowId = r.id) }
            if (!f.free) return
            removeFound(key, f.row, f.rec, rec.atMs)
        }

        private suspend fun removeFound(key: String, row: JournalEntryEntity?, rec: SeenRecord?, atMs: Long) {
            row?.let { j.delete(it.id) }
            seen.records[key] = (rec ?: SeenRecord(CareLinkJournalWriter.KIND_CARBS, atMs))
                .copy(rowId = row?.id, state = SeenState.REMOVED)
        }

        /**
         * The running bolus [b] has no record under [key]: a running one of ours within
         * [CareLinkJournalWriter.REKEY_MS] whose key is gone from the answer is the same bolus with a moved marker -
         * its row takes the new key, edits and all.
         */
        private suspend fun rekey(b: ClEvent, key: String, keysNow: Set<String>): SeenRecord? {
            val old = seen.records.entries
                .filter { (k, r) ->
                    k != key && k !in keysNow && r.kind == CareLinkJournalWriter.KIND_INSULIN && r.completed == false &&
                        abs(r.atMs - b.atMs) <= CareLinkJournalWriter.REKEY_MS
                }
                .minByOrNull { abs(it.value.atMs - b.atMs) } ?: return null
            if (j.byKey(key) != null) return null
            val rec = old.value
            seen.records.remove(old.key)
            val row = rec.rowId?.let { j.byId(it) }
            val moved = when {
                rec.state != SeenState.LIVE -> rec
                row == null -> rec.copy(state = SeenState.DELETED)
                else -> {
                    val edited = rec.writtenAt != null && row.updatedAt > rec.writtenAt
                    if (!j.retag(row.id, key)) {
                        seen.records[old.key] = rec
                        return null
                    }
                    val after = j.byId(row.id)
                    if (edited) rec.copy(state = SeenState.EDITED) else rec.copy(writtenAt = after?.updatedAt ?: rec.writtenAt)
                }
            }
            seen.records[key] = moved
            return moved
        }

        private fun note(b: ClEvent): String? {
            val parts = ArrayList<String>(2)
            if (b.extended) parts += app.getString(R.string.rus_carelink_note_extended, b.bolusType ?: "")
            if (b.completed == false) {
                val programmed = CareLinkJournalWriter.units(b.programmedUnits ?: b.dose ?: 0.0)
                parts += app.getString(R.string.rus_carelink_note_running, programmed)
            } else if (b.interrupted) {
                val delivered = CareLinkJournalWriter.units(b.deliveredUnits ?: 0.0)
                val programmed = CareLinkJournalWriter.units(b.programmedUnits ?: 0.0)
                parts += app.getString(R.string.rus_carelink_note_interrupted, delivered, programmed)
            }
            return parts.joinToString("; ").takeIf { it.isNotBlank() }
        }
    }

    internal fun units(v: Double): String = DecimalFormat("0.###").format(v)
}

/**
 * JugglucoNG-RUS, CareLink (stage 4d): the few journal calls of the writers. Writes only through upstream's
 * `JournalRepository` (its side effects: IOB, Nightscout wake, the watch, the screen); reads a row by id or key
 * straight from their DAO (the repository has no such read).
 */
internal class CareLinkJournal(app: Context) {
    private val repo = JournalRepository()
    private val dao = HistoryDatabase.getInstance(app.applicationContext).journalDao()

    suspend fun byId(id: Long): JournalEntryEntity? = dao.getEntryById(id)

    suspend fun byKey(key: String): JournalEntryEntity? = dao.getEntryBySourceRecordId(key)

    /** By the id the record holds, else by key (the person's editor may not keep the key). */
    suspend fun row(id: Long?, key: String): JournalEntryEntity? = id?.let { dao.getEntryById(it) } ?: byKey(key)

    /** Writes and reads the row back: its id and the `updatedAt` upstream stamped. */
    suspend fun upsert(input: JournalEntryInput): JournalEntryEntity {
        repo.upsertEntry(input)
        val key = input.sourceRecordId ?: error("no key")
        return byKey(key) ?: error("row not found after write")
    }

    suspend fun delete(id: Long) = repo.deleteEntry(id)

    suspend fun retag(id: Long, key: String): Boolean = repo.retagSourceRecordId(id, key)
}
