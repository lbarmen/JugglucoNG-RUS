package tk.glucodata.mod.watch

import android.content.SharedPreferences
import tk.glucodata.Applic
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertSoundVolume
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.alerts.MAX_ALERT_DURATION_SECONDS
import tk.glucodata.alerts.MIN_ALERT_DURATION_SECONDS
import tk.glucodata.alerts.maxSoundDelaySecondsFor

/**
 * JugglucoNGmod, watch block (part 7b): how each alert sounds on the watch follows the phone's alert settings.
 *
 * The watch raises its own alerts with upstream's Notify.mksound, which reads its own `tk.glucodata.alerts` file at
 * every alarm, and upstream sends the watch only "enabled" (WearToggleSync); the watch has no screen for the rest. So
 * the alarm on the watch ran the 5 s default without a repeat whatever the phone said. Our phone sends [Settings] per
 * standard alert ([ModWatchMessages.PATH_ALERTS]) and the watch writes them under upstream's own keys ([apply]).
 *
 * Only what the watch acts on travels (checked in upstream's code, 18.11.2026): alarm duration (`_alarmDur`, mksound),
 * sound and vibration on/off (`_sound`, `_vibration`, mksound), vibration pattern (`_haptic`, getHapticProfile), sound
 * volume (`_soundVolumePercent`, getSoundVolumePercent), sound delay (`_soundDelayEnabled`, `_soundDelay`,
 * getSoundDelaySeconds), repeat (`_retryOn`, `_retryInt`, `_retryCnt`, Notify's retry session - in-process, so also
 * on the watch) and the default snooze (`_snooze`, the watch's AlarmActivity and AlarmActionReceiver). Not sent: the
 * own melody (a file on the phone), flash and "override Do not disturb" (the watch has no flash and always overrides),
 * "enabled" (upstream's toggle sync), thresholds and the other trigger settings.
 *
 * Body after the version byte: entry count (byte), then per entry its length (byte) and [ENTRY_BYTES] of fields: kind,
 * flags (bit 0 sound, 1 vibration, 2 sound delay, 3 repeat), duration s, pattern code ([HAPTIC_CODES], 0 = unknown),
 * volume % (bytes); sound delay s, repeat interval min, repeat count, snooze min (unsigned shorts), big-endian. A
 * longer entry keeps its known fields and skips the tail; a short or cut entry makes the whole message `null`. An
 * entry of a kind that is not a standard alert is skipped, a field out of its range is dropped (the watch keeps its own
 * value) - never an exception.
 */
object ModAlertSettings {

    /** The fields of one entry this version writes and reads. */
    const val ENTRY_BYTES = 5 + 4 * 2

    /** Repeat interval and count, default snooze: what the watch accepts (the phone offers far less). */
    const val MAX_RETRY_INTERVAL_MINUTES = 24 * 60
    const val MAX_RETRY_COUNT = 100
    const val MAX_SNOOZE_MINUTES = 24 * 60

    /** The phone's alert preferences file and the watch's (upstream's AlertRepository.PREFS_NAME, Notify.mksound). */
    const val ALERT_PREFS = "tk.glucodata.alerts"

    /** Vibration pattern codes on the wire: index + 1. Names, not enum order, so an upstream reorder keeps them. */
    private val HAPTIC_CODES = listOf("SOFT", "STEADY", "STRONG", "ESCALATING")

    private const val FLAG_SOUND = 1
    private const val FLAG_VIBRATION = 2
    private const val FLAG_SOUND_DELAY = 4
    private const val FLAG_RETRY = 8

    /** One alert's sound settings; a `null` field is not known (not sent or out of range) and left as it is. */
    data class Settings(
        val kind: Int,
        val soundEnabled: Boolean,
        val vibrationEnabled: Boolean,
        val soundDelayEnabled: Boolean,
        val retryEnabled: Boolean,
        val alarmDurationSeconds: Int?,
        val hapticProfile: HapticProfile?,
        val soundVolumePercent: Int?,
        val soundDelaySeconds: Int?,
        val retryIntervalMinutes: Int?,
        val retryCount: Int?,
        val defaultSnoozeMinutes: Int?,
    )

