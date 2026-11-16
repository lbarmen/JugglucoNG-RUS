package tk.glucodata.rus.carelink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * JugglucoNG-RUS, CareLink (stage 4): starts our CareLink code without a hook in upstream files.
 *
 * Declared only in Common/src/mobileRus/AndroidManifest.xml, so it runs in the phone `rus` build alone; upstream's
 * own builds compile this class but never start it, and the watch never sees it. Android creates content providers
 * on every process start, before Application.onCreate: onCreate here stays cheap, touches none of upstream's
 * singletons and never throws (a crash here would take the whole app down before any screen).
 *
 * Stage 4c: sets the sign-in and sign-out listeners of [CareLinkAuth] and posts [CareLinkScheduler.arm] to the main
 * thread (it runs after Application.onCreate, when WorkManager has upstream's configuration). No Keystore, no network
 * here; without a CareLink sign-in nothing is scheduled.
 */
class RusCareLinkStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            CareLinkAuth.onLoginSuccess = { ctx -> afterLogin(ctx) }
            CareLinkAuth.onSignOut = { ctx -> afterSignOut(ctx) }
            val app = context?.applicationContext ?: return true
            Handler(Looper.getMainLooper()).post {
                try {
                    CareLinkScheduler.arm(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "arm on start failed (${t.javaClass.simpleName})")
                }
            }
        } catch (t: Throwable) {
            // our code must not take upstream's app down
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val TAG = "RusCareLink"

        /**
         * After a sign-in (a background thread of [CareLinkAuth]): polling on, "sign in again" away, the chain armed
         * and the first poll - it takes the whole 24 h of the cloud window. The first poll stays pending until one is
         * written (critic 4c, C1). The last snapshot and data time go: they may be of another account, and an old data
         * time would show "no data for 2000 min" at once (critic 4c, N4).
         */
        internal fun afterLogin(context: Context) {
            val app = context.applicationContext
            CareLinkSettings(app).enabled = true
            val store = CareLinkStateStore(app)
            store.firstPollPending = true
            store.update { it.copy(status = CareLinkStatus.OK, lastError = null, lastDataAtMs = null, pumpNow = null) }
            CareLinkNotifier.loggedIn(app)
            CareLinkScheduler.arm(app, force = true)
            CareLinkScheduler.enqueuePoll(app, PollReason.LOGIN, firstPoll = true)
        }

        /** After a sign-out (may be the main thread; file and preference work only): polling off, state cleared. */
        internal fun afterSignOut(context: Context) {
            val app = context.applicationContext
            CareLinkSettings(app).enabled = false
            CareLinkScheduler.disarm(app)
            CareLinkStateStore(app).clear()
            CareLinkNotifier.cancelAll(app)
        }
    }
}
