package tk.glucodata.mod.watch

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * JugglucoNGmod, watch block (part 2): the 5-minute cycle of `/mod/tick` ([WatchTicker]) - a chain of one-time
 * WorkManager tasks under one unique name, because a periodic task runs at most every 15 minutes. Each step sends a
 * tick and queues the next: in [PERIOD_MS] when a watch was found, in [NO_WATCH_PERIOD_MS] when none was (without a
 * watch the phone is not woken for nothing; our watch appearing brings the cycle back at once, [WatchTicker.onWatchAppeared]).
 *
 * From outside the chain is queued with KEEP (a running chain stays), from inside with APPEND_OR_REPLACE: REPLACE
 * would cancel the running step itself, KEEP would end the chain. [restart] (REPLACE) starts it afresh at 5 minutes.
 */
class WatchTickWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        var found = false
        try {
            found = WatchTicker.send(applicationContext, ModWatchMessages.REASON_CYCLE, System.currentTimeMillis())
        } catch (t: Throwable) {
            ModWatchLog.w("tick step failed: ${t.javaClass.simpleName}")
        }
        try {
            scheduleNext(applicationContext, found)
        } catch (t: Throwable) {
            ModWatchLog.w("next tick not queued: ${t.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        /** Unique WorkManager name of the chain. */
        const val WORK = "mod-watch-tick"

        /** With our watch reachable: a tick every 5 minutes (watch plan, decision 5). */
        const val PERIOD_MS = 5 * 60_000L

        /** Without a watch: look again in an hour (dispatcher's decision). */
        const val NO_WATCH_PERIOD_MS = 60 * 60_000L

        /** The queued step waits [NO_WATCH_PERIOD_MS] (true), [PERIOD_MS] (false); `null` - not known in this process. */
        @Volatile
        var waitingLong: Boolean? = null
            internal set

        /** The delay before the next step after one that found a watch ([watchFound]) or not. */
        fun nextDelayMs(watchFound: Boolean): Long = if (watchFound) PERIOD_MS else NO_WATCH_PERIOD_MS

        /** Queues the chain in [PERIOD_MS] unless it is already queued or running. Not for the main thread. */
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request(PERIOD_MS))
        }

        /** Starts the chain afresh: the next step in [PERIOD_MS]. Not for the main thread. */
        fun restart(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(PERIOD_MS))
            waitingLong = false
        }

        private fun scheduleNext(context: Context, watchFound: Boolean) {
            val delay = nextDelayMs(watchFound)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delay))
            waitingLong = delay == NO_WATCH_PERIOD_MS
        }

        private fun request(delayMs: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<WatchTickWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
    }
}
