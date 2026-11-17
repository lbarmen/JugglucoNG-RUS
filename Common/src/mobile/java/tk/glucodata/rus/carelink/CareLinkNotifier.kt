package tk.glucodata.rus.carelink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import tk.glucodata.R

/**
 * JugglucoNG-RUS, CareLink (stage 4c, 4e): three notifications on channel `rus_carelink`.
 * - "Sign in to CareLink again": the refresh key was refused; once, until a sign-in.
 * - "No pump data for N min": the newest pump data is older than the warning threshold
 *   ([CareLinkSettings.warnAfterMin], 30 min by default) while the session lives; once, until the data comes back,
 *   then it goes away by itself.
 * - "Pump records are not saved": the journal or basal write has failed poll after poll for longer than the same
 *   threshold (critic 4d2, C3: since a failed write moves the data time, "no data" no longer covers it); goes away
 *   after a poll that wrote fine.
 * A tap opens the CareLink window (4e; in a build without it - the app). Nothing personal in the text.
 */
object CareLinkNotifier {

    const val CHANNEL = "rus_carelink"
    internal const val ID_NEED_LOGIN = 1_947_001
    internal const val ID_NO_DATA = 1_947_002
    internal const val ID_WRITE_FAIL = 1_947_003

    /** The default warning threshold (the person can choose 5-60 min in the CareLink window). */
    const val NO_DATA_MS = CareLinkSettings.DEFAULT_WARN_MIN * 60_000L

    /** The sign-in is gone: show "sign in again" once; "no data" and "not saved" are replaced by it. */
    fun needLogin(context: Context) {
        val app = context.applicationContext
        try {
            val store = CareLinkStateStore(app)
            cancel(app, ID_NO_DATA)
            store.noDataShown = false
            cancel(app, ID_WRITE_FAIL)
            store.writeFailShown = false
            store.writeFailSinceMs = null
            if (store.needLoginShown) return
            if (post(app, ID_NEED_LOGIN, app.getString(R.string.rus_carelink_need_login_title),
                    app.getString(R.string.rus_carelink_need_login_text))
            ) {
                store.needLoginShown = true
            }
        } catch (t: Throwable) {
            CareLinkLog.w("need-login notification failed (${t.javaClass.simpleName})")
        }
    }

