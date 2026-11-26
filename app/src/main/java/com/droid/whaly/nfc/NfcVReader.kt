package com.droid.whaly.nfc

import android.nfc.Tag
import android.nfc.tech.NfcB
import android.nfc.tech.NfcV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ISO 15693 (NFC-V) and ISO 14443-B reader.
 *
 * Both are first-class Android technologies and neither is exposed anywhere else in the app,
 * which only ever opened NfcA/IsoDep. This is the read half of the Flipper's NFC menu that a
 * phone can actually do: inventory, system information, a full block dump for Slix and
 * ICODE, and a single-block write.
 *
 * Frame layout for NFC-V, from ISO/IEC 15693-3: flags, command, parameters. The Android
 * stack appends the CRC, so it must not be included here. Addressed mode is flag 0x20.
 */
object NfcVReader {

    data class Dump(
        val uid: String,
        val techs: List<String>,
        val kind: String,
        val dsfid: String,
        val afi: String,
        val blockSize: Int,
        val blockCount: Int,
        val icReference: String,
        val blocks: Map<Int, String>,
        val note: String,
    )

    private val _dump = MutableStateFlow<Dump?>(null)
    val dump: StateFlow<Dump?> = _dump.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun clear() {
        _dump.value = null
        _log.value = emptyList()
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }

    fun read(tag: Tag): Dump? {
        _busy.value = true
        return try {
            val techs = tag.techList.map { it.substringAfterLast('.') }
            val uid = tag.id.joinToString("") { "%02X".format(it) }
            val nfcV = NfcV.get(tag)
            val nfcB = NfcB.get(tag)
            val result = when {
                nfcV != null -> readV(nfcV, uid, techs)
                nfcB != null -> readB(nfcB, uid, techs)
                else -> Dump(
                    uid = uid,
                    techs = techs,
                    kind = "Neither NFC-V nor 14443-B",
                    dsfid = "-", afi = "-", blockSize = 0, blockCount = 0,
                    icReference = "-", blocks = emptyMap(),
                    note = "Tag answered on another technology: ${techs.joinToString(", ")}",
                )
            }
            _dump.value = result
            append("${result.kind} · $uid · ${result.blockCount} block(s) of ${result.blockSize} B")
            result
        } catch (error: Exception) {
            append("read failed: ${error.message}")
            null
        } finally {
            _busy.value = false
        }
    }

    private fun readV(nfcV: NfcV, uid: String, techs: List<String>): Dump {
        val info = runCatching { nfcV.transceive(byteArrayOf(0x20, 0x2B)) }.getOrNull()
        var blockSize = 4
        var blockCount = 0
        var dsfid = "-"
        var afi = "-"
        var icRef = "-"

        if (info != null && info.size >= 10) {
            // flags, infoFlags, UID (8), DSFID, AFI, blockSize, blockCount, IC reference
            dsfid = "%02X".format(info[info.size - 5])
            afi = "%02X".format(info[info.size - 4])
            blockSize = (info[info.size - 3].toInt() and 0xFF).coerceIn(1, 64)
            blockCount = (info[info.size - 2].toInt() and 0xFF) + 1
            icRef = "%02X".format(info[info.size - 1])
        }

        val kind = when {
            icRef == "03" -> "ICODE SLIX"
            icRef == "04" -> "ICODE SLIX2"
            icRef == "02" -> "ICODE SLI"
            icRef == "01" -> "ICODE SLI-S"
            icRef == "07" -> "ICODE DNA"
            icRef == "05" -> "ICODE SLIX-L"
            else -> "ISO 15693 (IC ref $icRef)"
        }

        val blocks = LinkedHashMap<Int, String>()
        if (blockCount in 1..512) {
            for (block in 0 until blockCount) {
                val frame = byteArrayOf(0x20, 0x20, block.toByte())
                val data = runCatching { nfcV.transceive(frame) }.getOrNull() ?: continue
                if (data.size < 2) continue
                val payload = data.copyOfRange(1, data.size)
                blocks[block] = payload.joinToString(" ") { "%02X".format(it) }
            }
        }

        val note = buildString {
            append("${blocks.size} of $blockCount blocks read")
            if (blocks.isEmpty()) append(" — card may require authentication before READ")
        }
        return Dump(
            uid = uid,
            techs = techs,
            kind = kind,
            dsfid = dsfid,
            afi = afi,
            blockSize = blockSize,
            blockCount = blockCount,
            icReference = icRef,
            blocks = blocks,
            note = note,
        )
    }

    private fun readB(nfcB: NfcB, uid: String, techs: List<String>): Dump {
        val appData = nfcB.applicationData?.joinToString(" ") { "%02X".format(it) } ?: "-"
        val protocol = nfcB.protocolInfo?.joinToString(" ") { "%02X".format(it) } ?: "-"
        // ATTRIB with a default CID: [0x00, 0x00, 0x00, 0x00] is enough to confirm the link
        // is alive on cards that accept it. Calypso and some transit cards reject it, which
        // is why the failure is reported instead of retried.
        val attrib = runCatching {
            nfcB.transceive(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        }.isSuccess
        return Dump(
            uid = uid,
            techs = techs,
            kind = "ISO 14443-B",
            dsfid = appData,
            afi = protocol,
            blockSize = 0,
            blockCount = 0,
            icReference = if (attrib) "ATTRIB accepted" else "ATTRIB refused",
            blocks = emptyMap(),
            note = "application data $appData, protocol info $protocol",
        )
    }

    /** Single-block write. Slix, ICODE and most 15693 tags accept it without auth. */
    fun writeBlock(tag: Tag, block: Int, data: ByteArray): Boolean {
        val nfcV = NfcV.get(tag) ?: return false
        if (data.isEmpty() || data.size > 64) return false
        val frame = ByteArray(3 + data.size)
        frame[0] = 0x20
        frame[1] = 0x21
        frame[2] = block.toByte()
        data.copyInto(frame, 3)
        val ok = runCatching { nfcV.transceive(frame) }.isSuccess
        append("write block $block → ${if (ok) "accepted" else "refused"}")
        return ok
    }

    fun export(dump: Dump): String = buildString {
        appendLine("# Whaly NFC-V dump")
        appendLine("kind: ${dump.kind}")
        appendLine("uid: ${dump.uid}")
        appendLine("tech: ${dump.techs.joinToString(", ")}")
        appendLine("dsfid: ${dump.dsfid}")
        appendLine("afi: ${dump.afi}")
        appendLine("blockSize: ${dump.blockSize}")
        appendLine("blockCount: ${dump.blockCount}")
        appendLine("ic: ${dump.icReference}")
        appendLine()
        dump.blocks.toSortedMap().forEach { (index, hex) ->
            appendLine("%02X: %s".format(index, hex))
        }
    }
}
