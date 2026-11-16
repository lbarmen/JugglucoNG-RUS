package tk.glucodata.rus.carelink

import android.content.ContentProvider
import android.content.ContentValues
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
 * Part 4a only proves the frame: one line in the system log, nothing is scheduled yet.
 */
class RusCareLinkStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Log.i(TAG, "process start")
                } catch (t: Throwable) {
                    // logging must never matter
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
    }
}
