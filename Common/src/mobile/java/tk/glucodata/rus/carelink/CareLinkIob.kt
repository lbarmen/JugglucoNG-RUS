package tk.glucodata.rus.carelink

/**
 * JugglucoNG-RUS, CareLink: the pump's active insulin for upstream's IoB plate, notification and widgets
 * (`ApiIobSnapshot.update`). Stage 4c leaves only the signature for [CareLinkPoller]; the body is part 4d
 * (plan, decision 9).
 */
object CareLinkIob {

    /**
     * [pumpNow] - the snapshot of this answer (`iobUnits` with its own `iobAtMs`, already not later than now); null -
     * the empty answer: nothing to publish. Not suspend: a quick in-memory update.
     */
    fun publish(pumpNow: PumpNow?) {
        // part 4d
    }
}
