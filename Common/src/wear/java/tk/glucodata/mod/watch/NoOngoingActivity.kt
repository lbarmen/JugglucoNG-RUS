package tk.glucodata.mod.watch

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import tk.glucodata.OngoingNotification
import tk.glucodata.OngoingNotificationAccess

/**
 * JugglucoNGmod, watch block (part 7j): no ongoing activity for upstream's watch service - the owner's request of
 * 19.11.2026, after the white dot Wear OS draws at the bottom of every watch face for it stayed bright with our faint
 * copy of its icon (part 7c): "remove it completely", as the xDrip watch app has none.
 *
 * Upstream keeps the watch's ongoing activity in a public one-slot registry ([OngoingNotificationAccess], filled by
 * their `Specific.registerBridges` from `Applic.onCreate`) and asks it only when their foreground service starts
 * (`Notify.foregroundno` -> [attach]) and on each reading ([updateStatus]); their own contract says a caller with
 * nothing there keeps its own notification and "behaves exactly as it did before". So ours takes the slot: the service
 * notification goes as it is, without an ongoing activity, and the service runs as before; a tap on the value of our
 * face still opens their app.
 *
 * Timing, without a hook: our process-start provider ([tk.glucodata.mod.health.hs.ModHealthStarter]) runs before
 * `Applic.onCreate`, so it posts [claim] to the main thread - it runs after their registration and, on a normal start,
 * before their service is created. A service the system restarts by itself is created before that post and keeps their
 * ongoing activity until the next start of the process; [claim] again on each phone tick keeps the slot ours.
 */
object NoOngoingActivity : OngoingNotification {

    /** The notification as upstream gives it, ongoing, with no ongoing activity attached. */
    override fun attach(context: Context, notification: Notification, notificationId: Int): Notification =
        notification.also { it.flags = it.flags or Notification.FLAG_ONGOING_EVENT }

    /** No ongoing activity, no status to update. */
    override fun updateStatus(context: Context?, notificationId: Int) {}

    /** Puts ours in upstream's slot unless it is there; true when it replaced another. Never throws. */
    fun claim(): Boolean = try {
        if (OngoingNotificationAccess.get() === this) {
            false
        } else {
            val had = OngoingNotificationAccess.isRegistered()
            OngoingNotificationAccess.register(this)
            ModWatchLog.i("ongoing activity: none (${if (had) "replaced upstream's" else "slot was empty"})")
            true
        }
    } catch (t: Throwable) {
        ModWatchLog.w("ongoing activity not replaced: ${t.javaClass.simpleName}")
        false
    }

    /** [claim] on the main thread after the current message (from a provider: after `Application.onCreate`). */
    fun claimAfterStart() {
        try {
            Handler(Looper.getMainLooper()).post { claim() }
        } catch (t: Throwable) {
            ModWatchLog.w("ongoing activity claim not posted: ${t.javaClass.simpleName}")
        }
    }
}
