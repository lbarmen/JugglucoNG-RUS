package tk.glucodata.mod.watch

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertActions
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.AlarmActivityAccess

/**
 * JugglucoNGmod, watch block: the standard alert that is going on now and its snooze, the same on the phone and on
 * the watch (watch plan, decision 3).
 *
 * "Going on" is a kind from [AlertType.settingsEntries] for which [Notify.isCurrentStandardAlert] holds - presented and
 * not taken back. Not `Notify.resolveAlertKind`: that one can return the last alert after it was over. Custom (user)
 * alerts are not covered: upstream has no public "which custom alert is ringing".
 *
 * The snooze is what upstream's "Snooze" button does: [AlertActions.handle] with the alert's default minutes (it
 * also sends their `/alertack` to the other device), then [AlarmActivityAccess.acknowledge] - `handle` stops the sound
 * but leaves a full-screen alarm open. Their alert code expects the main thread.
 */
object OngoingAlert {

    /** How long a listener thread waits for the main thread in [snoozeOngoingOnMain]. */
    const val MAIN_WAIT_MS = 5_000L

    /** The kind (AlertType id) of the standard alert going on now, `null` when there is none. */
    fun find(): Int? = AlertType.settingsEntries.firstOrNull { Notify.isCurrentStandardAlert(it.id) }?.id

    /** Snoozes [kind] as upstream's "Snooze" does. Main thread only: elsewhere refuses and returns false. */
    fun snooze(kind: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ModWatchLog.w("snooze refused: not on the main thread")
            return false
        }
        AlertActions.handle(kind, null, AlertActions.defaultSnoozeMinutes(kind, null))
        AlarmActivityAccess.acknowledge(kind)
        return true
    }

    /** On the main thread: snoozes the alert going on and returns its kind; `null` when there was none. */
    fun snoozeOngoing(): Int? {
        val kind = find() ?: return null
        return if (snooze(kind)) kind else null
    }

    /**
     * [snoozeOngoing] from any thread: runs it on the main thread and waits up to [waitMs]. `null` when there was no
     * alert, on a failure, or when the main thread did not answer in time.
     */
    fun snoozeOngoingOnMain(waitMs: Long = MAIN_WAIT_MS): Int? {
        if (Looper.myLooper() == Looper.getMainLooper()) return snoozeOngoing()
        val result = AtomicReference<Int?>(null)
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(snoozeOngoing())
            } catch (t: Throwable) {
                ModWatchLog.w("snooze failed: ${t.javaClass.simpleName}")
            } finally {
                done.countDown()
            }
        }
        if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
            ModWatchLog.w("snooze: main thread did not answer in time")
            return null
        }
        return result.get()
    }
}
