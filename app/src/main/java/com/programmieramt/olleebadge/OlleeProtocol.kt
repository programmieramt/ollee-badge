/*  Ollee Watch BLE protocol: framing, CRC, frame reassembly and the weekday register.

    Ported from Gadgetbridge (OlleeProtocol.kt, WeekdayRegisterComposer.kt —
    Copyright (C) 2026 Ken Blizzard-Caron), which is licensed under the GNU Affero
    General Public License v3 or later. This file is distributed under the same license. */
package com.programmieramt.olleebadge

import java.util.UUID

object OlleeProtocol {
    // Nordic UART Service
    val SERVICE_NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    /** app -> watch writes */
    val CHARACTERISTIC_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    /** watch -> app notifies */
    val CHARACTERISTIC_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Writes the weekday register ([WeekdayRegister]). */
    const val TARGET_SET_WEEKDAYS = 0x34
    /** Reads the weekday register back. */
    const val TARGET_GET_WEEKDAYS = 0x35
    /** Writes the alarm record ([AlarmRecord]). */
    const val TARGET_SET_ALARM = 0x25
    /** Reads the alarm record back. */
    const val TARGET_GET_ALARM = 0x2B
    /** Replies arrive on the request target plus this offset. */
    const val RESPONSE_OFFSET = 0x20

    /** Bytes per characteristic write. */
    const val MAX_CHUNK = 20

    /** Minimum frame length: 00, len, AA, 55, crcHi, crcLo, 02, target */
    internal const val MIN_FRAME_LENGTH = 8

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection). */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    /**
     * Frames [payload] to [target]: 00 len AA 55 crcHi crcLo 02 target payload…
     * (len = inner.size + 4; CRC over the inner bytes 02 target payload…).
     * Without a payload this is a read request; the reply arrives on target + [RESPONSE_OFFSET].
     */
    fun buildPacket(target: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }
        val inner = byteArrayOf(0x02, target.toByte()) + payload
        require(inner.size + 4 <= 0xFF) { "payload too large for single-byte LEN (inner ${inner.size})" }
        val crc = crc16(inner)
        return byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xAA.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner
    }

    /** Splits a framed packet into [MAX_CHUNK]-sized characteristic writes. */
    fun chunks(packet: ByteArray): List<ByteArray> =
        (packet.indices step MAX_CHUNK).map { packet.copyOfRange(it, minOf(it + MAX_CHUNK, packet.size)) }
}

/** A parsed frame from the watch: the target byte and the payload (without framing or CRC). */
class Frame(val target: Int, val payload: ByteArray)

/**
 * Reassembles fragmented 20-byte BLE notifications into complete frames. The watch pipelines
 * replies, so one fragment can carry the tail of one frame and the head of the next: after each
 * [accept], drain [pending] until it returns null. Leading bytes that cannot begin a valid frame
 * and frames with a bad CRC are dropped.
 */
class FrameReassembler {
    private var buffer = ByteArray(256)
    private var size = 0

    fun accept(chunk: ByteArray): Frame? {
        if (size + chunk.size > buffer.size) {
            buffer = buffer.copyOf((buffer.size * 2).coerceAtLeast(size + chunk.size))
        }
        System.arraycopy(chunk, 0, buffer, size, chunk.size)
        size += chunk.size
        return extractFrame()
    }

    /** Returns the next complete frame already buffered, if any. Call until null. */
    fun pending(): Frame? = extractFrame()

    fun reset() {
        size = 0
    }

    private fun extractFrame(): Frame? {
        while (true) {
            var pos = 0

            // Re-sync to the next 00 that is followed by the AA 55 magic
            while (pos < size && buffer[pos] != 0x00.toByte()) pos++
            while (pos + 4 <= size && (buffer[pos + 2] != 0xAA.toByte() || buffer[pos + 3] != 0x55.toByte())) {
                pos++
                while (pos < size && buffer[pos] != 0x00.toByte()) pos++
            }

            if (pos + 4 > size) {
                compact(pos)
                return null
            }
            val total = (buffer[pos + 1].toInt() and 0xFF) + 2
            if (pos + total > size) {
                compact(pos)
                return null
            }

            val frameBytes = buffer.copyOfRange(pos, pos + total)
            compact(pos + total)

            // A bad frame is consumed; a valid one may already be buffered behind it.
            parseFrame(frameBytes)?.let { return it }
        }
    }

    /** Discards everything before [from], keeping any partial frame that follows. */
    private fun compact(from: Int) {
        if (from <= 0) return
        val keep = size - from
        if (keep > 0) System.arraycopy(buffer, from, buffer, 0, keep)
        size = keep
    }

    private fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.size < OlleeProtocol.MIN_FRAME_LENGTH) return null
        if (bytes[2] != 0xAA.toByte() || bytes[3] != 0x55.toByte()) return null
        val crcField = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        val inner = bytes.copyOfRange(6, bytes.size)
        if (OlleeProtocol.crc16(inner) != crcField) return null
        return Frame(inner[1].toInt() and 0xFF, inner.copyOfRange(2, inner.size))
    }
}

