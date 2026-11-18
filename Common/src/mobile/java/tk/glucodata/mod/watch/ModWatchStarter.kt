package tk.glucodata.mod.watch

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * JugglucoNGmod, watch block (part 2): starts our phone-side watch code without a hook in upstream files.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml, so it runs in the phone `mod` build alone. Android
 * creates content providers on every process start, before Application.onCreate, so onCreate here only registers the
 * battery receiver ([WatchTicker.registerBatteryReceiver], for the life of the process) and never throws. The start
 * tick and the cycle ([WatchTicker.onAppStart]) are posted to the main thread and run on our own thread from there:
 * upstream has no androidx.startup provider, WorkManager comes up lazily from their Applic.getWorkManagerConfiguration,
 * and touching it here would open its database on the main thread before their Application.onCreate.
 */
class ModWatchStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            val app = context?.applicationContext ?: return true
            try {
                WatchTicker.registerBatteryReceiver(app)
            } catch (t: Throwable) {
                ModWatchLog.w("battery receiver not registered: ${t.javaClass.simpleName}")
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    WatchTicker.onAppStart(app)
                } catch (t: Throwable) {
                    ModWatchLog.w("watch start failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (_: Throwable) {
            // our code must not take upstream's app down
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
