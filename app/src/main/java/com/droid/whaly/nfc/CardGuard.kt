package com.droid.whaly.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads a contactless payment card and reports exactly what it hands to any reader.
 *
 * This is the defensive half of the card question. A contactless card is not private: it
 * answers a reader that has done nothing but power it up. What it will not do is authorise
 * a payment without a cryptogram signed by the issuer, which is why an app cannot make one
 * act as money — see the notes on the screen.
 */
object CardGuard {

    data class Scheme(val aid: String, val name: String)

    data class Report(
        val scheme: Scheme?,
        val panMasked: String,
        val panLength: Int,
        val expiry: String,
        val cardholder: String?,
        val track2Exposed: Boolean,
        val cvm: String,
        val requiresOnline: Boolean,
        val tags: List<Pair<String, String>>,
        val warnings: List<String>,
        val at: Long,
    )

    private val _report = MutableStateFlow<Report?>(null)
    val report: StateFlow<Report?> = _report.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val ppse = byteArrayOf(
        0x00, 0xA4.toByte(), 0x04, 0x00, 0x0E,
        0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53, 0x2E, 0x44, 0x44, 0x46, 0x30, 0x31,
        0x00,
    )

    /** AID prefix to scheme name. */
    private val schemes = listOf(
        "A0000000031010" to "Visa",
        "A0000000032010" to "Visa Electron",
        "A0000000033010" to "Visa Interlink",
        "A0000000041010" to "Mastercard",
        "A0000000043060" to "Maestro",
        "A0000000050001" to "Maestro UK",
        "A0000000651010" to "JCB",
        "A00000002501" to "American Express",
        "A0000000250104" to "American Express",
        "A0000001523010" to "Discover",
        "A0000003241010" to "Discover",
        "A0000002771010" to "Diners Club",
        "A0000003330101" to "UnionPay",
        "A0000000042203" to "Mastercard US",
    )

    fun reset() {
        _report.value = null
        _log.value = emptyList()
    }

    fun read(tag: Tag, onDone: (Report?) -> Unit) {
        if (_running.value) return
        _running.value = true
        _log.value = emptyList()
        _report.value = null

        val iso = IsoDep.get(tag)
        if (iso == null) {
            append("Not an ISO-DEP card — this reader only handles contactless payment")
            _running.value = false
            onDone(null)
            return
        }

        try {
            iso.connect()
            iso.timeout = 3000
            append("ATS ${hex(iso.historicalBytes ?: ByteArray(0))}")

            val ppseResponse = iso.transceive(ppse)
            append("PPSE ${ppseResponse.size} bytes")
            val aids = findAids(ppseResponse)
            if (aids.isEmpty()) {
                append("No payment application answered")
                _running.value = false
                onDone(null)
                return
            }
            aids.forEach { append("AID $it · ${schemeFor(it)?.name ?: "unknown"}") }

            val aid = aids.first()
            val scheme = schemeFor(aid)
            val selectAid = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00) +
                byteArrayOf((aid.length / 2).toByte()) +
                aid.chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
                byteArrayOf(0x00)
            iso.transceive(selectAid)

            // Get Processing Options tells the card which records to read.
            val gpo = iso.transceive(
                byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00),
            )
            append("GPO ${gpo.size} bytes")

            val records = mutableListOf<ByteArray>()
            for (sfi in 1..4) {
                for (record in 1..5) {
                    val command = byteArrayOf(
                        0x00, 0xB2.toByte(), record.toByte(),
                        ((sfi shl 3) or 4).toByte(), 0x00,
                    )
                    val response = runCatching { iso.transceive(command) }.getOrNull() ?: continue
                    if (response.size < 4) continue
                    if (!isSuccess(response)) continue
                    records += response
                }
            }
            append("${records.size} record(s) read")

            val tags = linkedMapOf<Int, ByteArray>()
            records.forEach { collectTags(it, tags) }

            val panBytes = tags[0x5A]
            val track2 = tags[0x57]
            val expiryBytes = tags[0x5F24]
            val holderBytes = tags[0x5F20]
            val cvmBytes = tags[0x8E]
            val aucBytes = tags[0x9F07]

            val pan = panBytes?.let { bcd(it) }
                ?: track2?.let { bcd(it).substringBefore('D') }
                ?: ""
            val masked = if (pan.length >= 10) {
                pan.take(6) + "•".repeat(pan.length - 10) + pan.takeLast(4)
            } else {
                pan
            }

            val expiry = expiryBytes?.let {
                val text = bcd(it)
                if (text.length >= 4) "${text.substring(0, 2)}/20${text.substring(2, 4)}" else text
            } ?: ""

            val holder = holderBytes?.let { String(it, Charsets.US_ASCII).trim() }?.ifBlank { null }
            val cvm = cvmBytes?.let { describeCvm(it) } ?: "not declared"
            val requiresOnline = aucBytes?.let { bcd(it).contains('2') } ?: false

