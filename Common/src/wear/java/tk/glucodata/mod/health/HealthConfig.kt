package tk.glucodata.mod.health

/**
 * JugglucoNGmod, watch block (part 4): the numbers of heart rate and steps collection on the watch, as Glucove ran
 * them on the same watch (its WearConfig). The rhythm of 5 minutes is the phone's `/mod/tick`; a delivery of Health
 * Services sends on its own only when no batch went for [SEND_PERIOD_MS].
 */
object HealthConfig {
    /** Heart rate goes as one mean per window of this length (aligned to the clock: :00, :05, :10 ...). */
    const val HR_WINDOW_MS = 5 * 60_000L

    /** A window closes this long after its end after "deliver now" (the data up to now is in)... */
    const val HR_GRACE_REFRESH_MS = 5_000L

    /** ...and this long after its end on a normal delivery (data may come late). */
    const val HR_GRACE_DATA_MS = 120_000L

    /** How long to wait for data after asking the source to deliver what it collected. */
    const val FLUSH_WAIT_MS = 1_500L

    /** A delivery sends a batch by itself only when the last send or attempt is at least this old. */
    const val SEND_PERIOD_MS = 5 * 60_000L

    /** The status line goes at least this often (and at once when its permanent part changes). */
    const val STATUS_PERIOD_MS = 30 * 60_000L

    /** Queue limit while the phone is away (several weeks of means and step counts); the oldest files go first. */
    const val BUFFER_MAX_BYTES = 20L * 1024 * 1024

    /** One Data Layer put waits at most this long for the local accept. */
    const val PUT_TIMEOUT_MS = 10_000L

    /** One hand-over sends files for at most this long; the rest goes next time (a long-away phone, many files). */
    const val SEND_BUDGET_MS = 20_000L
}
