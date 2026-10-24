package com.programmieramt.olleebadge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden vectors are hardware captures from Gadgetbridge's Ollee tests. */
class OlleeProtocolTest {

    private fun String.hex(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.ascii() = toString(Charsets.US_ASCII)

    private val goldenSetClock = "001aaa55fb5302239c8e516aa0abffffcc9c00004865feff0300ffff".hex()

    @Test
    fun crcMatchesGoldenFrame() {
        assertEquals(0xfb53, OlleeProtocol.crc16(goldenSetClock.copyOfRange(6, goldenSetClock.size)))
    }

    @Test
    fun buildPacketReproducesGoldenFrame() {
        val payload = goldenSetClock.copyOfRange(8, goldenSetClock.size)
        assertArrayEquals(goldenSetClock, OlleeProtocol.buildPacket(0x23, payload))
    }

    @Test
    fun readRequestHasNoPayload() {
        assertArrayEquals("0006aa552fe80227".hex(), OlleeProtocol.buildPacket(0x27))
    }

    @Test
    fun chunksSplitAtTwentyBytes() {
        val chunks = OlleeProtocol.chunks(goldenSetClock)
        assertEquals(listOf(20, 8), chunks.map { it.size }) // LEN 0x1a + 2 = 28 bytes
        assertArrayEquals(goldenSetClock, chunks[0] + chunks[1])
    }

    @Test
    fun reassemblerJoinsChunks() {
        val r = FrameReassembler()
        assertNull(r.accept(goldenSetClock.copyOfRange(0, 20)))
        val frame = r.accept(goldenSetClock.copyOfRange(20, goldenSetClock.size))
        assertNotNull(frame)
        assertEquals(0x23, frame!!.target)
        assertArrayEquals(goldenSetClock.copyOfRange(8, goldenSetClock.size), frame.payload)
    }

    @Test
    fun reassemblerDropsCrcMismatch() {
        val corrupt = goldenSetClock.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(FrameReassembler().accept(corrupt))
    }

    @Test
    fun reassemblerKeepsPipelinedFrameTail() {
        // Version reply (0x4A) immediately followed by alarm readback (0x4B), captured from the watch
        val r = FrameReassembler()
        assertNull(r.accept("002AAA555421024A444541444245454630312E30".hex()))
        assertNull(r.accept("352E303030302E30312E31304445414442454546".hex()))
        assertEquals(0x4A, r.accept("00000B200026AA559381024B0000010C00000005".hex())?.target)
        assertNull(r.pending())
        assertNull(r.accept("C0FF0FFF0C0000000C0000000C0000000C000000".hex()))
        assertEquals(0x4B, r.accept("0C000000".hex())?.target)
    }

    @Test
    fun reassemblerDrainsTwoFramesFromOneChunk() {
        val r = FrameReassembler()
        assertEquals(0x2A, r.accept(OlleeProtocol.buildPacket(0x2A) + OlleeProtocol.buildPacket(0x2B))?.target)
        assertEquals(0x2B, r.pending()?.target)
        assertNull(r.pending())
    }

    // --- weekday register ---

    /** 0x55 read-back: World Time offset -6:00 (FF FF AB A0) + factory weekday labels */
    private val readback = "ffffaba04d4f545557455448465253415355".hex()

    @Test
    fun parsesReadback() {
        val reg = WeekdayRegister.parse(readback)!!
        assertArrayEquals("ffffaba0".hex(), reg.offsetHeader)
        assertEquals("MOTUWETHFRSASU", reg.labels.ascii())
        assertFalse(reg.showsBadge)
        assertNull(WeekdayRegister.parse("ffff".hex()))
    }

    @Test
    fun badgeCells() {
        assertEquals("1 ", WeekdayRegister.badgeCell(1))
        assertEquals("9 ", WeekdayRegister.badgeCell(9))
        assertEquals("10", WeekdayRegister.badgeCell(10))
        assertEquals("11", WeekdayRegister.badgeCell(11))
        assertEquals("11", WeekdayRegister.badgeCell(99))
        assertEquals("6 6 6 6 6 6 6 ", WeekdayRegister.badgeLabels(6).ascii())
    }

    @Test
    fun recognisesOwnBadges() {
        assertTrue(WeekdayRegister.isBadge(WeekdayRegister.badgeLabels(6)))
        assertTrue(WeekdayRegister.isBadge(WeekdayRegister.badgeLabels(42)))
        assertTrue(WeekdayRegister.isBadge(WeekdayRegister.cellLabels("D*")))
        assertFalse(WeekdayRegister.isBadge(WeekdayRegister.DEFAULT_LABELS))
        assertFalse(WeekdayRegister.isBadge("MODIMIDOFRSASO".toByteArray()))
    }

    @Test
    fun cellLabelsRepeatTwoCharacters() {
        assertEquals("D*D*D*D*D*D*D*", WeekdayRegister.cellLabels("D*").ascii())
        assertEquals("w w w w w w w ", WeekdayRegister.cellLabels("w").ascii())
        assertEquals("ABABABABABABAB", WeekdayRegister.cellLabels("ABC").ascii())
    }

    @Test
    fun badgeWriteKeepsOffset() {
        val reg = WeekdayRegister.parse(readback)!!
        val update = WeekdayRegister.planWrite(reg, WeekdayRegister.badgeCell(3), WeekdayRegister.DEFAULT_LABELS)!!
        assertArrayEquals("ffffaba0".hex(), update.offsetHeader)
        assertEquals("3 3 3 3 3 3 3 ", update.labels.ascii())
        assertTrue(update.showsBadge)
        assertEquals(18, update.toPayload().size)
    }

    @Test
    fun zeroRestoresSavedWeekdayNames() {
        val showingBadge = WeekdayRegister("ffffaba0".hex(), WeekdayRegister.badgeLabels(4))
        val german = "MODIMIDOFRSASO".toByteArray(Charsets.US_ASCII)
        val update = WeekdayRegister.planWrite(showingBadge, "", german)!!
        assertEquals("MODIMIDOFRSASO", update.labels.ascii())
        assertArrayEquals("ffffaba0".hex(), update.offsetHeader)
    }

    @Test
    fun noWriteWhenWatchAlreadyShowsIt() {
        val reg = WeekdayRegister.parse(readback)!!
        assertNull(WeekdayRegister.planWrite(reg, "", WeekdayRegister.DEFAULT_LABELS))
        val badge = WeekdayRegister(reg.offsetHeader, WeekdayRegister.badgeLabels(15))
        assertNull(WeekdayRegister.planWrite(badge, WeekdayRegister.badgeCell(12), WeekdayRegister.DEFAULT_LABELS))
        val marker = WeekdayRegister(reg.offsetHeader, WeekdayRegister.cellLabels("J*"))
        assertNull(WeekdayRegister.planWrite(marker, "J*", WeekdayRegister.DEFAULT_LABELS))
    }

    // --- alarm record ---

    /** 0x4B read-back captured from the watch (alarm off, chime 0) */
    private val alarmReadback = ("0000010C00000005FFFFFFFF" + "0C000000".repeat(5)).hex()

    @Test
    fun alarmWriteInsertsPlayNowAfterSnoozeMinutes() {
        // What the Ollee app sent for "Try chime" with the same settings (18:31:02)
        val expected = ("0000010C0000000501FFFFFFFF" + "0C000000".repeat(5)).hex()
        assertArrayEquals(expected, AlarmRecord.toWrite(alarmReadback, playNow = true))
        assertEquals(33, AlarmRecord.toWrite(alarmReadback).size)
    }

    @Test
    fun alarmWriteSetsChimeAndKeepsEverythingElse() {
        // Ollee app "Try chime" with chime 14 (20:43:52) and the read-back it was based on
        val readback = ("010100" + "0C0002" + "04" + "05" + "00FF07FF" + "0C000000".repeat(5)).hex()
        val expected = ("010100" + "0C0002" + "0E" + "05" + "01" + "00FF07FF" + "0C000000".repeat(5)).hex()
        assertEquals(4, AlarmRecord.chimeOf(readback))
        assertArrayEquals(expected, AlarmRecord.toWrite(readback, chime = 14, playNow = true))
    }

    @Test
    fun alarmRestoreWritesReadbackUnchanged() {
        val restore = AlarmRecord.toWrite(alarmReadback)
        assertArrayEquals(alarmReadback.copyOfRange(0, 8), restore.copyOfRange(0, 8))
        assertEquals(0, restore[8].toInt())
        assertArrayEquals(alarmReadback.copyOfRange(8, 32), restore.copyOfRange(9, 33))
    }

    @Test
    fun badgeWriteFrameRoundTrips() {
        val reg = WeekdayRegister.planWrite(WeekdayRegister.parse(readback)!!, "2 ", WeekdayRegister.DEFAULT_LABELS)!!
        val packet = OlleeProtocol.buildPacket(OlleeProtocol.TARGET_SET_WEEKDAYS, reg.toPayload())
        val r = FrameReassembler()
        var frame: Frame? = null
        for (chunk in OlleeProtocol.chunks(packet)) frame = r.accept(chunk) ?: frame
        assertEquals(0x34, frame!!.target)
        assertArrayEquals(reg.toPayload(), frame.payload)
    }
}
