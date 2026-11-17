package tk.glucodata.mod.watch

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * JugglucoNGmod, watch block: our Data Layer messages on the watch (filter `/mod/` in
 * Common/src/wearMod/AndroidManifest.xml; upstream's MessageReceiver never sees these paths). Runs on a background
 * thread. A path we do not know yet is ignored: later parts add theirs here (`/mod/tick` - part 2).
 */
class ModWearListener : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            handle(applicationContext, messageEvent.path, messageEvent.data)
        } catch (t: Throwable) {
            ModWatchLog.w("watch listener failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        /** One message; returns true when the path was ours and its message valid. */
        fun handle(context: Context, path: String?, data: ByteArray?): Boolean {
            when (path) {
                ModWatchMessages.PATH_SNOOZE_DONE -> {
                    val done = ModWatchMessages.decodeSnoozeDone(data)
                    if (done == null) {
                        ModWatchLog.w("watch listener: bad $path message")
                        return false
                    }
                    SnoozeButton.onPhoneAnswer(context, done)
                    return true
                }
                else -> return false
            }
        }
    }
}
