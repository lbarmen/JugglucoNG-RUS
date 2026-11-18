package tk.glucodata.mod.health

/**
 * JugglucoNGmod, watch block (part 4b): when the Health Services subscription is made again, and the small rules
 * around it (Glucove's numbers: silent for 15 minutes - its failure of 23.10.2026, when the watch killed the process
 * and the passive deliveries never came back although the subscription was listed; at least every 12 hours). Plain
 * decisions for the tests; the calls are in `tk.glucodata.mod.health.hs` (the `mod` build only).
 */
object HealthSubscription {

    /** Health Services needs Android 11 (API 30); below it nothing is called (upstream's watch minSdk is 26). */
    const val MIN_SDK = 30

    /** No delivery and no subscription for this long: subscribe again (and no attempt more often). */
    const val SILENT_MS = 15 * 60_000L

    /** Subscribe again at least this often (a subscription might be lost without a word). */
    const val REREGISTER_MS = 12 * 60 * 60_000L

    fun supported(sdk: Int): Boolean = sdk >= MIN_SDK

    /**
     * Why to subscribe at [nowMs], or `null` - not now. Nothing without a permission and a subscription ([permsNow]
     * has neither heart rate nor activity; with a subscription in place the change of permissions drops it) or within
     * [SILENT_MS] of the last attempt ([attemptAt]; a failing or empty subscription is not
     * retried on every tick). Then: `new` - none in place ([registered] false); `perms` - the permissions changed since
     * it was made; `stale` - older than [REREGISTER_MS] (or made "in the future": the clock went back); `silent` - no
     * delivery ([lastDataAt]) and no subscription for over [SILENT_MS].
     */
    fun registerReason(
        nowMs: Long,
        permsNow: HealthPermissions.Perms,
        permsAtRegistration: String,
        registered: Boolean,
        registeredAt: Long,
        lastDataAt: Long,
        attemptAt: Long,
    ): String? {
        if (!permsNow.any && !registered) return null
        if (attemptAt > 0 && nowMs - attemptAt in 0 until SILENT_MS) return null
        return when {
            !registered -> "new"
            permsNow.short() != permsAtRegistration -> "perms"
            nowMs - registeredAt !in 0 until REREGISTER_MS -> "stale"
            nowMs - maxOf(lastDataAt, registeredAt) > SILENT_MS -> "silent"
            else -> null
        }
    }

    /** The subscription memory: `hr,steps`, `hr`, `steps` or empty. */
    fun typeNames(hr: Boolean, steps: Boolean): String = listOfNotNull("hr".takeIf { hr }, "steps".takeIf { steps }).joinToString(",")

    /**
     * The source's part of the status row (the permanent part: a change sends a status at once), never values:
     * `perm=hr1,bg1,act1;reg=hr+steps;regErr=-`.
     */
    fun statusFields(perms: HealthPermissions.Perms, registeredTypes: String, registerError: String): String =
        "perm=${perms.short()};reg=${registeredTypes.replace(',', '+').ifEmpty { "-" }};regErr=${registerError.ifEmpty { "-" }}"

    /** The ordinary time of the watch's boot (Health Services times count from it): now minus the time since boot. */
    fun bootEpochMs(nowMs: Long, elapsedSinceBootMs: Long): Long = nowMs - elapsedSinceBootMs
}
