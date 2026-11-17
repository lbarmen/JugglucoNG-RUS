package tk.glucodata.mod.watch

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JugglucoNGmod, watch block: our messages from the watch to the phone. The phone is every reachable node with the
 * phone app's capability ([ModWatchMessages.CAPABILITY_PHONE]); none - nothing is sent, silently.
 *
 * Off the main thread, on one short-lived worker: the button activity is already gone when it runs, and the process
 * may stop before it ends - a repeated press is harmless, so no retry queue.
 */
object WatchPhoneLink {

    private const val WAIT_SECONDS = 10L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ModWatchLink").apply { isDaemon = true } }

    /** Sends `/mod/snooze` with [pressedAtMs] to the phone; returns at once. */
    fun sendSnooze(context: Context, pressedAtMs: Long) {
        val app = context.applicationContext ?: context
        val data = ModWatchMessages.encodeSnooze(ModWatchMessages.SnoozeRequest(pressedAtMs))
        worker.execute { send(app, ModWatchMessages.PATH_SNOOZE, data) }
    }

    private fun send(context: Context, path: String, data: ByteArray) {
        try {
            val info = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(ModWatchMessages.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
                WAIT_SECONDS, TimeUnit.SECONDS,
            )
            val nodes = info.nodes
            if (nodes.isEmpty()) {
                ModWatchLog.i("send $path: no phone")
                return
            }
            val client = Wearable.getMessageClient(context)
            var sent = 0
            for (node in nodes) {
                try {
                    Tasks.await(client.sendMessage(node.id, path, data), WAIT_SECONDS, TimeUnit.SECONDS)
                    sent++
                } catch (t: Throwable) {
                    ModWatchLog.w("send $path: node failed: ${t.javaClass.simpleName}")
                }
            }
            ModWatchLog.i("send $path: $sent of ${nodes.size}")
        } catch (t: Throwable) {
            ModWatchLog.w("send $path failed: ${t.javaClass.simpleName}")
        }
    }
}
