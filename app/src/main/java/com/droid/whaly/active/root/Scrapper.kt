package com.droid.whaly.active.root

import com.droid.whaly.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Passive 802.11 capture from a monitor-mode interface.
 *
 * This is the only honest way to see other devices' traffic without being on their
 * network: put the radio in monitor mode and read what the air already carries. Payloads
 * inside WPA2 are encrypted and stay encrypted — what is readable is the metadata layer,
 * which is where the interesting leaks live: every MAC in range, every SSID each device
 * probes for (its saved-network list), beacon and probe-response SSIDs, and plaintext DNS
 * on open networks.
 */
object Scrapper {

    enum class Kind { PHONE, LAPTOP, IOT, AP, UNKNOWN }

    data class Device(
        val mac: String,
        val kind: Kind,
        val vendor: String,
        val probes: Set<String>,
        val rssi: Int,
        val packets: Int,
        val lastSeen: Long,
        val firstSeen: Long,
    )

    data class AccessPoint(
        val bssid: String,
        val ssid: String,
        val channel: Int,
        val rssi: Int,
        val encrypted: Boolean,
        val wps: Boolean,
        val seen: Int,
    )

    data class DnsHit(val client: String, val query: String, val at: Long)

    data class Handshake(
        val bssid: String,
        val station: String,
        val messages: Set<Int>,
        val pmkid: String?,
        val lastSeen: Long,
    ) {
        val complete: Boolean get() = messages.containsAll(setOf(1, 2, 3, 4))
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _aps = MutableStateFlow<List<AccessPoint>>(emptyList())
    val aps: StateFlow<List<AccessPoint>> = _aps.asStateFlow()
    private val _dns = MutableStateFlow<List<DnsHit>>(emptyList())
    val dns: StateFlow<List<DnsHit>> = _dns.asStateFlow()
    private val _handshakes = MutableStateFlow<List<Handshake>>(emptyList())
    val handshakes: StateFlow<List<Handshake>> = _handshakes.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _frames = MutableStateFlow(0)
    val frames: StateFlow<Int> = _frames.asStateFlow()
    private val _pcapPath = MutableStateFlow<String?>(null)
    val pcapPath: StateFlow<String?> = _pcapPath.asStateFlow()

    private val active = AtomicBoolean(false)
    private var process: Process? = null
    private var thread: Thread? = null
    private var interfaceName: String = "mon0"

    // Mutable accumulators, published on a timer so Compose is not rebuilt per frame.
    private val deviceMap = linkedMapOf<String, MutableDevice>()
    private val apMap = linkedMapOf<String, MutableAp>()
    private val dnsList = mutableListOf<DnsHit>()
    private val handshakeMap = linkedMapOf<String, MutableHandshake>()

    private class MutableHandshake(
        val bssid: String,
        val station: String,
        val messages: MutableSet<Int> = linkedSetOf(),
        var pmkid: String? = null,
        var lastSeen: Long = System.currentTimeMillis(),
    )

    private class MutableDevice(
        val mac: String,
        var kind: Kind = Kind.UNKNOWN,
        var vendor: String = "unknown",
        val probes: MutableSet<String> = linkedSetOf(),
        var rssi: Int = -100,
        var packets: Int = 0,
        var firstSeen: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
    )

    private class MutableAp(
        val bssid: String,
        var ssid: String,
        var channel: Int,
        var rssi: Int,
        var encrypted: Boolean,
        var wps: Boolean,
        var seen: Int,
    )

    /** Creates a monitor interface if the driver allows it. Returns null on success. */
    fun prepareInterface(): String? {
        if (!RootUtils.isRooted()) return "root required"
        val existing = shell("iw dev 2>/dev/null")
        interfaceName = when {
            existing.contains("mon0") -> "mon0"
            existing.contains("mon1") -> "mon1"
            else -> {
                shell("ip link set wlan0 down; iw dev wlan0 interface add mon0 type monitor")
                shell("ip link set mon0 up")
                "mon0"
            }
        }
        val check = shell("ip link show $interfaceName 2>/dev/null")
        if (!check.contains(interfaceName)) {
            shell("iw dev wlan0 interface del 2>/dev/null")
            return "driver has no monitor mode — needs Nexmon or a vendor testmode build"
        }
        if (!shell("which tcpdump").contains("tcpdump")) {
            return "tcpdump missing — install it or use a ROM that ships it"
        }
        append("monitor interface $interfaceName ready")
        return null
    }

    fun start(channel: Int = 0, savePcap: Boolean = true) {
        if (!active.compareAndSet(false, true)) return
        val problem = prepareInterface()
        if (problem != null) {
            append(problem)
            active.set(false)
            return
        }
        if (channel in 1..196) {
            shell("iw dev $interfaceName set channel $channel")
            append("locked to channel $channel")
        } else {
            append("hopping all channels")
        }

        deviceMap.clear()
        apMap.clear()
        dnsList.clear()
        handshakeMap.clear()
        _handshakes.value = emptyList()
        _frames.value = 0
        _running.value = true

        val pcapFile = if (savePcap) {
            File("/data/local/tmp", "whaly-${System.currentTimeMillis()}.pcap").also {
                _pcapPath.value = it.absolutePath
            }
        } else {
            _pcapPath.value = null
            null
        }

        thread = Thread({
            try {
                val command = if (pcapFile != null) {
                    "tcpdump -i $interfaceName -U -s 0 -w - 2>/dev/null | tee ${pcapFile.absolutePath}"
                } else {
                    "tcpdump -i $interfaceName -U -s 0 -w - 2>/dev/null"
                }
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                process = proc
                val input = DataInputStream(proc.inputStream)
                val header = ByteArray(24)
                input.readFully(header)
                if (header[0].toInt() != 0xD4 || header[1].toInt() != 0xC3) {
                    append("unexpected capture header — tcpdump is not emitting pcap")
                    return@Thread
                }
                append("capture running")
                val record = ByteArray(16)
                var counter = 0
                while (active.get()) {
                    input.readFully(record)
                    val length = ((record[8].toInt() and 0xFF)) or
                        ((record[9].toInt() and 0xFF) shl 8) or
                        ((record[10].toInt() and 0xFF) shl 16) or
                        ((record[11].toInt() and 0xFF) shl 24)
                    if (length <= 0 || length > 65535) continue
                    val packet = ByteArray(length)
                    input.readFully(packet)
                    handlePacket(packet)
                    counter++
                    if (counter % 20 == 0) {
                        _frames.value = counter
                        publish()
                    }
                }
            } catch (_: java.io.EOFException) {
                append("capture stream closed")
            } catch (error: Exception) {
                append("capture failed: ${error.message}")
            } finally {
                _running.value = false
                active.set(false)
                publish()
            }
        }, "whaly-scrapper")
        thread?.start()

        Thread({
            while (active.get()) {
                publish()
                try {
                    Thread.sleep(1200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "whaly-scrapper-publish").start()
    }

    fun stop() {
        active.set(false)
        runCatching { process?.destroy() }
        process = null
        thread?.interrupt()
        _running.value = false
        publish()
        append("stopped")
    }

    fun clear() {
        deviceMap.clear()
        apMap.clear()
        dnsList.clear()
        synchronized(handshakeMap) { handshakeMap.clear() }
        _handshakes.value = emptyList()
        publish()
    }

    fun isRunning(): Boolean = active.get()

    /** The SSIDs this phone's neighbours have saved, strongest first. Feed one to the rogue AP. */
    fun karmaCandidates(): List<String> = deviceMap.values
        .flatMap { it.probes }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }
        .filter { it.isNotBlank() && it != "<hidden>" }

    // -----------------------------------------------------------------------------------------

    private fun handlePacket(packet: ByteArray) {
        // radiotap: version(1) pad(1) length(2)
        if (packet.size < 8) return
        val radiotapLength = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        if (radiotapLength <= 0 || packet.size < radiotapLength + 24) return
        val rssi = readRadiotapRssi(packet, radiotapLength)
        val frame = radiotapLength
        val frameControl = (packet[frame].toInt() and 0xFF) or ((packet[frame + 1].toInt() and 0xFF) shl 8)
        val type = (frameControl shr 2) and 0x3
        val subtype = (frameControl shr 4) and 0xF

        val addr1 = mac(packet, frame + 4)
        val addr2 = mac(packet, frame + 10)
        val addr3 = mac(packet, frame + 16)

        when (type) {
            0 -> handleManagement(subtype, packet, frame, radiotapLength, addr1, addr2, addr3, rssi)
            2 -> handleData(packet, frame, addr1, addr2, radiotapLength, rssi)
        }
    }

    private fun handleManagement(
        subtype: Int,
        packet: ByteArray,
        frame: Int,
        headerLength: Int,
        addr1: String,
        addr2: String,
        addr3: String,
        rssi: Int,
    ) {
        val bodyStart = when (subtype) {
            8, 5 -> frame + 36          // beacon / probe response: 24 header + 12 fixed
            4 -> frame + 24             // probe request: no fixed fields
            0, 2 -> frame + 28          // assoc req / reassoc req: 24 + 4
            else -> return
        }
        val tags = parseTags(packet, bodyStart, headerLength)

        if (subtype == 4) {
            // Probe request: the station is leaking its saved-network list.
            val ssid = tags["ssid"]?.ifBlank { "<hidden>" }
            val device = deviceMap.getOrPut(addr2) { MutableDevice(addr2) }
            device.packets++
            device.lastSeen = System.currentTimeMillis()
            if (rssi > device.rssi) device.rssi = rssi
            if (ssid != null) device.probes += ssid
            device.kind = classify(addr2)
            device.vendor = vendorOf(addr2)
            return
        }

        if (subtype == 8 || subtype == 5) {
            val ssid = tags["ssid"] ?: return
            if (ssid.isBlank()) return
            val channel = tags["channel"]?.toIntOrNull() ?: 0
            val encrypted = tags["privacy"] == "1"
            val wps = tags["wps"] == "1"
            val existing = apMap[addr3]
            if (existing == null) {
                apMap[addr3] = MutableAp(addr3, ssid, channel, rssi, encrypted, wps, 1)
            } else {
                existing.ssid = ssid
                existing.seen++
                existing.channel = channel
                if (rssi > existing.rssi) existing.rssi = rssi
            }
            return
        }

        if (subtype == 0 || subtype == 2) {
            val device = deviceMap.getOrPut(addr2) { MutableDevice(addr2) }
            device.packets++
            device.lastSeen = System.currentTimeMillis()
            if (rssi > device.rssi) device.rssi = rssi
            device.kind = classify(addr2)
            device.vendor = vendorOf(addr2)
            tags["ssid"]?.takeIf { it.isNotBlank() }?.let { device.probes += it }
        }
    }

    private fun handleData(
        packet: ByteArray,
        frame: Int,
        addr1: String,
        addr2: String,
        headerLength: Int,
        rssi: Int,
    ) {
        val device = deviceMap.getOrPut(addr2) { MutableDevice(addr2) }
        device.packets++
        device.lastSeen = System.currentTimeMillis()
        if (rssi > device.rssi) device.rssi = rssi
        if (device.kind == Kind.UNKNOWN) {
            device.kind = classify(addr2)
            device.vendor = vendorOf(addr2)
        }

        // LLC/SNAP: aa aa 03 00 00 00 <ethertype>
        var offset = frame + 24
        if (packet.size < offset + 8) return
        if ((packet[offset].toInt() and 0xFF) != 0xAA) return
        val etherType = ((packet[offset + 6].toInt() and 0xFF) shl 8) or
            (packet[offset + 7].toInt() and 0xFF)
        offset += 8

        if (etherType == 0x888E) {
            handleEapol(packet, offset, addr1, addr2)
            return
        }
        if (etherType != 0x0800) return
        if (packet.size < offset + 20) return
        val version = (packet[offset].toInt() and 0xF0) shr 4
        if (version != 4) return
        val protocol = packet[offset + 9].toInt() and 0xFF
        if (protocol != 17) return   // UDP only
        val ihl = (packet[offset].toInt() and 0x0F) * 4
        if (ihl < 20) return
        val udpOffset = offset + ihl
        if (packet.size < udpOffset + 8) return
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[udpOffset + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val dnsOffset = udpOffset + 8
        val host = parseDnsQuestion(packet, dnsOffset) ?: return
        synchronized(dnsList) {
            if (dnsList.size < 800 && dnsList.none { it.query == host && it.client == addr2 }) {
                dnsList += DnsHit(addr2, host, System.currentTimeMillis())
            }
        }
    }

    /**
     * EAPOL-Key frames carry the 4-way handshake. Recording which of the four messages a
     * pair has produced, plus the PMKID out of message 1, is what turns the pcap into
     * something hashcat can attack.
     */
    private fun handleEapol(packet: ByteArray, start: Int, addr1: String, addr2: String) {
        if (packet.size < start + 4) return
        val type = packet[start + 1].toInt() and 0xFF
        if (type != 3) return                       // 3 = EAPOL-Key
        val keyOffset = start + 4
        if (packet.size < keyOffset + 99) return
        val keyInfo = ((packet[keyOffset + 1].toInt() and 0xFF) shl 8) or
            (packet[keyOffset + 2].toInt() and 0xFF)
        val pairwise = keyInfo and 0x0008 != 0
        if (!pairwise) return
        val ack = keyInfo and 0x0080 != 0
        val mic = keyInfo and 0x0100 != 0
        val secure = keyInfo and 0x0200 != 0
        val install = keyInfo and 0x0040 != 0

        val message = when {
            ack && !mic -> 1
            mic && !ack && !secure && !install -> 2
            mic && ack && install -> 3
            mic && !ack && secure -> 4
            else -> 0
        }
        if (message == 0) return

        val keyDataLength = ((packet[keyOffset + 97].toInt() and 0xFF) shl 8) or
            (packet[keyOffset + 98].toInt() and 0xFF)
        var pmkid: String? = null
        if (message == 1 && keyDataLength >= 20 && packet.size >= keyOffset + 99 + keyDataLength) {
            var index = keyOffset + 99
            val end = index + keyDataLength
            while (index + 6 < end) {
                val elementId = packet[index].toInt() and 0xFF
                val length = packet[index + 1].toInt() and 0xFF
                if (elementId == 0xDD && length >= 4 &&
                    packet[index + 2].toInt() == 0x00 &&
                    packet[index + 3].toInt() == 0x0F &&
                    packet[index + 4].toInt() == 0xAC.toInt() &&
                    packet[index + 5].toInt() == 0x04
                ) {
                    val valueLength = length - 4
                    if (valueLength >= 16 && index + 6 + 16 <= packet.size) {
                        pmkid = (0 until 16).joinToString("") {
                            "%02x".format(packet[index + 6 + it].toInt() and 0xFF)
                        }
                    }
                    break
                }
                index += 2 + length
            }
        }

        val key = "$addr1|$addr2"
        synchronized(handshakeMap) {
            val entry = handshakeMap.getOrPut(key) {
                MutableHandshake(addr1, addr2)
            }
            entry.messages += message
            entry.lastSeen = System.currentTimeMillis()
            if (pmkid != null && entry.pmkid == null) {
                entry.pmkid = pmkid
                append("PMKID captured for $addr1 from $addr2")
            }
            _handshakes.value = handshakeMap.values.map {
                Handshake(
                    bssid = it.bssid,
                    station = it.station,
                    messages = it.messages.toSet(),
                    pmkid = it.pmkid,
                    lastSeen = it.lastSeen,
                )
            }.filter { it.complete || it.pmkid != null }.sortedByDescending { it.lastSeen }
        }
    }

    private fun parseTags(packet: ByteArray, start: Int, limit: Int): Map<String, String> {        val out = linkedMapOf<String, String>()
        var index = start
        var guard = 0
        while (index + 1 < packet.size && index < limit && guard++ < 64) {
            val id = packet[index].toInt() and 0xFF
            val length = packet[index + 1].toInt() and 0xFF
            if (index + 2 + length > packet.size) break
            when (id) {
                0 -> out["ssid"] = String(packet, index + 2, length, Charsets.UTF_8)
                3 -> if (length >= 1) out["channel"] = (packet[index + 2].toInt() and 0xFF).toString()
                48 -> out["privacy"] = "1"   // RSN
                221 -> {
                    // WPS vendor IE: 00 50 f2 04
                    if (length >= 4 &&
                        packet[index + 2].toInt() == 0x00 &&
                        packet[index + 3].toInt() == 0x50 &&
                        packet[index + 4].toInt() == 0xF2.toInt() &&
                        packet[index + 5].toInt() == 0x04
                    ) {
                        out["wps"] = "1"
                    }
                }
            }
            index += 2 + length
        }
        // WEP privacy bit lives in the capability field, not in a tag.
        return out
    }

    private fun parseDnsQuestion(packet: ByteArray, start: Int): String? {
        if (packet.size < start + 13) return null
        val flags = ((packet[start + 2].toInt() and 0xFF) shl 8) or (packet[start + 3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return null    // a response, not a query
        val questions = ((packet[start + 4].toInt() and 0xFF) shl 8) or (packet[start + 5].toInt() and 0xFF)
        if (questions < 1) return null
        val out = StringBuilder()
        var index = start + 12
        var guard = 0
        while (index < packet.size && guard++ < 64) {
            val length = packet[index].toInt() and 0xFF
            if (length == 0) break
            if (length > 63 || index + 1 + length > packet.size) return null
            if (out.isNotEmpty()) out.append('.')
            out.append(String(packet, index + 1, length, Charsets.US_ASCII))
            index += 1 + length
        }
        return out.toString().ifBlank { null }
    }

    private fun readRadiotapRssi(packet: ByteArray, radiotapLength: Int): Int {
        if (radiotapLength < 8) return -100
        val present = (packet[4].toInt() and 0xFF) or
            ((packet[5].toInt() and 0xFF) shl 8) or
            ((packet[6].toInt() and 0xFF) shl 16) or
            ((packet[7].toInt() and 0xFF) shl 24)
        // Bit 5 is the antenna signal (dBm), stored after the 8-byte header.
        return if (present and (1 shl 5) != 0 && packet.size > 8) {
            packet[8].toInt()
        } else {
            -100
        }
    }

    private fun mac(packet: ByteArray, offset: Int): String {
        if (offset + 6 > packet.size) return "00:00:00:00:00:00"
        return (0 until 6).joinToString(":") { "%02x".format(packet[offset + it].toInt() and 0xFF) }
    }

    private fun publish() {
        _devices.value = deviceMap.values.map {
            Device(
                mac = it.mac,
                kind = it.kind,
                vendor = it.vendor,
                probes = it.probes.toSet(),
                rssi = it.rssi,
                packets = it.packets,
                lastSeen = it.lastSeen,
                firstSeen = it.firstSeen,
            )
        }.sortedByDescending { it.rssi }

        _aps.value = apMap.values.map {
            AccessPoint(it.bssid, it.ssid, it.channel, it.rssi, it.encrypted, it.wps, it.seen)
        }.sortedByDescending { it.rssi }

        _dns.value = synchronized(dnsList) { dnsList.toList() }
    }

    private fun classify(mac: String): Kind {
        val vendor = vendorOf(mac)
        return when {
            vendor.contains("Apple") || vendor.contains("Samsung") ||
                vendor.contains("Xiaomi") || vendor.contains("Huawei") ||
                vendor.contains("OnePlus") || vendor.contains("Google") -> Kind.PHONE
            vendor.contains("Intel") || vendor.contains("Dell") ||
                vendor.contains("Lenovo") || vendor.contains("HP") ||
                vendor.contains("ASUS") || vendor.contains("Acer") -> Kind.LAPTOP
            vendor.contains("Espressif") || vendor.contains("Tuya") ||
                vendor.contains("Realtek") || vendor.contains("Sonos") -> Kind.IOT
            else -> Kind.UNKNOWN
        }
    }

    private fun vendorOf(mac: String): String {
        val prefix = mac.uppercase().take(8)
        return OuiTable.entries[prefix] ?: "unknown"
    }

    private fun shell(command: String): String =
        runCatching { RootUtils.executeRootCommand(command).second }.getOrDefault("")

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(160)
    }

    /** A short OUI table for the vendors that matter when classifying what is in range. */
    private object OuiTable {
        val entries: Map<String, String> = mapOf(
            "00:1B:63" to "Apple", "3C:2E:FF" to "Apple", "A4:83:E7" to "Apple",
            "F0:18:98" to "Apple", "8C:85:90" to "Apple", "DC:A9:04" to "Apple",
            "00:1E:C2" to "Apple", "AC:BC:32" to "Apple",
            "00:12:FB" to "Samsung", "34:23:BA" to "Samsung", "8C:77:12" to "Samsung",
            "5C:0A:5B" to "Samsung", "B4:3A:28" to "Samsung",
            "64:CC:2E" to "Xiaomi", "34:CE:00" to "Xiaomi", "78:11:DC" to "Xiaomi",
            "F8:A4:5F" to "Huawei", "00:E0:FC" to "Huawei", "48:DB:50" to "Huawei",
            "C0:EE:FB" to "OnePlus", "94:65:2D" to "OnePlus",
            "F4:F5:D8" to "Google", "3C:5A:B4" to "Google", "A4:77:33" to "Google",
            "00:1B:21" to "Intel", "3C:A9:F4" to "Intel", "94:65:9C" to "Intel",
            "00:14:22" to "Dell", "B8:2A:72" to "Dell", "18:DB:F2" to "Dell",
            "00:1E:37" to "Lenovo", "28:D2:44" to "Lenovo",
            "3C:52:82" to "HP", "00:1B:78" to "HP",
            "2C:56:DC" to "ASUS", "1C:87:2C" to "ASUS",
            "24:0A:C4" to "Espressif", "30:AE:A4" to "Espressif", "84:F3:EB" to "Espressif",
            "8C:AA:B5" to "Espressif", "CC:50:E3" to "Espressif",
            "50:02:91" to "Espressif", "7C:9E:BD" to "Espressif",
            "18:FE:34" to "Espressif", "A0:20:A6" to "Espressif",
            "00:1D:AA" to "Sonos", "34:7E:5C" to "Sonos", "B8:E9:37" to "Sonos",
            "D8:3A:DD" to "Raspberry Pi", "B8:27:EB" to "Raspberry Pi",
            "DC:A6:32" to "Raspberry Pi", "E4:5F:01" to "Raspberry Pi",
            "00:17:88" to "Philips Hue", "EC:B5:FA" to "Philips Hue",
            "00:04:4B" to "Nvidia", "48:B0:2D" to "Nvidia",
            "00:0E:58" to "Sonos", "00:1F:3C" to "Intel",
        )
    }
}
