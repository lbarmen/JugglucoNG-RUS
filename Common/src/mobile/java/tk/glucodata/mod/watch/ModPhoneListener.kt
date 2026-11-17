package tk.glucodata.mod.watch

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit
import tk.glucodata.MessageSender

/**
 * JugglucoNGmod, watch block: our Data Layer messages on the phone (filter `/mod/` in
 * Common/src/mobileMod/AndroidManifest.xml; upstream's MessageReceiver never sees these paths). Runs on a background
 * thread; upstream's alert calls go to the main thread ([OngoingAlert.snoozeOngoingOnMain]).
 *
 * `/mod/snooze` (the watch's button): the standard alert going on here is snoozed as upstream's "Snooze" does, and
 * `/mod/snooze/done` goes back to the same watch - "snoozed kind N" or "no alert". A repeated message is harmless:
 * upstream's action times drop a second acknowledgement. Ignored, like upstream's own receiver does, while the user
 * has the watch companion switched off on the phone. Paths of later parts are ignored until they are added here.
 */
class ModPhoneListener : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            if (!messageEvent.path.startsWith(ModWatchMessages.PATH_PREFIX)) return
            if (!MessageSender.outgoingAllowed()) {
                ModWatchLog.i("phone listener: watch companion off, message ignored")
                return
            }
            when (messageEvent.path) {
                ModWatchMessages.PATH_SNOOZE -> {
                    val reply = answerSnooze(messageEvent.data) { OngoingAlert.snoozeOngoingOnMain() } ?: return
                    Tasks.await(
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(messageEvent.sourceNodeId, ModWatchMessages.PATH_SNOOZE_DONE, reply),
                        SEND_WAIT_SECONDS, TimeUnit.SECONDS,
                    )
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("phone listener failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val SEND_WAIT_SECONDS = 10L

        /**
         * The `/mod/snooze/done` answer to a `/mod/snooze` message; `null` (nothing snoozed, no answer) when the
         * message is not valid. [snooze] snoozes the alert going on and returns its kind, `null` when there is none.
         */
        fun answerSnooze(
            data: ByteArray?,
            nowMs: Long = System.currentTimeMillis(),
            snooze: () -> Int?,
        ): ByteArray? {
            if (ModWatchMessages.decodeSnooze(data) == null) {
                ModWatchLog.w("phone listener: bad snooze message")
                return null
            }
            val kind = snooze()
            ModWatchLog.i("phone listener: " + if (kind != null) "snoozed kind $kind" else "no alert")
            return ModWatchMessages.encodeSnoozeDone(ModWatchMessages.SnoozeDone(kind, nowMs))
        }
    }
}
