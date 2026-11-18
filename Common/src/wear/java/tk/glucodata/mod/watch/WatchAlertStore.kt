package tk.glucodata.mod.watch

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.alerts.AlertType

/**
 * JugglucoNGmod, watch block (part 7d): `/mod/alerts` on the watch ([ModAlertSettings]) - order, memory and the write
 * again after upstream's toggle sync.
 *
 * Order: the phone stamps each message with its send time; one older than the last accepted by less than
 * [STALE_WINDOW_MS] was overtaken on the way (a tick that waited in its send while a change went out) and is dropped.
 * Older than that is a phone clock set back - accepted, as a 7b message without a time always is.
 *
 * Memory: the last accepted message is kept in our own preferences file ([PhoneBatteryStore.PREFS]). When upstream's
 * toggle sync turns an alert on or off on the watch, their AlertRepository.saveConfig writes every field of the
 * config it loaded - for a prefs-only alert without its "enabled" key the defaults, and at any time it may land just
 * after our write with older values. So a change of any `alert_<kind>_enabled` (only their code writes it, never ours)
 * writes the kept message again [REAPPLY_DELAY_MS] later, after their native part of the save; only what differs.
 * Registered for the life of the process with the first message.
 */
object WatchAlertStore {

    /** A message this much or less older than the last accepted one is dropped as overtaken. */
    const val STALE_WINDOW_MS = 10 * 60_000L

    /** After upstream's toggle sync wrote an alert, ours is written again this much later. */
    const val REAPPLY_DELAY_MS = 2_000L

    private const val KEY_SENT_AT = "alerts_sent_at"
    private const val KEY_MESSAGE = "alerts_message"

    private val lock = Any()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ModWatchAlerts").apply { isDaemon = true }
    }

    @Volatile
    private var pending: ScheduledFuture<*>? = null

    /** Kept here: SharedPreferences holds its listeners weakly. */
    @Volatile
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * [sentAtMs] is older than [lastAcceptedMs] by less than [STALE_WINDOW_MS]: the message was overtaken. An unknown
     * time on either side is never stale.
     */
    fun isStale(sentAtMs: Long?, lastAcceptedMs: Long?): Boolean =
        sentAtMs != null && lastAcceptedMs != null && sentAtMs < lastAcceptedMs &&
            lastAcceptedMs - sentAtMs < STALE_WINDOW_MS

    /**
     * One `/mod/alerts` message: written into upstream's alert preferences and native thresholds unless it is broken
     * or overtaken. Returns false only for a broken message. Never throws.
     */
    fun onMessage(
        context: Context,
        data: ByteArray?,
        thresholds: ModAlertSettings.ThresholdStore = ModAlertSettings.UpstreamThresholds,
    ): Boolean {
        val message = ModAlertSettings.decodeMessage(data)
        if (message == null || data == null) {
            ModWatchLog.w("alerts: bad message")
            return false
        }
        try {
            synchronized(lock) {
                val ours = PhoneBatteryStore.prefs(context)
                val last = if (ours.contains(KEY_SENT_AT)) ours.getLong(KEY_SENT_AT, 0L) else null
                if (isStale(message.sentAtMs, last)) {
                    ModWatchLog.i("alerts: an overtaken message dropped")
                    return true
                }
                val changed = ModAlertSettings.apply(alertPrefs(context), message.settings, thresholds)
                val editor = ours.edit().putString(KEY_MESSAGE, Base64.encodeToString(data, Base64.NO_WRAP))
                message.sentAtMs?.let { editor.putLong(KEY_SENT_AT, it) }
                editor.apply()
                if (changed > 0) ModWatchLog.i("alerts: $changed values from the phone (${message.settings.size} kinds)")
            }
            watchToggles(context)
        } catch (t: Throwable) {
            ModWatchLog.w("alerts: not applied: ${t.javaClass.simpleName}")
        }
        return true
    }

    /** Writes the kept message again (only what differs); returns how many values changed. Never throws. */
    fun reapply(
        context: Context,
        thresholds: ModAlertSettings.ThresholdStore = ModAlertSettings.UpstreamThresholds,
    ): Int = try {
        synchronized(lock) {
            val kept = PhoneBatteryStore.prefs(context).getString(KEY_MESSAGE, null)
            val settings = kept?.let { ModAlertSettings.decode(Base64.decode(it, Base64.NO_WRAP)) }
            if (settings == null) 0 else ModAlertSettings.apply(alertPrefs(context), settings, thresholds)
        }
    } catch (t: Throwable) {
        ModWatchLog.w("alerts: not written again: ${t.javaClass.simpleName}")
        0
    }

    /** [key] is an alert's on/off in upstream's alert preferences. */
    fun isToggleKey(key: String?): Boolean =
        key != null && AlertType.settingsEntries.any { key == "alert_${it.id}_enabled" }

    /** Registers, once per process, the write again after upstream's toggle sync. */
    private fun watchToggles(context: Context) {
        if (listener != null) return
        val app = context.applicationContext ?: context
        synchronized(lock) {
            if (listener != null) return
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (isToggleKey(key)) reapplyLater(app)
            }
            listener = l
            alertPrefs(app).registerOnSharedPreferenceChangeListener(l)
        }
    }

    /** Tests: forgets the listener and the pending write of an earlier test's Application. */
    internal fun resetForTests() {
        synchronized(this) { pending?.cancel(false) }
        listener = null
    }

    private fun reapplyLater(context: Context) {
        try {
            synchronized(this) {
                pending?.cancel(false)
                pending = executor.schedule({
                    val changed = reapply(context)
                    if (changed > 0) ModWatchLog.i("alerts: $changed values written again after a toggle")
                }, REAPPLY_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alerts: write again not queued: ${t.javaClass.simpleName}")
        }
    }

    private fun alertPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ModAlertSettings.ALERT_PREFS, Context.MODE_PRIVATE)
}
