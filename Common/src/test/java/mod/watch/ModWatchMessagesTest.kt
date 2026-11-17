package mod.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWatchMessages.SnoozeDone
import tk.glucodata.mod.watch.ModWatchMessages.SnoozeRequest

/** JugglucoNGmod, watch block: the byte format of our phone-watch messages (runs in both test tasks). Made-up values. */
class ModWatchMessagesTest {

    private val at = 1_800_000_000_000L

    @Test
    fun snoozeRoundTrip() {
        val bytes = ModWatchMessages.encodeSnooze(SnoozeRequest(at))
        assertEquals(1 + 8, bytes.size)
        assertEquals(ModWatchMessages.VERSION, bytes[0].toInt())
        assertEquals(SnoozeRequest(at), ModWatchMessages.decodeSnooze(bytes))
    }

    @Test
    fun snoozeDoneRoundTripForASnoozedKindAndForNoAlert() {
        val snoozed = SnoozeDone(7, at)
        assertEquals(snoozed, ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(snoozed)))
        val none = SnoozeDone(null, at)
        val bytes = ModWatchMessages.encodeSnoozeDone(none)
        assertEquals(1 + 4 + 8, bytes.size)
        assertEquals(none, ModWatchMessages.decodeSnoozeDone(bytes))
        val zero = SnoozeDone(0, at)
        assertEquals(zero, ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(zero)))
    }

    @Test
    fun emptyShortAndNullMessagesAreNull() {
        assertNull(ModWatchMessages.decodeSnooze(null))
        assertNull(ModWatchMessages.decodeSnooze(ByteArray(0)))
        assertNull(ModWatchMessages.decodeSnoozeDone(null))
        assertNull(ModWatchMessages.decodeSnoozeDone(ByteArray(0)))
        val snooze = ModWatchMessages.encodeSnooze(SnoozeRequest(at))
        for (n in 1 until snooze.size) assertNull("$n bytes", ModWatchMessages.decodeSnooze(snooze.copyOf(n)))
        val done = ModWatchMessages.encodeSnoozeDone(SnoozeDone(1, at))
        for (n in 1 until done.size) assertNull("$n bytes", ModWatchMessages.decodeSnoozeDone(done.copyOf(n)))
    }

    @Test
    fun versionZeroAndNewerVersionsAreNull() {
        for (version in listOf(0, ModWatchMessages.VERSION + 1, 0x7f, 0xff)) {
            val snooze = ModWatchMessages.encodeSnooze(SnoozeRequest(at)).also { it[0] = version.toByte() }
            assertNull("version $version", ModWatchMessages.decodeSnooze(snooze))
            val done = ModWatchMessages.encodeSnoozeDone(SnoozeDone(1, at)).also { it[0] = version.toByte() }
            assertNull("version $version", ModWatchMessages.decodeSnoozeDone(done))
        }
    }

    @Test
    fun brokenFieldsAreNull() {
        assertNull(ModWatchMessages.decodeSnooze(ModWatchMessages.encodeSnooze(SnoozeRequest(-5))))
        assertNull(ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(SnoozeDone(-2, at))))
        assertNull(ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(SnoozeDone(1, -1))))
    }

    @Test
    fun anUnknownTailIsIgnored() {
        val snooze = ModWatchMessages.encodeSnooze(SnoozeRequest(at)) + byteArrayOf(9, 8, 7)
        assertEquals(SnoozeRequest(at), ModWatchMessages.decodeSnooze(snooze))
        val done = ModWatchMessages.encodeSnoozeDone(SnoozeDone(4, at)) + ByteArray(16) { 0x55 }
        assertEquals(SnoozeDone(4, at), ModWatchMessages.decodeSnoozeDone(done))
    }

    @Test
    fun theWireFormatIsBigEndianAfterTheVersionByte() {
        val bytes = ModWatchMessages.encodeSnoozeDone(SnoozeDone(null, 0x0102030405060708L))
        assertArrayEquals(byteArrayOf(1, -1, -1, -1, -1, 1, 2, 3, 4, 5, 6, 7, 8), bytes)
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = java.util.Random(42)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(24)).also { random.nextBytes(it) }
            ModWatchMessages.decodeSnooze(bytes)
            ModWatchMessages.decodeSnoozeDone(bytes)
        }
    }

    @Test
    fun allPathsAreUnderOurPrefixAndNotUpstreams() {
        val paths = listOf(
            ModWatchMessages.PATH_SNOOZE, ModWatchMessages.PATH_SNOOZE_DONE, ModWatchMessages.PATH_TICK,
            ModWatchMessages.PATH_HEALTH_BATCH_PREFIX,
        )
        for (path in paths) assertTrue(path, path.startsWith(ModWatchMessages.PATH_PREFIX))
        assertEquals(paths.size, paths.toSet().size)
        // upstream's MessageReceiver filters (wear and mobile manifests) list their own prefixes only; none of them is
        // a prefix of ours, so their receiver never gets our messages
        for (theirs in tk.glucodata.WearMessagePath.entries) {
            for (path in paths) assertTrue("$theirs vs $path", !path.startsWith(theirs.wire))
        }
    }
}
