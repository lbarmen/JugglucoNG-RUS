package tk.glucodata.rus.carelink

/**
 * JugglucoNG-RUS, CareLink: pump boluses and calculator carbs into upstream's journal. Stage 4c leaves only the
 * signature for [CareLinkPoller]; the body is part 4d (plan, "Technique": `JournalRepository().upsertEntry`, keys,
 * own edits kept, cancelled bolus removed).
 */
object CareLinkJournalWriter {

    /**
     * Writes [events] of one answer (all 24 h of the cloud window). [firstPoll] - the first poll after a sign-in: the
     * whole window is taken. A failure throws: the poll then does not move `lastDataAtMs` and the next poll writes again.
     */
    suspend fun apply(events: List<ClEvent>, firstPoll: Boolean) {
        // part 4d
    }
}
