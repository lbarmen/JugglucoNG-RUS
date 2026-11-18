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
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.mod.watch.ModAlertSettings
import tk.glucodata.mod.watch.ModAlertSettings.Settings
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWearListener

/** JugglucoNGmod, watch block (part 7b): `/mod/alerts` arriving on the watch - written into upstream's alert file, or not at all. Made-up values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchAlertSettingsTest {

    private lateinit var context: Context

    private val alerts get() = context.getSharedPreferences(ModAlertSettings.ALERT_PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alerts.edit().clear().commit()
    }

    private fun settings(kind: Int, seconds: Int) = Settings(
        kind = kind, soundEnabled = true, vibrationEnabled = true, soundDelayEnabled = false, retryEnabled = true,
        alarmDurationSeconds = seconds, hapticProfile = HapticProfile.STRONG, soundVolumePercent = 100,
        soundDelaySeconds = 0, retryIntervalMinutes = 0, retryCount = 0, defaultSnoozeMinutes = 30,
    )

    @Test
    fun thePhoneSettingsLandUnderUpstreamKeys() {
        val data = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40), settings(AlertType.MISSED_READING.id, 15)))
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        val low = "alert_${AlertType.LOW.id}_"
        assertEquals(40, alerts.getInt(low + "alarmDur", 5))
        assertTrue(alerts.getBoolean(low + "retryOn", false))
        assertEquals(0, alerts.getInt(low + "retryInt", 5))
        assertEquals(0, alerts.getInt(low + "retryCnt", 3))
        assertEquals("STRONG", alerts.getString(low + "haptic", null))
        assertEquals(100, alerts.getInt(low + "soundVolumePercent", 80))
        assertEquals(30, alerts.getInt(low + "snooze", 15))
        assertEquals(15, alerts.getInt("alert_${AlertType.MISSED_READING.id}_alarmDur", 5))
        // LOW's on/off lives in the native side: its key is not touched; a prefs-only alert gets its default once
        assertFalse(alerts.contains(low + "enabled"))
        assertTrue(alerts.contains("alert_${AlertType.MISSED_READING.id}_enabled"))
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
        val data = ModAlertSettings.encode(listOf(settings(AlertType.HIGH.id, 20)))
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        val before = alerts.all
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        assertEquals(before, alerts.all)
    }
}