    /** The part of [config] that travels. */
    fun fromConfig(config: AlertConfig): Settings = Settings(
        kind = config.type.id,
        soundEnabled = config.soundEnabled,
        vibrationEnabled = config.vibrationEnabled,
        soundDelayEnabled = config.soundDelayEnabled,
        retryEnabled = config.retryEnabled,
        alarmDurationSeconds = config.alarmDurationSeconds,
        hapticProfile = config.hapticProfile,
        soundVolumePercent = config.soundVolumePercent,
        soundDelaySeconds = config.soundDelaySeconds,
        retryIntervalMinutes = config.retryIntervalMinutes,
        retryCount = config.retryCount,
        defaultSnoozeMinutes = config.defaultSnoozeMinutes,
    )

    /** Phone: the settings of every standard alert, as upstream's AlertRepository holds them; one that fails is left out. */
    fun collect(): List<Settings> = AlertType.settingsEntries.mapNotNull { type ->
        try {
            fromConfig(AlertRepository.loadConfig(type))
        } catch (t: Throwable) {
            ModWatchLog.w("alert settings: kind ${type.id} not read: ${t.javaClass.simpleName}")
            null
        }
    }

    fun encode(list: List<Settings>): ByteArray {
        val entries = list.take(0xff)
        val buffer = ModWatchMessages.allocate(1 + entries.size * (1 + ENTRY_BYTES)).put(entries.size.toByte())
        for (s in entries) {
            var flags = 0
            if (s.soundEnabled) flags = flags or FLAG_SOUND
            if (s.vibrationEnabled) flags = flags or FLAG_VIBRATION
            if (s.soundDelayEnabled) flags = flags or FLAG_SOUND_DELAY
            if (s.retryEnabled) flags = flags or FLAG_RETRY
            buffer.put(ENTRY_BYTES.toByte())
                .put(u8(s.kind))
                .put(flags.toByte())
                .put(u8(s.alarmDurationSeconds))
                .put(((s.hapticProfile?.let { HAPTIC_CODES.indexOf(it.name) } ?: -1) + 1).toByte())
                .put(u8(s.soundVolumePercent))
                .putShort(u16(s.soundDelaySeconds))
                // "constantly" is an interval of 0 or below on the phone; 0 keeps its meaning
                .putShort(u16(s.retryIntervalMinutes?.coerceAtLeast(0)))
                .putShort(u16(s.retryCount))
                .putShort(u16(s.defaultSnoozeMinutes))
        }
        return buffer.array()
    }

    /** The entries of [data]; `null` when the message is empty, cut, of an unknown version or not ours. */
    fun decode(data: ByteArray?): List<Settings>? {
        val body = ModWatchMessages.open(data, 1) ?: return null
        val count = body.get().toInt() and 0xff
        val result = ArrayList<Settings>(count)
        repeat(count) {
            if (body.remaining() < 1) return null
            val length = body.get().toInt() and 0xff
            if (length < ENTRY_BYTES || body.remaining() < length) return null
            val end = body.position() + length
            val kind = body.get().toInt() and 0xff
            val flags = body.get().toInt() and 0xff
            val duration = body.get().toInt() and 0xff
            val haptic = body.get().toInt() and 0xff
            val volume = body.get().toInt() and 0xff
            val delay = body.short.toInt() and 0xffff
            val interval = body.short.toInt() and 0xffff
            val retries = body.short.toInt() and 0xffff
            val snooze = body.short.toInt() and 0xffff
            body.position(end)
            val type = AlertType.settingsEntries.firstOrNull { it.id == kind } ?: return@repeat
            result += Settings(
                kind = kind,
                soundEnabled = flags and FLAG_SOUND != 0,
                vibrationEnabled = flags and FLAG_VIBRATION != 0,
                soundDelayEnabled = flags and FLAG_SOUND_DELAY != 0,
                retryEnabled = flags and FLAG_RETRY != 0,
                alarmDurationSeconds = duration.takeIf { it in MIN_ALERT_DURATION_SECONDS..MAX_ALERT_DURATION_SECONDS },
                hapticProfile = HAPTIC_CODES.getOrNull(haptic - 1)
                    ?.let { name -> HapticProfile.entries.firstOrNull { it.name == name } },
                soundVolumePercent = volume.takeIf { it in AlertSoundVolume.MIN_PERCENT..AlertSoundVolume.MAX_PERCENT },
                soundDelaySeconds = delay.takeIf { it in 0..maxSoundDelaySecondsFor(type) },
                retryIntervalMinutes = interval.takeIf { it in 0..MAX_RETRY_INTERVAL_MINUTES },
                retryCount = retries.takeIf { it in 0..MAX_RETRY_COUNT },
                defaultSnoozeMinutes = snooze.takeIf { it in 1..MAX_SNOOZE_MINUTES },
            )
        }
        return result
    }

