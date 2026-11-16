package tk.glucodata.rus.carelink

import android.content.Context

/**
 * JugglucoNG-RUS, CareLink: the pump's actual basal rate as TEMP_BASAL segments and stops as notes. Stage 4c leaves
 * only the signature for [CareLinkPoller]; the body is part 4d (plan, decision 1).
 */
object CareLinkBasalWriter {

    /**
     * [pumpNow] - the snapshot of this answer: null - the empty answer (a gap, not a continuation);
     * `basalKnown == false` - the answer had no basal object (also a gap). Segment times are the snapshot's
     * `dataAtMs`, never the poll time [atMs] (critic 4c, C3); a snapshot older than the state's is not passed at all.
     * A failure throws: the poll then does not move `lastDataAtMs`.
     */
    suspend fun apply(context: Context, pumpNow: PumpNow?, atMs: Long) {
        // part 4d
    }
}
