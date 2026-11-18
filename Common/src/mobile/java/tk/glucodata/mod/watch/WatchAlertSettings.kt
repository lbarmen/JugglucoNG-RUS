package tk.glucodata.mod.watch

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.MessageSender

/**
 * JugglucoNGmod, watch block (part 7b): the phone's alert sound settings to our watch app ([ModAlertSettings],
 * `/mod/alerts`) - with every tick ([WatchTicker.send] hands over the watches it found), and [CHANGE_DELAY_MS] after
 * the last change of a sent key in upstream's alert preferences, so a slider drag goes once. The watch writes only
 * what differs, so the repeat with each tick costs it nothing. Nothing goes while the user has the watch companion
 * switched off (as upstream's own sender). The log has counts only.
 */
object WatchAlertSettings {

    /** After the last change of a sent setting, the settings go to the watch this much later. */
    const val CHANGE_DELAY_MS = 3_000L

    private const val WAIT_SECONDS = 10L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ModWatchAlerts").apply { isDaemon = true }
    }

    @Volatile
    private var pending: ScheduledFuture<*>? = null

    /** Kept here: SharedPreferences holds its listeners weakly. */
    @Volatile
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Sends the current settings to [nodeIds] with [client]; [label] names the occasion in the log. Blocks up to a few
     * seconds per watch (a worker thread). Returns how many watches got it; never throws.
     */
    fun sendTo(client: MessageClient, nodeIds: Collection<String>, label: String): Int {
        if (nodeIds.isEmpty()) return 0
        return try {
            val settings = ModAlertSettings.collect()
            if (settings.isEmpty()) {
                ModWatchLog.w("alerts $label: nothing to send")
                return 0
            }
            val data = ModAlertSettings.encode(settings)
            var sent = 0
            for (id in nodeIds) {
                try {
                    Tasks.await(client.sendMessage(id, ModWatchMessages.PATH_ALERTS, data), WAIT_SECONDS, TimeUnit.SECONDS)
                    sent++
                } catch (t: Throwable) {
                    ModWatchLog.w("alerts $label: node failed: ${t.javaClass.simpleName}")
                }
            }
            ModWatchLog.i("alerts $label: ${settings.size} kinds to $sent of ${nodeIds.size}")
            sent
        } catch (t: Throwable) {
            ModWatchLog.w("alerts $label failed: ${t.javaClass.simpleName}")
            0
        }
    }

    /** Looks up our reachable watches and sends them the settings; blocks (our thread). Never throws. */
    fun sendNow(context: Context, label: String) {
        try {
            if (!MessageSender.outgoingAllowed()) {
                ModWatchLog.i("alerts $label: watch companion off")
                return
            }
            val nodes = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(ModWatchMessages.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
                WAIT_SECONDS, TimeUnit.SECONDS,
            ).nodes
            if (nodes.isEmpty()) {
                ModWatchLog.i("alerts $label: no watch")
                return
            }
            sendTo(Wearable.getMessageClient(context), nodes.map { it.id }, label)
        } catch (t: Throwable) {
            ModWatchLog.w("alerts $label failed: ${t.javaClass.simpleName}")
        }
    }

    /** A sent setting changed: [sendNow] once, [CHANGE_DELAY_MS] after the last such call. Returns at once. */
    fun onChanged(context: Context) {
        val app = context.applicationContext ?: context
        try {
            synchronized(this) {
                pending?.cancel(false)
                pending = executor.schedule({ sendNow(app, "change") }, CHANGE_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alerts change not queued: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Watches upstream's alert preferences for the life of the process (from [ModWatchStarter]; the file is opened on
     * our thread, not the main one). Returns at once.
     */
    fun watchChanges(context: Context) {
        val app = context.applicationContext ?: context
        try {
            executor.execute {
                try {
                    if (listener != null) return@execute
                    val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (ModAlertSettings.isSentKey(key)) onChanged(app)
                    }
                    listener = l
                    app.getSharedPreferences(ModAlertSettings.ALERT_PREFS, Context.MODE_PRIVATE)
                        .registerOnSharedPreferenceChangeListener(l)
                } catch (t: Throwable) {
                    ModWatchLog.w("alerts listener not registered: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alerts listener not queued: ${t.javaClass.simpleName}")
        }
    }
}
