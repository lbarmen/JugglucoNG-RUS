package tk.glucodata.rus.carelink

import android.util.Log

/**
 * JugglucoNG-RUS, CareLink: our log lines (tag `RusCareLink`). Through `Log.println`, not `Log.i`/`Log.w`: upstream's R8
 * rules (Common/proguard-rules.log) strip `Log.v/d/i/w` from release builds, so none of our lines ("poll ok", errors by
 * code) reached the device (found by the emulator trial, part 4p). Never anything personal: codes, counts and
 * exception classes only - no keys, user name, answer bodies or addresses. Not upstream's `tk.glucodata.Log`: that one
 * is exported with their logs.
 */
internal object CareLinkLog {

    const val TAG = "RusCareLink"

    fun i(message: String) {
        Log.println(Log.INFO, TAG, message)
    }

    fun w(message: String) {
        Log.println(Log.WARN, TAG, message)
    }
}
