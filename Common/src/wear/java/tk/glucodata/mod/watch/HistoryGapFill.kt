package tk.glucodata.mod.watch

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import tk.glucodata.NotificationHistorySource

/**
 * JugglucoNGmod, watch block: fills a hole in the watch's glucose history after the watch was off or away from the
 * phone. The phone pushes only an 8-minute tail per reading (upstream's WearSync2), so after a reboot the newest
 * reading jumps over the hole, and upstream's routine request ("from the newest reading minus 15 minutes") never
 * reaches back to it. Only a deep request (opening the app on the watch) did.
 *
 * On each `/mod/tick` (every 5 minutes, [ModWearListener]) we read the watch's history of the last [WINDOW_MS] in the
 * background, find the earliest hole longer than [GAP_MS] between two readings, and ask the phone for everything
 * since [LEAD_MS] before it, in upstream's own request format ([encodeRequest], `/sync2/req`) through
 * [WatchPhoneLink]. Upstream's phone answers any request shallower than its 14-day horizon from the asked start
 * (WearSync2.onRequest), and the watch's ingest writes into empty slots - no hook in their files.
 *
 * Repeats: the same hole is asked for at most every [RETRY_MS], [MAX_TRIES] times; a hole still open after that is
 * one the phone does not have either (the sensor was not reading) and is dropped until it leaves the window. The
 * state is kept in our preferences file, so a restart of the watch process does not start the count again.
 * Edges are not holes: before the first reading of the window (a new sensor, a fresh watch) and after the newest one
 * (upstream's routine request covers that).
 *
 * Log (tag `ModWatch`): counts, lengths and ages in minutes and the request start in epoch seconds (it matches the
 * phone's "served N triples ... since S" line of WearSync2); never a glucose value.
 */
object HistoryGapFill {

    /** How far back the watch's history is read on a tick. */
    const val WINDOW_MS = 48 * 3_600_000L

    /** Never ask for more than this (cost of one serve: ~1 reading a minute, 360 per message). */
    const val MAX_DEPTH_MS = 72 * 3_600_000L

    /** A step between two readings longer than this is a hole (Sibionics and Libre: 1 a minute, Dexcom: 5). */
    const val GAP_MS = 12 * 60_000L

    /** The request starts this much before the hole, so the readings either side overlap. */
    const val LEAD_MS = 5 * 60_000L

    /** The same hole is not asked for again sooner than this (a tick may come every 5 minutes). */
    const val RETRY_MS = 10 * 60_000L

    /** Requests for one hole before it is dropped. */
    const val MAX_TRIES = 3

    /** Ticks closer than this to the last scan are ignored (cycle, battery and start ticks may come together). */
    const val MIN_SCAN_MS = 60_000L

    /** Upstream's WearSync2.VERSION - the request is `[u8 version][i64 fromSec][u8 force]`, big-endian. */
    const val WIRE_VERSION = 1

    private const val KEY_START = "gap_fill_start"
    private const val KEY_TRIES = "gap_fill_tries"
    private const val KEY_LAST = "gap_fill_last"
    private const val KEY_DEAD = "gap_fill_dead"

    /** A hole between the reading at [startMs] and the next one at [endMs]. */
    data class Gap(val startMs: Long, val endMs: Long)

    /** The hole being asked for ([gapStartMs], 0 - none), its requests so far, the last one, and dropped holes. */
    data class State(
        val gapStartMs: Long = 0L,
        val tries: Int = 0,
        val lastRequestMs: Long = 0L,
        val dead: Set<Long> = emptySet(),
    )

    /** What a scan decided: [fromSec] > 0 - send a request since then; [gap] - the hole it is for, if any. */
    data class Decision(val gap: Gap?, val fromSec: Long, val tries: Int, val wait: Boolean, val state: State)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ModGapFill").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val lastScanMs = AtomicLong(0L)

    /** Timestamps (ms) of the watch's readings since the given start; replaced in tests. */
    @Volatile
    internal var history: (Long) -> List<Long> = { startMs ->
        NotificationHistorySource.getRawHistory(startMs).map { it.timestamp }
    }

    /** Sends a request to the phone and waits for it; true when a phone took it. Replaced in tests. */
    @Volatile
    internal var sender: (Context, ByteArray) -> Boolean = { context, data -> WatchPhoneLink.sendSyncRequest(context, data) }

    /** Runs a scan off the caller's thread; replaced in tests. */
    @Volatile
    internal var runner: (Runnable) -> Unit = { worker.execute(it) }

    /** A `/mod/tick` at [nowMs]: queues one scan unless one runs or ran within [MIN_SCAN_MS]. Never throws. */
    fun onTick(context: Context, nowMs: Long) {
        try {
            val last = lastScanMs.get()
            if (last != 0L && nowMs - last in 0 until MIN_SCAN_MS) return
            if (!running.compareAndSet(false, true)) return
            lastScanMs.set(nowMs)
            val app = context.applicationContext ?: context
            runner {
                try {
                    scan(app, nowMs)
                } catch (t: Throwable) {
                    ModWatchLog.w("gap: scan failed: ${t.javaClass.simpleName}")
                } finally {
                    running.set(false)
                }
            }
        } catch (t: Throwable) {
            running.set(false)
            ModWatchLog.w("gap: tick failed: ${t.javaClass.simpleName}")
        }
    }