/**
 * The alarm record. Read-back (0x4B, 32 bytes): enable, hourlyChime, snoozeEnable, hour, minute,
 * dayMask, chime, snoozeMin, hourMask lo/mid/hi, 0xFF, then five LE u32 0x0000000C. The write
 * (02 25, 33 bytes) has one more byte after snoozeMin: playNow, which makes the watch play the
 * record's chime once (the Ollee app's "Try chime"). Layout from Gadgetbridge's OlleeAlarm.kt.
 */
object AlarmRecord {
    const val READBACK_SIZE = 32
    private const val CHIME = 6
    /** Write-only byte; the read-back has no slot for it. */
    private const val PLAY_NOW = 8

    fun chimeOf(readback: ByteArray): Int = readback[CHIME].toInt() and 0xFF

    /** The write record for [readback] unchanged except [chime], with playNow as given. */
    fun toWrite(readback: ByteArray, chime: Int = chimeOf(readback), playNow: Boolean = false): ByteArray {
        require(readback.size >= READBACK_SIZE) { "alarm read-back must be $READBACK_SIZE bytes" }
        val record = readback.copyOfRange(0, PLAY_NOW) +
            byteArrayOf(if (playNow) 1 else 0) +
            readback.copyOfRange(PLAY_NOW, READBACK_SIZE)
        record[CHIME] = chime.toByte()
        return record
    }
}

/**
 * The 02 34 / 02 35 register: a 4-byte big-endian World Time offset followed by 14 ASCII
 * characters — the seven two-character weekday labels (Mon..Sun) the watch shows top left.
 *
 * The badge replaces all seven labels with the notification count, so it shows whatever day it
 * is. The offset lives in the same register and a partial write resets the watch's World Time,
 * so every write carries the offset exactly as read back.
 */
class WeekdayRegister(val offsetHeader: ByteArray, val labels: ByteArray) {
    init {
        require(offsetHeader.size == HEADER_SIZE) { "offset header must be $HEADER_SIZE bytes" }
        require(labels.size == LABELS_SIZE) { "labels must be $LABELS_SIZE bytes" }
    }

    fun toPayload(): ByteArray = offsetHeader + labels

    /** True when the labels are one of our badges rather than real weekday names. */
    val showsBadge: Boolean get() = isBadge(labels)

    companion object {
        const val HEADER_SIZE = 4
        const val LABELS_SIZE = 14
        private const val DAYS = 7

        /** Highest count the LCD can show; anything above is displayed as "11". */
        const val MAX_COUNT = 11

        /** The watch's factory weekday labels (Mon..Sun). */
        val DEFAULT_LABELS: ByteArray = "MOTUWETHFRSASU".toByteArray(Charsets.US_ASCII)

        /** Parses a 0x55 read-back payload; null if too short. */
        fun parse(payload: ByteArray): WeekdayRegister? {
            if (payload.size < HEADER_SIZE + LABELS_SIZE) return null
            return WeekdayRegister(
                payload.copyOfRange(0, HEADER_SIZE),
                payload.copyOfRange(HEADER_SIZE, HEADER_SIZE + LABELS_SIZE)
            )
        }

        /**
         * One two-character weekday cell showing [count]. The right cell garbles most digits
         * (hardware-verified by Gadgetbridge), so 1..9 -> "N " (blank right), 10/11 -> "10"/"11",
         * 12+ -> "11" ("11 or more").
         */
        fun badgeCell(count: Int): String = when {
            count <= 9 -> "$count "
            count <= MAX_COUNT -> count.toString()
            else -> "11"
        }

        /** A badge: [cell] (two characters, padded or cut to fit) in every weekday slot. */
        fun cellLabels(cell: String): ByteArray =
            cell.padEnd(2).take(2).repeat(DAYS).toByteArray(Charsets.US_ASCII)

        fun badgeLabels(count: Int): ByteArray = cellLabels(badgeCell(count))

        /**
         * Whether [labels] are one of our badges: the same two characters in all seven slots.
         * Real weekday names (MO, TU, … or custom ones) never repeat like that.
         */
        fun isBadge(labels: ByteArray): Boolean =
            labels.size == LABELS_SIZE && (2 until LABELS_SIZE).all { labels[it] == labels[it % 2] }

        /**
         * What to write after reading [current] back: an empty [cell] restores [weekdayLabels],
         * anything else shows that cell as the badge. Null when the watch already shows it.
         */
        fun planWrite(current: WeekdayRegister, cell: String, weekdayLabels: ByteArray): WeekdayRegister? {
            val wanted = if (cell.isEmpty()) weekdayLabels else cellLabels(cell)
            if (wanted.contentEquals(current.labels)) return null
            return WeekdayRegister(current.offsetHeader, wanted)
        }
    }
}