            val warnings = mutableListOf<String>()
            if (track2 != null) {
                warnings += "Track 2 equivalent data is present. This is the magstripe payload " +
                    "in digital form — any reader in range can ask for it."
            }
            if (holder != null) {
                warnings += "Cardholder name is stored in the clear and is returned to any reader."
            }
            if (expiry.isNotBlank()) {
                warnings += "Expiry is returned in the clear alongside the PAN."
            }
            if (aucBytes != null && !requiresOnline) {
                warnings += "Card Authentication says offline is allowed, so a terminal can accept " +
                    "it without contacting the issuer."
            }
            if (warnings.isEmpty()) warnings += "Nothing unusual in what this card exposes."

            val result = Report(
                scheme = scheme,
                panMasked = masked,
                panLength = pan.length,
                expiry = expiry,
                cardholder = holder,
                track2Exposed = track2 != null,
                cvm = cvm,
                requiresOnline = requiresOnline,
                tags = tags.entries.map { "0x%02X".format(it.key) to hex(it.value) }.take(40),
                warnings = warnings,
                at = System.currentTimeMillis(),
            )
            _report.value = result
            append("done — ${scheme?.name ?: "unknown scheme"}, ${tags.size} tags")
            onDone(result)
        } catch (error: Exception) {
            append("card error: ${error.message}")
            onDone(null)
        } finally {
            runCatching { iso.close() }
            _running.value = false
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun findAids(response: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index + 1 < response.size) {
            val tag = response[index].toInt() and 0xFF
            var length = response[index + 1].toInt() and 0xFF
            var header = 2
            if (length == 0x81 && index + 2 < response.size) {
                length = response[index + 2].toInt() and 0xFF
                header = 3
            } else if (length == 0x82 && index + 3 < response.size) {
                length = ((response[index + 2].toInt() and 0xFF) shl 8) or
                    (response[index + 3].toInt() and 0xFF)
                header = 4
            }
            val start = index + header
            if (start + length > response.size) break
            if (tag == 0x4F) {
                val aid = hex(response.copyOfRange(start, start + length))
                if (aid.length >= 14) out += aid
            } else if (tag == 0xBF0C || tag == 0x61) {
                out += findAids(response.copyOfRange(start, start + length))
            }
            index = start + length
        }
        return out.distinct()
    }

    private fun schemeFor(aid: String): Scheme? {
        val match = schemes.firstOrNull { aid.startsWith(it.first) } ?: return null
        return Scheme(match.first, match.second)
    }

    /** Walks a BER-TLV record and keeps the primitive tags worth reporting. */
    private fun collectTags(data: ByteArray, into: MutableMap<Int, ByteArray>) {
        var index = 0
        var guard = 0
        while (index < data.size && guard++ < 200) {
            if (index + 1 >= data.size) break
            var tag = data[index].toInt() and 0xFF
            var tagLength = 1
            if (tag and 0x1F == 0x1F) {
                if (index + 2 >= data.size) break
                tag = (tag shl 8) or (data[index + 1].toInt() and 0xFF)
                tagLength = 2
            }
            var length = data[index + tagLength].toInt() and 0xFF
            var lengthLength = 1
            if (length == 0x81) {
                if (index + tagLength + 1 >= data.size) break
                length = data[index + tagLength + 1].toInt() and 0xFF
                lengthLength = 2
            } else if (length == 0x82) {
                if (index + tagLength + 2 >= data.size) break
                length = ((data[index + tagLength + 1].toInt() and 0xFF) shl 8) or
                    (data[index + tagLength + 2].toInt() and 0xFF)
                lengthLength = 3
            }
            val valueStart = index + tagLength + lengthLength
            if (valueStart + length > data.size) break
            val value = data.copyOfRange(valueStart, valueStart + length)
            val constructed = tagLength == 1 && (data[index].toInt() and 0x20) != 0
            if (constructed) {
                collectTags(value, into)
            } else {
                into[tag] = value
            }
            index = valueStart + length
        }
    }

    private fun describeCvm(bytes: ByteArray): String {
        if (bytes.size < 8) return "malformed"
        val first = bytes[1].toInt() and 0xFF
        val second = bytes[2].toInt() and 0xFF
        val conditions = when (bytes[0].toInt() and 0xFF) {
            0x00 -> "always"
            0x01 -> "unattended cash"
            0x02 -> "not unattended cash"
            0x03 -> "terminal supports the CVM"
            else -> "condition 0x%02X".format(bytes[0].toInt() and 0xFF)
        }
        fun name(code: Int): String = when (code) {
            0x00 -> "fail"
            0x01 -> "plain PIN"
            0x02 -> "enciphered PIN"
            0x03 -> "plain PIN + signature"
            0x04 -> "enciphered PIN + signature"
            0x05 -> "signature"
            0x1E -> "no CVM required"
            0x1F -> "no CVM required"
            else -> "method 0x%02X".format(code)
        }
        return "$conditions → ${name(first)}, then ${name(second)}"
    }

    private fun isSuccess(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return sw1 == 0x90 || (sw1 == 0x61) || (sw1 == 0x6C)
    }

    private fun bcd(bytes: ByteArray): String = buildString {
        for (byte in bytes) {
            val high = (byte.toInt() shr 4) and 0x0F
            val low = byte.toInt() and 0x0F
            if (high != 0x0F) append(high.toString(16).uppercase())
            if (low != 0x0F) append(if (low == 0x0D) 'D' else low.toString(16).uppercase())
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }
}