    /** One scan at [nowMs]: read, decide, send, keep the state. */
    internal fun scan(context: Context, nowMs: Long) {
        val windowStart = nowMs - WINDOW_MS
        val stamps = history(windowStart)
        val gaps = findGaps(stamps, windowStart, nowMs)
        val decision = decide(gaps, load(context), nowMs)
        save(context, decision.state)
        val gap = decision.gap
        when {
            gap == null -> ModWatchLog.i("gap: none, holes=${gaps.size} points=${stamps.size}")
            decision.wait -> ModWatchLog.i(
                "gap: waiting ${describe(gap, nowMs)}, holes=${gaps.size} try=${decision.tries}/$MAX_TRIES",
            )
            else -> {
                ModWatchLog.i(
                    "gap: found ${describe(gap, nowMs)}, holes=${gaps.size}, request since ${decision.fromSec} " +
                        "try=${decision.tries}/$MAX_TRIES",
                )
                if (!sender(context, encodeRequest(decision.fromSec))) ModWatchLog.i("gap: request not delivered")
            }
        }
    }

    private fun describe(gap: Gap, nowMs: Long): String =
        "len=${(gap.endMs - gap.startMs) / 60_000L}min from -${(nowMs - gap.startMs) / 60_000L}min " +
            "to -${(nowMs - gap.endMs) / 60_000L}min"

    /**
     * Every hole longer than [GAP_MS] between two readings in [windowStartMs]..[nowMs], oldest first. Unsorted input
     * and repeated timestamps are fine; readings outside the window are ignored.
     */
    fun findGaps(timestampsMs: List<Long>, windowStartMs: Long, nowMs: Long): List<Gap> {
        val sorted = timestampsMs.filter { it in windowStartMs..nowMs }.distinct().sorted()
        val gaps = ArrayList<Gap>()
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] > GAP_MS) gaps.add(Gap(sorted[i - 1], sorted[i]))
        }
        return gaps
    }

    /** The request start (epoch seconds) for [gap]: [LEAD_MS] before it, never deeper than [MAX_DEPTH_MS]. */
    fun requestFromSec(gap: Gap, nowMs: Long): Long = maxOf(gap.startMs - LEAD_MS, nowMs - MAX_DEPTH_MS) / 1000L

    /** Upstream's `/sync2/req` body: version, start in epoch seconds, force (0: the phone keeps its own spacing). */
    fun encodeRequest(fromSec: Long, force: Boolean = false): ByteArray =
        ByteBuffer.allocate(10)
            .put(WIRE_VERSION.toByte())
            .putLong(fromSec)
            .put((if (force) 1 else 0).toByte())
            .array()

    /**
     * Which hole of [gaps] to ask for at [nowMs], given the [state] after the last scan. The earliest hole not dropped
     * is the target; a new target starts its count again. A target asked for [MAX_TRIES] times and still open
     * [RETRY_MS] after the last request is dropped, and the next one is considered at once. Dropped holes older than
     * the window are forgotten. A last request in the future (the clock set back) counts as long ago.
     */
    fun decide(gaps: List<Gap>, state: State, nowMs: Long): Decision {
        val windowStart = nowMs - WINDOW_MS
        val dead = state.dead.filterTo(HashSet()) { it >= windowStart }
        var current = state
        while (true) {
            val target = gaps.firstOrNull { it.startMs !in dead }
                ?: return Decision(null, 0L, 0, false, State(dead = dead))
            if (target.startMs != current.gapStartMs) current = State(target.startMs, 0, 0L, dead)
            val sinceLast = nowMs - current.lastRequestMs
            val recent = current.tries > 0 && sinceLast in 0 until RETRY_MS
            if (recent) return Decision(target, 0L, current.tries, true, current.copy(dead = dead))
            if (current.tries >= MAX_TRIES) {
                dead.add(target.startMs)
                ModWatchLog.i("gap: dropped after $MAX_TRIES tries, len=${(target.endMs - target.startMs) / 60_000L}min")
                continue
            }
            val tries = current.tries + 1
            return Decision(
                target, requestFromSec(target, nowMs), tries, false, State(target.startMs, tries, nowMs, dead),
            )
        }
    }

    internal fun load(context: Context): State {
        val prefs = PhoneBatteryStore.prefs(context)
        val dead = prefs.getString(KEY_DEAD, "").orEmpty().split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }
        return State(prefs.getLong(KEY_START, 0L), prefs.getInt(KEY_TRIES, 0), prefs.getLong(KEY_LAST, 0L), dead)
    }

    internal fun save(context: Context, state: State) {
        PhoneBatteryStore.prefs(context).edit()
            .putLong(KEY_START, state.gapStartMs)
            .putInt(KEY_TRIES, state.tries)
            .putLong(KEY_LAST, state.lastRequestMs)
            .putString(KEY_DEAD, state.dead.sorted().joinToString(","))
            .apply()
    }

    /** Tests: forget the in-memory pacing. */
    internal fun resetForTest() {
        running.set(false)
        lastScanMs.set(0L)
    }
}
