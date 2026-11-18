package mod.watch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.mod.watch.ModAlertSettings
import tk.glucodata.mod.watch.ModAlertSettings.Settings
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWearListener
import tk.glucodata.mod.watch.PhoneBatteryStore
import tk.glucodata.mod.watch.WatchAlertStore

/**
 * JugglucoNGmod, watch block (parts 7b, 7d): `/mod/alerts` arriving on the watch - written into upstream's alert file,
 * or not at all; never an alert's on/off; an overtaken message dropped; the kept message written again after
 * upstream's toggle sync. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchAlertSettingsTest {

    /** Native thresholds in mg/L; the watch build's tests have no native side. */
    private class Levels : ModAlertSettings.ThresholdStore {
        val levels = HashMap<AlertType, Int>()
        override fun read(type: AlertType): Int? = levels[type]
        override fun write(type: AlertType, mgPerL: Int) {
            levels[type] = mgPerL
        }
    }

    private lateinit var context: Context

    private val alerts get() = context.getSharedPreferences(ModAlertSettings.ALERT_PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alerts.edit().clear().commit()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        WatchAlertStore.resetForTests()
    }

    private fun settings(kind: Int, seconds: Int) = Settings(
        kind = kind, soundEnabled = true, vibrationEnabled = true, soundDelayEnabled = false, retryEnabled = true,
        alarmDurationSeconds = seconds, hapticProfile = HapticProfile.STRONG, soundVolumePercent = 100,
        soundDelaySeconds = 0, retryIntervalMinutes = 0, retryCount = 0, defaultSnoozeMinutes = 30,
        deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY, timeRangeEnabled = true, activeStartHour = 22,
        activeStartMinute = 0, activeEndHour = 7, activeEndMinute = ModAlertSettings.TIME_UNSET,
    )

    @Test
    fun thePhoneSettingsLandUnderUpstreamKeys() {
        val data = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40), settings(AlertType.MISSED_READING.id, 15)), 1_000L)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        val low = "alert_${AlertType.LOW.id}_"
        assertEquals(40, alerts.getInt(low + "alarmDur", 5))
        assertTrue(alerts.getBoolean(low + "retryOn", false))
        assertEquals(0, alerts.getInt(low + "retryInt", 5))
        assertEquals(0, alerts.getInt(low + "retryCnt", 3))
        assertEquals("STRONG", alerts.getString(low + "haptic", null))
        assertEquals(100, alerts.getInt(low + "soundVolumePercent", 80))
        assertEquals(30, alerts.getInt(low + "snooze", 15))
        assertEquals("NOTIFICATION_ONLY", alerts.getString(low + "delivery", null))
        assertTrue(alerts.getBoolean(low + "timeRange", false))
        assertEquals(22, alerts.getInt(low + "startHour", -1))
        assertEquals(0, alerts.getInt(low + "startMin", -1))
        assertEquals(7, alerts.getInt(low + "endHour", -1))
        assertFalse(alerts.contains(low + "endMin"))
        assertEquals(15, alerts.getInt("alert_${AlertType.MISSED_READING.id}_alarmDur", 5))
        // no alert's on/off is ever written by the watch, native-backed or prefs-only
        assertFalse(alerts.contains(low + "enabled"))
        assertFalse(alerts.contains("alert_${AlertType.MISSED_READING.id}_enabled"))
        // the own melody, flash and DND never come from the phone
        assertFalse(alerts.contains(low + "soundUri"))
        assertFalse(alerts.contains(low + "flash"))
        assertFalse(alerts.contains(low + "dnd"))
    }

    @Test
    fun aBrokenOrNewerMessageChangesNothing() {
        alerts.edit().putInt("alert_${AlertType.LOW.id}_alarmDur", 9).commit()
        val good = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40)))
        for (bad in listOf(null, ByteArray(0), good.copyOf(good.size - 1), good.copyOf().also { it[0] = 2 })) {
            assertFalse(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, bad))
        }
        assertEquals(mapOf("alert_${AlertType.LOW.id}_alarmDur" to 9), alerts.all)
    }

    @Test
    fun theSameSettingsAgainAreStillAValidMessage() {
        val data = ModAlertSettings.encode(listOf(settings(AlertType.HIGH.id, 20)), 1_000L)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        val before = alerts.all
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        assertEquals(before, alerts.all)
    }

    @Test
    fun anOvertakenMessageIsDroppedAClockSetBackIsNot() {
        val t = 1_800_000_000_000L
        val key = "alert_${AlertType.HIGH.id}_alarmDur"
        fun send(seconds: Int, at: Long?) =
            WatchAlertStore.onMessage(context, ModAlertSettings.encode(listOf(settings(AlertType.HIGH.id, seconds)), at), Levels())
        assertTrue(send(40, t))
        // the tick read the settings before the change went, and arrived after it
        assertTrue(send(20, t - 5_000))
        assertEquals(40, alerts.getInt(key, 0))
        // the same time again, or newer: written
        assertTrue(send(41, t))
        assertEquals(41, alerts.getInt(key, 0))
        // older by more than the window: the phone clock went back - written, and the new base
        assertTrue(send(30, t - WatchAlertStore.STALE_WINDOW_MS))
        assertEquals(30, alerts.getInt(key, 0))
        assertTrue(send(31, t - WatchAlertStore.STALE_WINDOW_MS + 1))
        assertEquals(31, alerts.getInt(key, 0))
        // a phone of part 7b sends no time: always written
        assertTrue(send(25, null))
        assertEquals(25, alerts.getInt(key, 0))
    }

    @Test
    fun staleIsOnlyAShortStepBack() {
        assertFalse(WatchAlertStore.isStale(null, 100L))
        assertFalse(WatchAlertStore.isStale(100L, null))
        assertFalse(WatchAlertStore.isStale(100L, 100L))
        assertFalse(WatchAlertStore.isStale(101L, 100L))
        val last = 10 * WatchAlertStore.STALE_WINDOW_MS
        assertTrue(WatchAlertStore.isStale(last - 1, last))
        assertTrue(WatchAlertStore.isStale(last - WatchAlertStore.STALE_WINDOW_MS + 1, last))
        assertFalse(WatchAlertStore.isStale(last - WatchAlertStore.STALE_WINDOW_MS, last))
    }

    @Test
    fun theKeptMessageIsWrittenAgainAfterTheirToggleSync() {
        val missed = "alert_${AlertType.MISSED_READING.id}_"
        val levels = Levels()
        val s = settings(AlertType.MISSED_READING.id, 40)
        assertTrue(WatchAlertStore.onMessage(context, ModAlertSettings.encode(listOf(s), 1_000L), levels))
        // their toggle sync turns the alert on: their save writes the defaults over ours, with "enabled"
        alerts.edit().putBoolean(missed + "enabled", true).putBoolean(missed + "retryOn", false)
            .putInt(missed + "snooze", 15).putInt(missed + "alarmDur", 5).commit()
        assertEquals(3, WatchAlertStore.reapply(context, levels))
        assertTrue(alerts.getBoolean(missed + "retryOn", false))
        assertEquals(30, alerts.getInt(missed + "snooze", 0))
        assertEquals(40, alerts.getInt(missed + "alarmDur", 0))
        // their "enabled" stays as they wrote it
        assertTrue(alerts.getBoolean(missed + "enabled", false))
        assertEquals(0, WatchAlertStore.reapply(context, levels))
    }

    @Test
    fun aToggleOnTheWatchBringsOursBackByItself() {
        val missed = "alert_${AlertType.MISSED_READING.id}_"
        assertTrue(WatchAlertStore.onMessage(context, ModAlertSettings.encode(listOf(settings(AlertType.MISSED_READING.id, 40)), 1_000L)))
        alerts.edit().putBoolean(missed + "enabled", true).putInt(missed + "alarmDur", 5).commit()
        val until = System.currentTimeMillis() + WatchAlertStore.REAPPLY_DELAY_MS + 5_000
        while (alerts.getInt(missed + "alarmDur", 0) != 40 && System.currentTimeMillis() < until) Thread.sleep(100)
        assertEquals(40, alerts.getInt(missed + "alarmDur", 0))
    }

    @Test
    fun nothingKeptNothingWrittenAgain() {
        assertEquals(0, WatchAlertStore.reapply(context, Levels()))
        assertTrue(WatchAlertStore.isToggleKey("alert_9_enabled"))
        assertTrue(WatchAlertStore.isToggleKey("alert_0_enabled"))
        assertFalse(WatchAlertStore.isToggleKey("alert_2_enabled"))
        assertFalse(WatchAlertStore.isToggleKey("alert_9_alarmDur"))
        assertFalse(WatchAlertStore.isToggleKey(null))
    }
}
