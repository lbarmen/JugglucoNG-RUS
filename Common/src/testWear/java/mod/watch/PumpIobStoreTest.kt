package mod.watch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWatchMessages.PumpIob
import tk.glucodata.mod.watch.ModWatchMessages.Tick
import tk.glucodata.mod.watch.ModWearListener
import tk.glucodata.mod.watch.PhoneBatteryStore
import tk.glucodata.mod.watch.PumpIobStore

/**
 * JugglucoNGmod, watch face (20.11): the pump's active insulin from `/mod/tick` on the watch - kept with its own time on
 * the watch's clock, removed by a tick without it (an older phone too), the face woken only when it changed. Made-up
 * values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PumpIobStoreTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun tick(iob: PumpIob?) =
        ModWatchMessages.encodeTick(Tick(64, false, now - 3_000L, ModWatchMessages.REASON_CYCLE, iob))

    @Test
    fun aTickWithTheActiveInsulinKeepsItWithItsTimeOnTheWatchsClock() {
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now))
        assertEquals(PumpIobStore.PumpIob(23, now - 45_000L), PumpIobStore.read(context))
        // the phone battery as before
        assertEquals(64, PhoneBatteryStore.read(context, now))
    }

    @Test
    fun aTickWithoutItRemovesTheKeptOne() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(null), now + 5 * minute))
        assertNull(PumpIobStore.read(context))
        assertEquals(64, PhoneBatteryStore.read(context, now + 5 * minute))
    }

    @Test
    fun anOlderPhonesTickHasNoneAndIsStillATick() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now)
        // 12 bytes, as an older phone sends it
        val old = byteArrayOf(1, 64, 0, 0, 0, 1, -94, 53, 69, 0, 0, 1)
        assertEquals(12, old.size)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, old, now))
        assertNull(PumpIobStore.read(context))
    }

    @Test
    fun theFaceIsWokenOnlyWhenTheNumberChanged() {
        val r0 = PumpIobStore.revision.value
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now)
        val r1 = PumpIobStore.revision.value
        assertTrue(r1 > r0)
        // the same number 5 minutes later, its age 5 minutes more: no wake-up
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 345)), now + 5 * minute)
        assertEquals(r1, PumpIobStore.revision.value)
        // another value, the same number with a new time, gone: each wakes the face
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(31, 345)), now + 5 * minute)
        val r2 = PumpIobStore.revision.value
        assertTrue(r2 > r1)
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(31, 10)), now + 10 * minute)
        val r3 = PumpIobStore.revision.value
        assertTrue(r3 > r2)
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(null), now + 15 * minute)
        assertTrue(PumpIobStore.revision.value > r3)
    }

    @Test
    fun theChangeRule() {
        val a = PumpIobStore.PumpIob(23, now)
        assertFalse(PumpIobStore.changed(null, null))
        assertTrue(PumpIobStore.changed(null, a))
        assertTrue(PumpIobStore.changed(a, null))
        assertFalse(PumpIobStore.changed(a, a.copy(valueAtMs = now + PumpIobStore.SAME_TIME_MS)))
        assertTrue(PumpIobStore.changed(a, a.copy(valueAtMs = now + PumpIobStore.SAME_TIME_MS + 1)))
        assertTrue(PumpIobStore.changed(a, a.copy(tenths = 24)))
    }
}