    /** After a poll that reached the cloud or failed on the way: the sign-in works, check the data age and the writes. */
    fun afterPoll(context: Context, state: CareLinkState, nowMs: Long) {
        val app = context.applicationContext
        try {
            if (state.status == CareLinkStatus.OK || state.status == CareLinkStatus.ERROR) {
                val store = CareLinkStateStore(app)
                if (store.needLoginShown) {
                    cancel(app, ID_NEED_LOGIN)
                    store.needLoginShown = false
                }
            }
            checkWrites(app, state, nowMs)
            checkNoData(app, state, nowMs)
        } catch (t: Throwable) {
            CareLinkLog.w("notification check failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * "Pump records are not saved": a poll that wrote fine clears the mark and the notification; a write error starts
     * the clock (first failing poll) or, past the threshold, shows the notification once. Other errors (no network, the
     * cloud) leave the clock as it is - nothing was tried to be written.
     */
    internal fun checkWrites(context: Context, state: CareLinkState, nowMs: Long) {
        val app = context.applicationContext
        val store = CareLinkStateStore(app)
        when {
            state.status == CareLinkStatus.OK -> {
                if (store.writeFailSinceMs != null) store.writeFailSinceMs = null
                if (store.writeFailShown) {
                    cancel(app, ID_WRITE_FAIL)
                    store.writeFailShown = false
                }
            }
            state.status == CareLinkStatus.ERROR && state.lastError?.kind == CareLinkErrorKind.WRITE -> {
                val since = store.writeFailSinceMs ?: nowMs.also { store.writeFailSinceMs = it }
                val minutes = ((nowMs - since) / 60_000L).toInt()
                if (nowMs - since < CareLinkSettings(app).warnAfterMs || store.writeFailShown) return
                if (post(app, ID_WRITE_FAIL, app.getString(R.string.rus_carelink_write_fail_title),
                        app.getString(R.string.rus_carelink_write_fail_text, minutes))
                ) {
                    store.writeFailShown = true
                }
            }
        }
    }

    /**
     * "No pump data for N min": shown once when the newest pump data is older than the warning threshold while polling
     * is on and the session lives; taken away when data is fresh again or the link is off. Also called by the alarm
     * without a poll: with no network no poll runs, but the data still gets old. No data ever since the sign-in - no
     * notification (the CareLink window shows that). The threshold is read each time: a change in the window counts at
     * the next check.
     */
    fun checkNoData(context: Context, state: CareLinkState = CareLinkStateStore(context).read(),
                    nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        try {
            val store = CareLinkStateStore(app)
            val settings = CareLinkSettings(app)
            val live = settings.enabled && CareLinkAuth.hasSession(app) &&
                (state.status == CareLinkStatus.OK || state.status == CareLinkStatus.ERROR)
            val last = state.lastDataAtMs
            val stale = live && last != null && nowMs - last >= settings.warnAfterMs
            if (stale) {
                if (store.noDataShown) return
                val minutes = ((nowMs - last!!) / 60_000L).toInt()
                if (post(app, ID_NO_DATA, app.getString(R.string.rus_carelink_no_data_title, minutes),
                        app.getString(R.string.rus_carelink_no_data_text))
                ) {
                    store.noDataShown = true
                }
            } else if (store.noDataShown) {
                cancel(app, ID_NO_DATA)
                store.noDataShown = false
            }
        } catch (t: Throwable) {
            CareLinkLog.w("no-data check failed (${t.javaClass.simpleName})")
        }
    }

    /** A sign-in: "sign in again" goes away. */
    fun loggedIn(context: Context) {
        val app = context.applicationContext
        try {
            cancel(app, ID_NEED_LOGIN)
            CareLinkStateStore(app).needLoginShown = false
        } catch (t: Throwable) {
            CareLinkLog.w("notification not removed (${t.javaClass.simpleName})")
        }
    }

    /** Sign-out: every CareLink notification goes away. */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        try {
            cancel(app, ID_NEED_LOGIN)
            cancel(app, ID_NO_DATA)
            cancel(app, ID_WRITE_FAIL)
            CareLinkStateStore(app).apply {
                needLoginShown = false
                noDataShown = false
                writeFailShown = false
                writeFailSinceMs = null
            }
        } catch (t: Throwable) {
            CareLinkLog.w("notifications not removed (${t.javaClass.simpleName})")
        }
    }

    /** false - not shown (notifications off for the app, or refused). */
    private fun post(app: Context, id: Int, title: String, text: String): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) {
            CareLinkLog.i("notification not shown: notifications are off")
            return false
        }
        ensureChannel(app)
        val tap = tapIntent(app)?.let {
            PendingIntent.getActivity(app, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        return try {
            nm.notify(id, n)
            true
        } catch (e: SecurityException) {
            CareLinkLog.w("notification refused (${e.javaClass.simpleName})")
            false
        }
    }

    /** The CareLink window when this build has it (the `rus` manifest), else the app's main window. */
    internal fun tapIntent(app: Context): Intent? =
        CareLinkActivity.intentIfDeclared(app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: app.packageManager.getLaunchIntentForPackage(app.packageName)

    private fun cancel(app: Context, id: Int) {
        app.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /**
     * Creates channel [CHANNEL] if it is not there. Internal for the CareLink window (4e): its "sound and vibration"
     * button opens the channel's system screen, which needs the channel to exist first (critic of the joints, N4).
     */
    internal fun ensureChannel(context: Context) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.rus_carelink_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = app.getString(R.string.rus_carelink_channel_description) },
        )
    }
}
