package mod.watch

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.mod.watch.ModAlertSettings
import tk.glucodata.mod.watch.ModAlertSettings.Settings
import tk.glucodata.mod.watch.ModWatchMessages

/**
 * JugglucoNGmod, watch block (part 7b): `/mod/alerts` - the byte format, what a broken or newer message does, what the
 * phone collects and that what the watch writes is what upstream's AlertRepository reads back (their key names: a red
 * test after merging an upstream release means a key moved - ModAlertSettings needs a look). Runs in both test tasks;
 * the native side is a sink. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ModAlertSettingsTest.TestApplication::class, shadows = [ModAlertSettingsTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModAlertSettingsTest {

    class TestApplication : Applic() {
        override fun onCreate() {}
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        Log.doLog = false
        // the very file AlertRepository reads (its lazy handle outlives one test's Application)
        prefs = AlertRepository::class.java.getDeclaredMethod("getPrefs").apply { isAccessible = true }
            .invoke(AlertRepository) as SharedPreferences
        prefs.edit().clear().commit()
    }

    private fun sample(kind: Int) = Settings(
        kind = kind,
        soundEnabled = false,
        vibrationEnabled = true,
        soundDelayEnabled = true,
        retryEnabled = true,
        alarmDurationSeconds = 42,
        hapticProfile = HapticProfile.ESCALATING,
        soundVolumePercent = 95,
        soundDelaySeconds = 20,
        retryIntervalMinutes = 2,
        retryCount = 0,
        defaultSnoozeMinutes = 25,
    )

    /** One raw entry of this version, [extra] zero bytes after the known fields. */
    private fun entry(
        kind: Int, flags: Int = 0, duration: Int = 30, haptic: Int = 3, volume: Int = 80,
        delay: Int = 0, interval: Int = 5, count: Int = 3, snooze: Int = 15, extra: Int = 0,
        length: Int = ModAlertSettings.ENTRY_BYTES + extra,
    ): ByteArray = ByteBuffer.allocate(1 + ModAlertSettings.ENTRY_BYTES + extra)
        .put(length.toByte()).put(kind.toByte()).put(flags.toByte()).put(duration.toByte()).put(haptic.toByte())
        .put(volume.toByte()).putShort(delay.toShort()).putShort(interval.toShort()).putShort(count.toShort())
        .putShort(snooze.toShort())
        .array()

    private fun message(vararg entries: ByteArray, version: Int = ModWatchMessages.VERSION, count: Int = entries.size) =
        byteArrayOf(version.toByte(), count.toByte()) + entries.fold(ByteArray(0)) { all, e -> all + e }

    @Test
    fun everyStandardKindRoundTrips() {
        val list = AlertType.settingsEntries.map { sample(it.id) }
        val bytes = ModAlertSettings.encode(list)
        assertEquals(1 + 1 + list.size * (1 + ModAlertSettings.ENTRY_BYTES), bytes.size)
        assertEquals(ModWatchMessages.VERSION, bytes[0].toInt())
        assertEquals(list, ModAlertSettings.decode(bytes))
        // every pattern and both ends of the ranges
        for (profile in HapticProfile.entries) {
            val one = listOf(sample(AlertType.HIGH.id).copy(hapticProfile = profile, alarmDurationSeconds = 60,
                soundVolumePercent = 30, soundDelaySeconds = 300, retryIntervalMinutes = 0, retryCount = 100,
                defaultSnoozeMinutes = 24 * 60, soundEnabled = true, retryEnabled = false))
            assertEquals(profile.name, one, ModAlertSettings.decode(ModAlertSettings.encode(one)))
        }
        assertEquals(emptyList<Settings>(), ModAlertSettings.decode(ModAlertSettings.encode(emptyList())))
    }

    @Test
    fun aConfigTravelsAsThePhoneHoldsIt() {
        val config = AlertDefaults.defaultConfig(AlertType.LOW, false).copy(
            alarmDurationSeconds = 45, soundEnabled = true, vibrationEnabled = false, hapticProfile = HapticProfile.SOFT,
            soundVolumePercent = 90, soundDelayEnabled = true, soundDelaySeconds = 10, retryEnabled = true,
            retryIntervalMinutes = 0, retryCount = 0, defaultSnoozeMinutes = 20,
            customSoundUri = "content://made-up/melody", flashEnabled = true, overrideDND = false,
        )
        val expected = Settings(AlertType.LOW.id, true, false, true, true, 45, HapticProfile.SOFT, 90, 10, 0, 0, 20)
        assertEquals(expected, ModAlertSettings.fromConfig(config))
        assertEquals(listOf(expected), ModAlertSettings.decode(ModAlertSettings.encode(listOf(expected))))
    }

    @Test
    fun thePhoneCollectsWhatUpstreamSaved() {
        val high = AlertRepository.loadConfig(AlertType.HIGH).copy(alarmDurationSeconds = 37, retryEnabled = true,
            retryIntervalMinutes = 10, retryCount = 5, defaultSnoozeMinutes = 45, soundVolumePercent = 60)
        AlertRepository.saveConfig(high)
        val collected = ModAlertSettings.collect()
        assertEquals(AlertType.settingsEntries.map { it.id }, collected.map { it.kind })
        val got = collected.single { it.kind == AlertType.HIGH.id }
        assertEquals(37, got.alarmDurationSeconds)
        assertTrue(got.retryEnabled)
        assertEquals(10, got.retryIntervalMinutes)
        assertEquals(5, got.retryCount)
        assertEquals(45, got.defaultSnoozeMinutes)
        assertEquals(60, got.soundVolumePercent)
        // untouched kinds go with upstream's defaults: 5 s, no repeat
        val missed = collected.single { it.kind == AlertType.MISSED_READING.id }
        assertEquals(5, missed.alarmDurationSeconds)
        assertFalse(missed.retryEnabled)
    }

    @Test
    fun whatTheWatchWritesIsWhatUpstreamReads() {
        for (type in AlertType.settingsEntries) {
            prefs.edit().clear().commit()
            val enabledBefore = AlertRepository.loadConfig(type).enabled
            val s = sample(type.id)
            assertTrue(type.name, ModAlertSettings.apply(prefs, listOf(s)) > 0)
            val c = AlertRepository.loadConfig(type)
            assertEquals(type.name, s.alarmDurationSeconds, c.alarmDurationSeconds)
            assertEquals(type.name, s.soundEnabled, c.soundEnabled)
            assertEquals(type.name, s.vibrationEnabled, c.vibrationEnabled)
            assertEquals(type.name, s.hapticProfile, c.hapticProfile)
            assertEquals(type.name, s.soundVolumePercent, c.soundVolumePercent)
            assertEquals(type.name, s.soundDelayEnabled, c.soundDelayEnabled)
            assertEquals(type.name, s.soundDelaySeconds, c.soundDelaySeconds)
            assertEquals(type.name, s.retryEnabled, c.retryEnabled)
            assertEquals(type.name, s.retryIntervalMinutes, c.retryIntervalMinutes)
            assertEquals(type.name, s.retryCount, c.retryCount)
            assertEquals(type.name, s.defaultSnoozeMinutes, c.defaultSnoozeMinutes)
            // on/off is not ours
            assertEquals(type.name, enabledBefore, c.enabled)
            // Notify.mksound reads the duration by this literal key
            assertEquals(type.name, 42, prefs.getInt("alert_${type.id}_alarmDur", 5))
        }
    }

    @Test
    fun theSameSettingsTwiceWriteNothingAndANullFieldKeepsTheWatchValue() {
        val s = sample(AlertType.HIGH.id)
        assertTrue(ModAlertSettings.apply(prefs, listOf(s)) > 0)
        assertEquals(0, ModAlertSettings.apply(prefs, listOf(s)))
        assertEquals(1, ModAlertSettings.apply(prefs, listOf(s.copy(alarmDurationSeconds = 12))))
        assertEquals(1, ModAlertSettings.apply(prefs, listOf(s.copy(alarmDurationSeconds = null, retryEnabled = false))))
        assertEquals(12, prefs.getInt("alert_${AlertType.HIGH.id}_alarmDur", 0))
        assertFalse(AlertRepository.loadConfig(AlertType.HIGH).retryEnabled)
    }

    @Test
    fun brokenOrNewerMessagesAreNull() {
        val good = entry(AlertType.LOW.id)
        assertEquals(1, ModAlertSettings.decode(message(good))?.size)
        assertNull(ModAlertSettings.decode(null))
        assertNull(ModAlertSettings.decode(ByteArray(0)))
        assertNull(ModAlertSettings.decode(byteArrayOf(ModWatchMessages.VERSION.toByte()))) // no count
        assertNull(ModAlertSettings.decode(message(good, version = 0)))
        assertNull(ModAlertSettings.decode(message(good, version = ModWatchMessages.VERSION + 1)))
        assertNull(ModAlertSettings.decode(message(good, count = 2))) // one entry missing
        assertNull(ModAlertSettings.decode(message(good).copyOf(2 + 5))) // entry cut
        assertNull(ModAlertSettings.decode(message(entry(AlertType.LOW.id, length = ModAlertSettings.ENTRY_BYTES - 1))))
        assertNull(ModAlertSettings.decode(message(entry(AlertType.LOW.id, length = 0xff))))
        // a tick sent to this path by mistake
        assertNull(ModAlertSettings.decode(ModWatchMessages.encodeTick(ModWatchMessages.Tick(50, false, 1L, 1))))
    }

    @Test
    fun kindsThatAreNotStandardAlertsAreSkipped() {
        val got = ModAlertSettings.decode(message(entry(2), entry(3), entry(AlertType.HIGH.id), entry(15), entry(0xff)))
        assertEquals(listOf(AlertType.HIGH.id), got?.map { it.kind })
    }

    @Test
    fun aFieldOutOfItsRangeIsDroppedTheRestKept() {
        val bad = ModAlertSettings.decode(message(
            entry(AlertType.LOW.id, flags = 1 or 8, duration = 0, haptic = 0, volume = 29, delay = 61, interval = 1441,
                count = 101, snooze = 0),
            entry(AlertType.HIGH.id, duration = 61, haptic = 5, volume = 101, delay = 61, snooze = 1441),
            entry(AlertType.VERY_LOW.id, delay = 31, interval = 0xffff, count = 0xffff, snooze = 0xffff),
        ))!!
        assertEquals(Settings(AlertType.LOW.id, true, false, false, true, null, null, null, null, null, null, null), bad[0])
        val high = bad[1]
        assertNull(high.alarmDurationSeconds)
        assertNull(high.hapticProfile)
        assertNull(high.soundVolumePercent)
        assertEquals(61, high.soundDelaySeconds) // the hypo cap of 60 s is LOW's, not HIGH's
        assertNull(high.defaultSnoozeMinutes)
        assertEquals(5, high.retryIntervalMinutes)
        assertNull(bad[2].soundDelaySeconds) // VERY_LOW: 30 s at most
        assertNull(bad[2].retryIntervalMinutes)
        assertNull(bad[2].retryCount)
        assertNull(bad[2].defaultSnoozeMinutes)
        // and nothing of a dropped field is written on the watch
        prefs.edit().putInt("alert_${AlertType.LOW.id}_alarmDur", 33).commit()
        ModAlertSettings.apply(prefs, bad)
        assertEquals(33, prefs.getInt("alert_${AlertType.LOW.id}_alarmDur", 0))
        assertFalse(prefs.contains("alert_${AlertType.LOW.id}_snooze"))
    }

    @Test
    fun aLongerEntryAndUnknownFlagsFromALaterPartAreRead() {
        val got = ModAlertSettings.decode(message(
            entry(AlertType.HIGH.id, flags = 0xf0 or 2, duration = 20, extra = 3),
            entry(AlertType.LOW.id, duration = 50),
        ) + byteArrayOf(7, 7))
        assertEquals(listOf(AlertType.HIGH.id, AlertType.LOW.id), got?.map { it.kind })
        assertEquals(20, got!![0].alarmDurationSeconds)
        assertTrue(got[0].vibrationEnabled)
        assertFalse(got[0].soundEnabled)
        assertEquals(50, got[1].alarmDurationSeconds)
    }

    @Test
    fun thePhoneSendsUnknownAsDroppedAndConstantlyAsZero() {
        val odd = Settings(AlertType.HIGH.id, true, true, false, true, null, null, null, null, -1, null, 70_000)
        val got = ModAlertSettings.decode(ModAlertSettings.encode(listOf(odd)))!!.single()
        assertEquals(0, got.retryIntervalMinutes) // "constantly"
        assertNull(got.alarmDurationSeconds)
        assertNull(got.soundVolumePercent)
        assertNull(got.soundDelaySeconds)
        assertNull(got.retryCount)
        assertNull(got.defaultSnoozeMinutes)
    }

    @Test
    fun onlyTheSentKeysOfStandardAlertsCountAsAChange() {
        assertTrue(ModAlertSettings.isSentKey("alert_1_alarmDur"))
        assertTrue(ModAlertSettings.isSentKey("alert_0_soundVolumePercent"))
        assertTrue(ModAlertSettings.isSentKey("alert_9_retryOn"))
        assertTrue(ModAlertSettings.isSentKey("alert_14_snooze"))
        assertFalse(ModAlertSettings.isSentKey("alert_1_threshold"))
        assertFalse(ModAlertSettings.isSentKey("alert_1_soundUri"))
        assertFalse(ModAlertSettings.isSentKey("alert_1_enabled"))
        assertFalse(ModAlertSettings.isSentKey("alert_2_alarmDur"))
        assertFalse(ModAlertSettings.isSentKey(null))
    }
}
