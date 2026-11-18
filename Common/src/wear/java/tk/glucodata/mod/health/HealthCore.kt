package tk.glucodata.mod.health

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JugglucoNGmod, watch block (part 4): the one set of heart rate and steps objects of the watch process - the queue
 * ([buffer]), the memory between runs ([state]), the open heart rate windows and the counter of deliveries a
 * hand-over waits on. Built lazily on first use from any entry point ([get]): upstream's Application is theirs, so
 * nothing is set up at process start. Tests build their own with a folder and a clock.
 */
class HealthCore(
    val context: Context,
    dir: File,
    val clock: () -> Long = { System.currentTimeMillis() },
) {
    val state = HealthState(context)
    val buffer = SampleBuffer(dir)

    private val hrLock = Any()
    private val hr: HrAggregator = HrAggregator.decode(state.hrBuckets)

    private val eventLock = Object()
    private var events = 0L

    /** Open heart rate windows (for tests and the status). */
    val openHeartRateWindows: Int get() = synchronized(hrLock) { hr.openCount }

    /**
     * Adds heart rate readings and closes the windows ended [graceMs] before [nowMs]; the closed means go to the queue.
     * Returns how many means were queued.
     */
    fun addHeartRate(readings: List<HealthIntake.HeartRate>, nowMs: Long, graceMs: Long): Int {
        val closed = synchronized(hrLock) {
            readings.forEach { hr.add(it.timeMs, it.bpm, it.noContact) }
            val out = hr.close(nowMs, graceMs)
            state.hrBuckets = hr.encode()
            out
        }
        buffer.append(closed)
        return closed.size
    }

    /** Closes the heart rate windows ended [graceMs] before [nowMs] into the queue; returns how many. */
    fun closeHeartRate(nowMs: Long, graceMs: Long): Int = addHeartRate(emptyList(), nowMs, graceMs)

    /** The number of deliveries so far (a hand-over waits for it to change after a flush). */
    fun dataEvents(): Long = synchronized(eventLock) { events }

    fun signalData() {
        synchronized(eventLock) {
            events++
            eventLock.notifyAll()
        }
    }

    /** Waits up to [timeoutMs] for a delivery after the count [before]; `true` when one came. */
    fun awaitDataAfter(before: Long, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(eventLock) {
            while (events == before) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) return false
                try {
                    eventLock.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return events != before
                }
            }
            return true
        }
    }

    companion object {
        /** Our queue folder in the app's no-backup storage. */
        const val DIR = "mod_health"

        @Volatile
        private var shared: HealthCore? = null

        /** One background thread for deliveries and button sends (disk and Data Layer never on the main thread). */
        val executor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "ModHealth").apply { isDaemon = true } }
        }

        fun get(context: Context): HealthCore = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext ?: context
                HealthCore(app, File(app.noBackupFilesDir, DIR)).also { shared = it }
            }
        }
    }
}
