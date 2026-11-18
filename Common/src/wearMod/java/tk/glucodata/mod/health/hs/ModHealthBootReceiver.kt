package tk.glucodata.mod.health.hs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.mod.health.HealthCore
import tk.glucodata.mod.health.HealthSources
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4b): the watch restarted or our app was updated - a passive subscription may not
 * survive either (as Glucove's BootReceiver), and without a phone near no `/mod/tick` would bring it back. The
 * subscription is marked lost and made again at once on our background thread, while the broadcast is held open
 * ([goAsync]; the calls wait a few seconds at most). Never throws.
 */
class ModHealthBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        try {
            val app = context.applicationContext ?: context
            val pending = goAsync()
            HealthCore.executor.execute {
                try {
                    HealthCore.get(app).state.registeredTypes = ""
                    (HealthSources.current ?: ModHealthServicesSource).register(app, reason)
                } catch (t: Throwable) {
                    ModWatchLog.w("health restart register failed: ${t.javaClass.simpleName}")
                } finally {
                    pending.finish()
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health restart not handled: ${t.javaClass.simpleName}")
        }
    }
}