    /** Upstream's preference keys this message writes, by kind (AlertRepository's key functions, Notify.mksound). */
    fun keys(kind: Int): List<String> = SUFFIXES.map { "alert_${kind}_$it" } + AlertSoundVolume.preferenceKey(kind)

    /** Alerts whose on/off and threshold AlertRepository.loadConfig takes from the native side, the rest from the file. */
    private val NATIVE_BACKED = setOf(
        AlertType.LOW, AlertType.HIGH, AlertType.VERY_LOW, AlertType.VERY_HIGH,
        AlertType.PRE_LOW, AlertType.PRE_HIGH, AlertType.LOSS,
    )

    private val SUFFIXES = listOf(
        "alarmDur", "sound", "vibration", "haptic", "soundDelayEnabled", "soundDelay",
        "retryOn", "retryInt", "retryCnt", "snooze",
    )

    /** Phone: [key] of the alert preferences is one this message carries (a change there is sent at once). */
    fun isSentKey(key: String?): Boolean {
        if (key == null) return false
        return AlertType.settingsEntries.any { type -> key in keys(type.id) }
    }

    /**
     * Watch: writes [list] into [prefs] (upstream's alert preferences) under upstream's keys - only the values that
     * differ, so a repeat of the same settings writes nothing. Returns how many values changed.
     */
    fun apply(prefs: SharedPreferences, list: List<Settings>): Int {
        val editor = prefs.edit()
        var changed = 0
        fun putInt(key: String, value: Int?) {
            if (value == null) return
            if (prefs.contains(key) && runCatching { prefs.getInt(key, 0) }.getOrNull() == value) return
            editor.putInt(key, value)
            changed++
        }
        fun putBoolean(key: String, value: Boolean) {
            if (prefs.contains(key) && runCatching { prefs.getBoolean(key, false) }.getOrNull() == value) return
            editor.putBoolean(key, value)
            changed++
        }
        fun putString(key: String, value: String?) {
            if (value == null) return
            if (prefs.contains(key) && runCatching { prefs.getString(key, null) }.getOrNull() == value) return
            editor.putString(key, value)
            changed++
        }
        for (s in list) {
            val k = "alert_${s.kind}_"
            // AlertRepository reads a prefs-only alert from the file only once its "enabled" key exists (defaults
            // before that), and Notify's repeat and the snooze go through it: an absent key gets the value the watch
            // already acts on, the default - on/off itself stays upstream's toggle sync.
            val type = AlertType.fromId(s.kind)
            if (type != null && type !in NATIVE_BACKED && !prefs.contains(k + "enabled")) {
                editor.putBoolean(k + "enabled", AlertDefaults.defaultConfig(type, Applic.unit == 1).enabled)
                changed++
            }
            putInt(k + "alarmDur", s.alarmDurationSeconds)
            putBoolean(k + "sound", s.soundEnabled)
            putBoolean(k + "vibration", s.vibrationEnabled)
            putString(k + "haptic", s.hapticProfile?.name)
            putInt(AlertSoundVolume.preferenceKey(s.kind), s.soundVolumePercent)
            putBoolean(k + "soundDelayEnabled", s.soundDelayEnabled)
            putInt(k + "soundDelay", s.soundDelaySeconds)
            putBoolean(k + "retryOn", s.retryEnabled)
            putInt(k + "retryInt", s.retryIntervalMinutes)
            putInt(k + "retryCnt", s.retryCount)
            putInt(k + "snooze", s.defaultSnoozeMinutes)
        }
        if (changed > 0) editor.apply()
        return changed
    }

    /** `null` goes as 0, which neither byte field (duration, volume) accepts. */
    private fun u8(value: Int?): Byte = (value ?: 0).coerceIn(0, 0xff).toByte()

    /** `null` and anything too big go as 0xffff, which no short field accepts: the watch keeps its own value. */
    private fun u16(value: Int?): Short = (value ?: 0xffff).coerceIn(0, 0xffff).toShort()
}
