package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.HealthPermissions.Perms
import tk.glucodata.mod.health.HealthSubscription

/** JugglucoNGmod, watch block (part 4b): when the Health Services subscription is made again. Made-up times. */
class HealthSubscriptionTest {

    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val all = Perms(hr = true, background = true, activity = true)
    private val none = Perms(hr = false, background = true, activity = false)

    private fun reason(
        now: Long,
        perms: Perms = all,
        permsAt: String = all.short(),
        registered: Boolean = true,
        registeredAt: Long = t0,
        lastDataAt: Long = t0,
        attemptAt: Long = t0,
    ) = HealthSubscription.registerReason(now, perms, permsAt, registered, registeredAt, lastDataAt, attemptAt)

    @Test
    fun onlyFromAndroid11() {
        assertFalse(HealthSubscription.supported(29))
        assertTrue(HealthSubscription.supported(30))
        assertTrue(HealthSubscription.supported(34))
    }

    @Test
    fun silentForFifteenMinutesSubscribesAgain() {
        assertNull(reason(t0 + 10 * minute, lastDataAt = t0 + 5 * minute))
        assertNull(reason(t0 + 20 * minute, lastDataAt = t0 + 5 * minute)) // data 15 min ago exactly: not yet
        assertEquals("silent", reason(t0 + 20 * minute + 1, lastDataAt = t0 + 5 * minute))
        // A fresh subscription counts as a sign of life too.
        assertNull(reason(t0 + 20 * minute, registeredAt = t0 + 10 * minute, lastDataAt = 0, attemptAt = 0))
    }

    @Test
    fun atLeastEveryTwelveHoursAndWhenTheClockWentBack() {
        assertNull(reason(t0 + 12 * hour - 1, lastDataAt = t0 + 12 * hour - minute))
        assertEquals("stale", reason(t0 + 12 * hour, lastDataAt = t0 + 12 * hour - minute))
        assertEquals("stale", reason(t0 - hour, attemptAt = 0, lastDataAt = t0 - hour))
    }

    @Test
    fun noneOrChangedPermissionsAndNoRetryWithinFifteenMinutes() {
        assertEquals("new", reason(t0 + 20 * minute, registered = false, registeredAt = 0))
        assertNull(reason(t0 + 5 * minute, registered = false, registeredAt = 0)) // tried 5 min ago
        assertNull(reason(t0 + 20 * minute, perms = none, registered = false, registeredAt = 0)) // nothing allowed
        val hrOnly = Perms(hr = true, background = true, activity = false)
        assertEquals("perms", reason(t0 + 20 * minute, perms = all, permsAt = hrOnly.short(), lastDataAt = t0 + 19 * minute))
        // Everything taken back while subscribed: subscribe again, which drops the subscription.
        assertEquals("perms", reason(t0 + 20 * minute, perms = none, lastDataAt = t0 + 19 * minute))
    }

    @Test
    fun statusFieldsHaveNoValuesAndNamesStayShort() {
        assertEquals("hr,steps", HealthSubscription.typeNames(hr = true, steps = true))
        assertEquals("steps", HealthSubscription.typeNames(hr = false, steps = true))
        assertEquals("", HealthSubscription.typeNames(hr = false, steps = false))
        assertEquals("perm=hr1,bg1,act1;reg=hr+steps;regErr=-", HealthSubscription.statusFields(all, "hr,steps", ""))
        assertEquals("perm=hr0,bg1,act0;reg=-;regErr=TimeoutException", HealthSubscription.statusFields(none, "", "TimeoutException"))
    }

    @Test
    fun healthServicesTimesCountFromBoot() {
        val boot = HealthSubscription.bootEpochMs(nowMs = t0, elapsedSinceBootMs = 3 * hour)
        assertEquals(t0 - 3 * hour, boot)
        assertEquals(t0 - minute, boot + (3 * hour - minute)) // a reading 1 min ago, given as time since boot
    }
}
