package tk.glucodata.mod.watch

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tk.glucodata.UiRefreshBus
import tk.glucodata.alerts.AlertRuntimeManager

/**
 * JugglucoNGmod, watch block (part 7o): the watch's alerts and notification look at a new reading as soon as it is
 * stored, not at upstream's next tick of 15 s (`AlertRuntimeManager.CHECK_INTERVAL_MS`). Every rise of upstream's
 * public `UiRefreshBus.revision` (the phone's calibration and values, `/sync2/cal` and `/sync2/chunk`, both bump it)
 * asks for upstream's public `AlertRuntimeManager.ensureMonitoring()` - under their lock, the same evaluation their
 * timer runs (`evaluateLocked`), which hands a new synced reading on to their alert path once (guarded by the reading's
 * time, `lastDeliveredReadingTimeMs`). No hook: nothing of theirs changes.
 *
 * The calibration signal brings no new reading: its check is the same as an early timer tick (`onDisplayCalibrationChanged`
 * and its suppression run only on the phone, `CalibrationManager`), so it is not filtered - filtering would cost a
 * resolution of the current reading as heavy as the check itself.
 *
 * Our own echo is not checked again (critic 7o, S1): a check that hands a new reading on goes through upstream's
 * `SuperGattCallback.dowithglucose`, which bumps the revision itself (`requestDataRefresh`). The revision right after
 * our check is kept ([RevisionGate]); a queued run that finds the revision no higher does nothing (upstream's 15 s
 * tick still covers data that came in during the check without a later signal).
 *
 * Paced by [SignalBudget] (3 checks in 20 s) and coalesced on one thread ([CoalescedRunner]): one check at a time, a
 * signal during it makes one more after it. Started lazily from [tk.glucodata.mod.health.hs.ModHealthStarter],
 * behind `Application.onCreate`; the collection runs in the background. Our failure never takes the process down.
 */
internal object FreshReadingAlerts {

    @Volatile private var started = false

    private val gate = RevisionGate()

    private val runner by lazy {
        CoalescedRunner("ModFreshAlerts", SignalBudget(), due = { gate.due(UiRefreshBus.revision.value) }) { check() }
    }

    /** At process start (a provider's `onCreate`, before `Application.onCreate`): [start] runs after it. */
    fun startAfterApplication() {
        try {
            Handler(Looper.getMainLooper()).post { start() }
        } catch (t: Throwable) {
            ModWatchLog.w("fresh reading alerts not posted: ${t.javaClass.simpleName}")
        }
    }

    private fun start() {
        if (started) return
        started = true
        try {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
                    ModWatchLog.w("fresh reading alerts failed: ${t.javaClass.simpleName}")
                },
            )
            scope.launch { UiRefreshBus.revision.drop(1).collect { runner.signal() } }
        } catch (t: Throwable) {
            ModWatchLog.w("fresh reading alerts not started: ${t.javaClass.simpleName}")
        }
    }

    private fun check() {
        val begin = SystemClock.elapsedRealtime()
        try {
            AlertRuntimeManager.ensureMonitoring()
            ModWatchLog.i("alerts checked took=${SystemClock.elapsedRealtime() - begin} ms")
        } catch (t: Throwable) {
            ModWatchLog.w("alerts check failed: ${t.javaClass.simpleName}")
        } finally {
            // the bump of our own hand-over (made during the check, on this thread) is not new data
            try {
                gate.checked(UiRefreshBus.revision.value)
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * The revision seen right after our last check (critic 7o, S1): a revision no higher holds nothing the check did not
 * see - or only the check's own echo. Thread-safe.
 */
internal class RevisionGate {
    @Volatile private var afterCheck = Long.MIN_VALUE

    /** A run at [revision] has something new to look at. */
    fun due(revision: Long): Boolean = revision > afterCheck

    /** A check ended at [revision]. */
    @Synchronized
    fun checked(revision: Long) {
        if (revision > afterCheck) afterCheck = revision
    }
}

/**
 * Runs [action] on its own thread for [signal]s: one run at a time; signals while a run waits to start are one run; a
 * signal during a run makes one run after it; runs keep to the [budget] (a run over it waits, still counted as queued);
 * a run that is not [due] when it starts does nothing and costs no budget. The [clock] is the budget's
 * ([SystemClock.elapsedRealtime] - it goes on in sleep).
 */
internal class CoalescedRunner(
    name: String,
    private val budget: SignalBudget,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val due: () -> Boolean = { true },
    private val action: () -> Unit,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    /** A run is scheduled and has not started yet (guarded by this). */
    private var queued = false

    fun signal() {
        synchronized(this) {
            if (queued) return
            queued = true
        }
        schedule(budget.waitMs(clock()))
    }

    private fun schedule(delayMs: Long) {
        try {
            executor.schedule({ run() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            synchronized(this) { queued = false }
            ModWatchLog.w("fresh reading run not scheduled: ${t.javaClass.simpleName}")
        }
    }

    private fun run() {
        // cleared first: a signal from now on means data this run may not see - one more run after it
        synchronized(this) { queued = false }
        val isDue = try {
            due()
        } catch (_: Throwable) {
            true
        }
        if (!isDue) return
        val wait = try {
            budget.waitMs(clock())
        } catch (_: Throwable) {
            0L
        }
        if (wait > 0L) {
            synchronized(this) {
                if (queued) return // a signal since has scheduled its own run
                queued = true
            }
            schedule(wait)
            return
        }
        try {
            budget.record(clock())
            action()
        } catch (_: Throwable) {
        }
    }
}
