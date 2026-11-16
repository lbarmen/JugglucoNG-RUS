package tk.glucodata.rus.carelink

import android.content.Context
import android.content.SharedPreferences

/** CareLink link: off (not signed in or turned off), working, sign-in needed, the last poll failed. */
enum class CareLinkStatus { OFF, OK, NEED_LOGIN, ERROR }

/**
 * Why the last poll failed (the CareLink window turns it into text, 4e): no network, the cloud refused or failed
 * (HTTP status), an answer that is not pump data, the session file did not open, parsing or the journal write failed.
 */
enum class CareLinkErrorKind { NO_NETWORK, SERVER, BAD_ANSWER, STORAGE, PARSE, WRITE }

/** A poll failure: kind, HTTP status (0 - none) and the exception class or a short reason - never a body or a key. */
data class CareLinkError(val kind: CareLinkErrorKind, val code: Int = 0, val errorClass: String? = null)

/**
 * JugglucoNG-RUS, CareLink (stage 4c): the state of the link for the window, the notifications and the schedule; no
 * keys, no user name. A cut-down Glucove CareLinkState (no basal watch, no change questions, no modes).
 * [lastPollAtMs] - the last poll attempt (any result), [lastDataAtMs] - the newest pump data time seen
 * (`lastMedicalDeviceDataUpdateServerTime`; moves only forward and only when the answer was parsed and written),
 * [lastError] - why the last poll failed (null after a good one), [pumpNow] - what the pump showed in the last
 * non-empty answer.
 */
data class CareLinkState(
    val status: CareLinkStatus = CareLinkStatus.OFF,
    val lastPollAtMs: Long? = null,
    val lastDataAtMs: Long? = null,
    val lastError: CareLinkError? = null,
    val pumpNow: PumpNow? = null,
)

/**
 * Where [CareLinkState] lives: SharedPreferences `rus_carelink_state` (upstream's settings backup takes every
 * shared_prefs file - nothing secret here). Also the two "shown once" marks of [CareLinkNotifier].
 */
class CareLinkStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): CareLinkState = CareLinkState(
        status = prefs.getString(K_STATUS, null)?.let { s -> CareLinkStatus.values().firstOrNull { it.name == s } }
            ?: CareLinkStatus.OFF,
        lastPollAtMs = long(K_POLL),
        lastDataAtMs = long(K_DATA),
        lastError = prefs.getString(K_ERROR_KIND, null)
            ?.let { s -> CareLinkErrorKind.values().firstOrNull { it.name == s } }
            ?.let { CareLinkError(it, prefs.getInt(K_ERROR_CODE, 0), prefs.getString(K_ERROR_CLASS, null)) },
        pumpNow = prefs.getString(K_PUMP, null)?.let { PumpNow.fromJson(CareLinkConfig.parse(it)) },
    )

    fun write(s: CareLinkState) {
        prefs.edit().apply {
            putString(K_STATUS, s.status.name)
            putOrRemove(K_POLL, s.lastPollAtMs, this)
            putOrRemove(K_DATA, s.lastDataAtMs, this)
            if (s.lastError == null) {
                remove(K_ERROR_KIND).remove(K_ERROR_CODE).remove(K_ERROR_CLASS)
            } else {
                putString(K_ERROR_KIND, s.lastError.kind.name)
                putInt(K_ERROR_CODE, s.lastError.code)
                putString(K_ERROR_CLASS, s.lastError.errorClass)
            }
            if (s.pumpNow == null) remove(K_PUMP) else putString(K_PUMP, s.pumpNow.toJson().toString())
        }.commit()
    }

    fun update(change: (CareLinkState) -> CareLinkState): CareLinkState = synchronized(LOCK) {
        change(read()).also(::write)
    }

    /** "Sign in again" was shown and not answered by a sign-in yet. */
    var needLoginShown: Boolean
        get() = prefs.getBoolean(K_NEED_LOGIN_SHOWN, false)
        set(v) {
            prefs.edit().putBoolean(K_NEED_LOGIN_SHOWN, v).apply()
        }

    /** "No pump data for N min" was shown and the data has not come back yet. */
    var noDataShown: Boolean
        get() = prefs.getBoolean(K_NO_DATA_SHOWN, false)
        set(v) {
            prefs.edit().putBoolean(K_NO_DATA_SHOWN, v).apply()
        }

    /** Back to nothing (sign-out; may run on the main thread - written in the background). */
    fun clear() {
        synchronized(LOCK) { prefs.edit().clear().apply() }
    }

    private fun long(k: String): Long? = if (prefs.contains(k)) prefs.getLong(k, 0L) else null

    private fun putOrRemove(k: String, v: Long?, e: SharedPreferences.Editor) {
        if (v == null) e.remove(k) else e.putLong(k, v)
    }

    companion object {
        const val PREFS = "rus_carelink_state"
        private val LOCK = Any()
        private const val K_STATUS = "status"
        private const val K_POLL = "lastPollAtMs"
        private const val K_DATA = "lastDataAtMs"
        private const val K_ERROR_KIND = "lastErrorKind"
        private const val K_ERROR_CODE = "lastErrorCode"
        private const val K_ERROR_CLASS = "lastErrorClass"
        private const val K_PUMP = "pumpNow"
        private const val K_NEED_LOGIN_SHOWN = "needLoginShown"
        private const val K_NO_DATA_SHOWN = "noDataShown"
    }
}

/**
 * JugglucoNG-RUS, CareLink (stage 4c): settings of the CareLink window (SharedPreferences `rus_carelink_settings`, no
 * secrets). [enabled] - polling on (a sign-in turns it on, a sign-out off); [intervalMin] - 5/10/15 min, default 5;
 * [presetId] - insulin type of pump boluses, null - upstream's default for calculation (4d); [writeCarbs] - record
 * calculator carbs (on); [sendToNightscout] - pump records go to Nightscout (off; plan, decision 8).
 */
class CareLinkSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, false)
        set(v) {
            prefs.edit().putBoolean(K_ENABLED, v).apply()
        }

    var intervalMin: Int
        get() = prefs.getInt(K_INTERVAL, DEFAULT_INTERVAL_MIN).takeIf { it in INTERVALS_MIN } ?: DEFAULT_INTERVAL_MIN
        set(v) {
            prefs.edit().putInt(K_INTERVAL, v.takeIf { it in INTERVALS_MIN } ?: DEFAULT_INTERVAL_MIN).apply()
        }

    var presetId: Long?
        get() = if (prefs.contains(K_PRESET)) prefs.getLong(K_PRESET, 0L) else null
        set(v) {
            prefs.edit().apply { if (v == null) remove(K_PRESET) else putLong(K_PRESET, v) }.apply()
        }

    var writeCarbs: Boolean
        get() = prefs.getBoolean(K_CARBS, true)
        set(v) {
            prefs.edit().putBoolean(K_CARBS, v).apply()
        }

    var sendToNightscout: Boolean
        get() = prefs.getBoolean(K_NIGHTSCOUT, false)
        set(v) {
            prefs.edit().putBoolean(K_NIGHTSCOUT, v).apply()
        }

    companion object {
        const val PREFS = "rus_carelink_settings"
        const val DEFAULT_INTERVAL_MIN = 5
        val INTERVALS_MIN = listOf(5, 10, 15)
        private const val K_ENABLED = "enabled"
        private const val K_INTERVAL = "intervalMin"
        private const val K_PRESET = "presetId"
        private const val K_CARBS = "writeCarbs"
        private const val K_NIGHTSCOUT = "sendToNightscout"
    }
}
