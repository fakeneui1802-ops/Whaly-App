package com.droid.whaly.scooter

/**
 * Frame builders for the scooter telematics families Whaly knows about.
 *
 * Ninebot / Xiaomi use a documented magic header and a CRC16 trailer: that part is
 * exact. Voi and Lime run proprietary firmware over a generic BLE UART, and no public
 * documentation names their unlock opcode, so the sweep below is a candidate list —
 * the point is to let the radio find the answer on the wire instead of guessing it here.
 */
object ScooterFrames {

    data class Frame(val label: String, val bytes: ByteArray) {
        fun hex(): String = bytes.joinToString(" ") { "%02X".format(it) }
    }

    /** CRC16/XMODEM — polynomial 0x1021, init 0x0000, no reflection, no xorout. */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (index in from until to) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** 5A A5 <len> <cmd> <payload> <crc lo> <crc hi>. Ninebot ES / MAX / G series. */
    fun ninebot(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf(0x5A, 0xA5.toByte(), payload.size.toByte(), cmd.toByte()) + payload
        val crc = crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    /** 55 AA <len> <cmd> <payload> <crc lo> <crc hi>. Xiaomi M365 / Pro / 1S / Essential. */
    fun xiaomi(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf(0x55, 0xAA.toByte(), payload.size.toByte(), cmd.toByte()) + payload
        val crc = crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    /** Okai / Voi / Lime style: one opcode byte, optional payload, no checksum. */
    fun opcode(value: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(value.toByte()) + payload

    /** Ninebot speed and mode commands, matching the tuning payloads already in the app. */
    val ninebotTuning: List<Frame> = listOf(
        Frame("Max 2G unlock", byteArrayOf(0x55, 0xAB.toByte(), 0x4D, 0x41, 0x58, 0x32, 0x53, 0x63, 0x6F, 0x6F, 0x74, 0x65, 0x72, 0x5F, 0x31)),
        Frame("F2 unlock", byteArrayOf(0x55, 0xAB.toByte(), 0x46, 0x32, 0x53, 0x63, 0x6F, 0x6F, 0x74, 0x65, 0x72, 0x5F, 0x31)),
        Frame("Gen 1 speed", byteArrayOf(0x5A, 0xA5.toByte(), 0x00, 0x4B, 0x48, 0x94.toByte(), 0xE3.toByte(), 0x3A, 0x91.toByte(), 0xE0.toByte(), 0x32, 0x7E, 0xC2.toByte())),
        Frame("Gen 2 speed", byteArrayOf(0x5A, 0xA5.toByte(), 0x00, 0x4B, 0x48, 0x94.toByte(), 0xE3.toByte(), 0x3A, 0x91.toByte(), 0xE0.toByte(), 0x43, 0x3E, 0xC2.toByte())),
        Frame("Gen 3 speed", byteArrayOf(0x5A, 0xA5.toByte(), 0x00, 0x4B, 0x48, 0x94.toByte(), 0xE3.toByte(), 0x3A, 0x91.toByte(), 0xE0.toByte(), 0x42, 0x7E, 0xC4.toByte())),
    )

    /** Ninebot commands that touch the rental lock bit. */
    val ninebotLock: List<Frame> = listOf(
        Frame("Read lock state", ninebot(0x3B)),
        Frame("Disable lock", ninebot(0x3D, byteArrayOf(0x01))),
        Frame("Enable lock", ninebot(0x3D, byteArrayOf(0x00))),
        Frame("Clear rental flag", ninebot(0x3E, byteArrayOf(0x00))),
        Frame("Force motor on", ninebot(0x25, byteArrayOf(0x01))),
    )

    /**
     * Candidate sweep for Voi / Lime / Okai telematics.
     *
     * These boxes speak a single-byte opcode protocol over a write characteristic. The
     * documented public knowledge stops at the frame shape, so this list covers the
     * opcodes that appear as lock, motor and mode commands in leaked dealer tools and
     * in captured app traffic. Anything the radio answers differently is worth keeping:
     * a notification that arrives only for one opcode is the unlock.
     */
    val rentalSweep: List<Frame> = buildList {
        add(Frame("Ping 0x00", opcode(0x00)))
        add(Frame("Ping 0xFF", opcode(0xFF)))
        for (value in listOf(0x01, 0x02, 0x03, 0x05, 0x10, 0x11, 0x20, 0x21, 0x30, 0x31, 0x40, 0x41)) {
            add(Frame("Opcode %02X".format(value), opcode(value)))
        }
        for (value in listOf(0x01, 0x02, 0x03)) {
            add(Frame("Opcode %02X + 00".format(value), opcode(value, byteArrayOf(0x00))))
            add(Frame("Opcode %02X + 01".format(value), opcode(value, byteArrayOf(0x01))))
        }
        add(Frame("Xiaomi lock off", xiaomi(0x3D, byteArrayOf(0x01))))
        add(Frame("Xiaomi motor on", xiaomi(0x25, byteArrayOf(0x01))))
        add(Frame("Ninebot unlock", ninebot(0x3D, byteArrayOf(0x01))))
    }

    /** Name fragments that mean the radio belongs to a shared scooter fleet. */
    val fleetNames: List<String> = listOf(
        "voi", "voiager", "lime", "lime-s", "bird", "tier", "dott", "bolt",
        "circ", "hive", "wind", "okai", "shared", "scooter", "e-scooter", "escooter",
    )
}
