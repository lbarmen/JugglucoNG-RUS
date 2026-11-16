package tk.glucodata.rus.carelink

/**
 * JugglucoNG-RUS, CareLink: the pump's actual basal rate as TEMP_BASAL segments and stops as notes. Stage 4c leaves
 * only the signature for [CareLinkPoller]; the body is part 4d (plan, decision 1).
 */
object CareLinkBasalWriter {

    /**
     * [pumpNow] - the snapshot of this answer: null - the empty answer (a gap, not a continuation); `basalRate == null`
     * - the answer had no basal object (also a gap). [atMs] - the poll time. A failure throws: the poll then does not
     * move `lastDataAtMs`.
     */
    suspend fun apply(pumpNow: PumpNow?, atMs: Long) {
        // part 4d
    }
}
