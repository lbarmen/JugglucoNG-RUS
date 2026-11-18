package tk.glucodata.mod.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tk.glucodata.MessageSender

/**
 * JugglucoNGmod, watch block (part 2): `/mod/tick` from the phone to our watch app - the phone battery for the watch
 * face and the wake-up after which the watch hands over its heart rate and steps (one alarm for both, watch plan,
 * decision 5). The watch has no alarm of its own: Android keeps an app that is never opened in rare standby buckets,
 * while a Data Layer message wakes it at once.
 *
 * Sent by the 5-minute cycle ([WatchTickWorker]), at the app's start ([ModWatchStarter]), when our watch app appears
 * ([ModPhoneListener]) and when the battery moved [BATTERY_STEP] points from the last tick sent (at most once a
 * [MIN_EXTRA_GAP_MS], while the process lives). The watch is every reachable node with the watch app's capability:
 * the Data Layer links only apps of the same package and signature, so a node found is our watch app. Nothing goes
 * while the user has the watch companion switched off on the phone (as upstream's own sender). The log has counts
 * and reason codes only, never the battery.
 */
object WatchTicker {

    /** A battery move this big (percentage points) from the last tick sent sends an extra tick. */
    const val BATTERY_STEP = 5

    /** Extra ticks (battery, watch appeared) - not within this of the last tick. */
    const val MIN_EXTRA_GAP_MS = 60_000L

    private const val WAIT_SECONDS = 10L

    /** Battery percent of the last tick that reached a watch; `null` before the first one in this process. */
    @Volatile
    private var lastSentPercent: Int? = null

    /** Time of the last tick attempt of any reason. */
    @Volatile
    private var lastTickAtMs = 0L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ModWatchTick").apply { isDaemon = true } }

    /**
     * Sends a tick with [reason] to our watch app; blocks up to a few seconds (a worker or listener thread, never the
     * main thread). Returns true when a watch was found, false when none is reachable, the companion is off or the
     * lookup failed. Never throws.
     */
    fun send(
        context: Context,
        reason: Int,
        nowMs: Long,
        battery: ModBattery.Reading? = ModBattery.read(context),
    ): Boolean {
        lastTickAtMs = nowMs
        try {
            if (!MessageSender.outgoingAllowed()) {
                ModWatchLog.i("tick $reason: watch companion off")
                return false
            }
            val nodes = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(ModWatchMessages.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
                WAIT_SECONDS, TimeUnit.SECONDS,
            ).nodes
            if (nodes.isEmpty()) {
                ModWatchLog.i("tick $reason: no watch")
                return false
            }
            val data = ModWatchMessages.encodeTick(
                ModWatchMessages.Tick(battery?.percent, battery?.charging == true, nowMs, reason)
            )
            val client = Wearable.getMessageClient(context)
            var sent = 0
            for (node in nodes) {
                try {
                    Tasks.await(client.sendMessage(node.id, ModWatchMessages.PATH_TICK, data), WAIT_SECONDS, TimeUnit.SECONDS)
                    sent++
                } catch (t: Throwable) {
                    ModWatchLog.w("tick $reason: node failed: ${t.javaClass.simpleName}")
                }
            }
            if (sent > 0) lastSentPercent = battery?.percent
            ModWatchLog.i("tick $reason: $sent of ${nodes.size}")
            return true
        } catch (t: Throwable) {
            ModWatchLog.w("tick $reason failed: ${t.javaClass.simpleName}")
            return false
        }
    }

    /** [send] on our own background thread; returns at once. */
    fun sendAsync(context: Context, reason: Int, battery: ModBattery.Reading? = null) {
        val app = context.applicationContext ?: context
        try {
            worker.execute {
                val now = System.currentTimeMillis()
                if (battery == null) send(app, reason, now) else send(app, reason, now, battery)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("tick $reason not queued: ${t.javaClass.simpleName}")
        }
    }

    /**
     * The app's process started (posted to the main thread by [ModWatchStarter], so after Application.onCreate): a
     * start tick on our thread, then the cycle - started afresh when a watch answered, kept as it is otherwise.
     */
    fun onAppStart(context: Context) {
        val app = context.applicationContext ?: context
        try {
            worker.execute {
                try {
                    val found = send(app, ModWatchMessages.REASON_START, System.currentTimeMillis())
                    if (found) WatchTickWorker.restart(app) else WatchTickWorker.ensureScheduled(app)
                } catch (t: Throwable) {
                    ModWatchLog.w("start tick failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("start tick not queued: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Our watch app became reachable (ModPhoneListener's CAPABILITY_CHANGED, a listener thread): an extra tick unless
     * one went within [MIN_EXTRA_GAP_MS], and the cycle back to every 5 minutes if it was waiting the long hour.
     */
    fun onWatchAppeared(context: Context, nowMs: Long) {
        if (gapPassed(nowMs, lastTickAtMs)) send(context, ModWatchMessages.REASON_WATCH, nowMs)
        if (WatchTickWorker.waitingLong != false) WatchTickWorker.restart(context)
    }

    /** A battery broadcast (main thread): an extra tick when [shouldSendForBattery] says so. */
    fun onBatteryChanged(context: Context, battery: ModBattery.Reading?, nowMs: Long) {
        if (battery == null) return
        if (!shouldSendForBattery(battery.percent, lastSentPercent, nowMs, lastTickAtMs)) return
        lastTickAtMs = nowMs // claimed now: broadcasts that come before the send runs queue no second tick
        sendAsync(context, ModWatchMessages.REASON_BATTERY, battery)
    }

    /**
     * An extra tick for the battery: it moved at least [BATTERY_STEP] points from [lastSentPercent] (the last tick
     * that reached a watch; none yet - no extra tick, the next cycle carries it) and no tick went within
     * [MIN_EXTRA_GAP_MS] of [nowMs].
     */
    fun shouldSendForBattery(percent: Int, lastSentPercent: Int?, nowMs: Long, lastTickAtMs: Long): Boolean {
        if (lastSentPercent == null) return false
        if (Math.abs(percent - lastSentPercent) < BATTERY_STEP) return false
        return gapPassed(nowMs, lastTickAtMs)
    }

    /** [MIN_EXTRA_GAP_MS] has passed since [lastMs] (a clock set back counts as passed). */
    fun gapPassed(nowMs: Long, lastMs: Long): Boolean = nowMs - lastMs !in 0 until MIN_EXTRA_GAP_MS

    /** Listens to the battery for the life of the process (ACTION_BATTERY_CHANGED is only sent to registered receivers). */
    fun registerBatteryReceiver(context: Context) {
        val app = context.applicationContext ?: context
        ContextCompat.registerReceiver(
            app, BatteryReceiver(), IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Tests only: a process that sent nothing yet. */
    internal fun resetForTest() {
        lastSentPercent = null
        lastTickAtMs = 0L
    }

    private class BatteryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            try {
                onBatteryChanged(context, ModBattery.fromIntent(intent), System.currentTimeMillis())
            } catch (t: Throwable) {
                ModWatchLog.w("battery receiver failed: ${t.javaClass.simpleName}")
            }
        }
    }
}
