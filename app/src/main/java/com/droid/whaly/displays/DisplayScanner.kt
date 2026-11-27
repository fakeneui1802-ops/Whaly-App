package com.droid.whaly.displays

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Display and whiteboard discovery.
 *
 * Three passes, each answering a different question:
 *
 * 1. **mDNS** — what advertises a service. Google Cast, AirPlay, RAOP, IPP, PJLink and the
 *    generic display types cover projectors, panels and whiteboards from Kindermann,
 *    Promethean, SMART, Clevertouch, Prowise, Epson, BenQ, Optoma, NEC and the rest.
 * 2. **SSDP** — what answers a UPnP M-SEARCH. MediaRenderer and Basic device replies carry
 *    a LOCATION header with the device description, which names the manufacturer.
 * 3. **BLE** — what is broadcasting a name that belongs to a display. Some panels expose a
 *    pairing service over BLE and never touch the LAN until paired.
 *
 * The LAN passes need Wi-Fi on the same network. The BLE pass works with Wi-Fi off.
 */
object DisplayScanner {

    enum class Kind(val label: String) {
        WHITEBOARD("whiteboard"),
        PROJECTOR("projector"),
        DISPLAY("display"),
        PRINTER("printer"),
        UNKNOWN("device"),
    }

    data class Found(
        val name: String,
        val kind: Kind,
        val host: String,
        val detail: String,
    )

    private val _results = MutableStateFlow<List<Found>>(emptyList())
    val results: StateFlow<List<Found>> = _results.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val running = AtomicBoolean(false)
    private var nsd: NsdManager? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null

    private val serviceTypes = listOf(
        "_googlecast._tcp.",
        "_airplay._tcp.",
        "_raop._tcp.",
        "_ipp._tcp.",
        "_printer._tcp.",
        "_pdl-datastream._tcp.",
        "_pjlink._tcp.",
        "_display._tcp.",
        "_http._tcp.",
        "_ssh._tcp.",
        "_smb._tcp.",
    )

    private val whiteboardHints = listOf(
        "kindermann", "promethean", "smart board", "smartboard", "clevertouch",
        "prowise", "newline", "iqboard", "vibe", "mimio", "starboard", "activpanel",
        "surface hub", "flip", "wbf", "whiteboard", "interactive display",
    )

    private val projectorHints = listOf(
        "epson", "benq", "optoma", "nec", "casio", "hitachi", "sony", "viewsonic",
        "vivitek", "christie", "barco", "panasonic", "pjlink", "projector", "beamer",
    )

    private val displayHints = listOf(
        "display", "panel", "signage", "bravia", "lg", "samsung", "philips", "sharp",
        "kindermann", "promethean", "clevertouch", "prowise", "newline",
    )

    private fun classify(text: String): Kind {
        val lower = text.lowercase()
        if (whiteboardHints.any { lower.contains(it) }) return Kind.WHITEBOARD
        if (projectorHints.any { lower.contains(it) }) return Kind.PROJECTOR
        if (lower.contains("ipp") || lower.contains("printer") || lower.contains("pdl")) return Kind.PRINTER
        if (displayHints.any { lower.contains(it) }) return Kind.DISPLAY
        return Kind.UNKNOWN
    }

    private fun add(found: Found) {
        val key = "${found.host}|${found.name}"
        val existing = _results.value
        if (existing.any { "${it.host}|${it.name}" == key }) return
        _results.value = (existing + found).sortedWith(
            compareBy<Found> { it.kind.ordinal }.thenBy { it.name.lowercase() },
        )
    }

    fun stop() {
        running.set(false)
        listeners.forEach { listener ->
            runCatching { nsd?.stopServiceDiscovery(listener) }
        }
        listeners.clear()
        runCatching { bleScanner?.stopScan(bleCallback) }
        bleScanner = null
        _scanning.value = false
        _status.value = "Stopped"
    }

    /** mDNS and SSDP. Requires Wi-Fi on the same network as the displays. */
    fun scanLan(context: Context) {
        if (!running.compareAndSet(false, true)) return
        _results.value = emptyList()
        _scanning.value = true
        _status.value = "mDNS browse, ${serviceTypes.size} service types"
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        nsd = manager
        if (manager == null) {
            _status.value = "No NsdManager on this device"
            _scanning.value = false
            running.set(false)
            return
        }

        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    val name = info.serviceName ?: ""
                    val host = info.host?.hostAddress ?: ""
                    val detail = "${info.serviceType} ${info.port}"
                    add(
                        Found(
                            name = name.ifBlank { "unnamed service" },
                            kind = classify("$name ${info.serviceType}"),
                            host = host,
                            detail = detail,
                        ),
                    )
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    _status.value = "mDNS $serviceType failed ($errorCode)"
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            listeners += listener
            runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        }

        ssdpProbe()
        _status.value = "LAN discovery running — ${_results.value.size} found"
    }

    private fun ssdpProbe() {
        Thread({
            val targets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:device:Basic:1",
                "ssdp:all",
            )
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 900
                    val group = InetAddress.getByName("239.255.255.250")
                    targets.forEach { target ->
                        val message = buildString {
                            append("M-SEARCH * HTTP/1.1\r\n")
                            append("HOST: 239.255.255.250:1900\r\n")
                            append("MAN: \"ssdp:discover\"\r\n")
                            append("MX: 2\r\n")
                            append("ST: $target\r\n")
                            append("\r\n")
                        }
                        val bytes = message.toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(group, 1900)))
                    }
                    val buffer = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + 3500
                    while (running.get() && System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                        if (!ok) break
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val headers = text.lineSequence()
                            .mapNotNull { line ->
                                val index = line.indexOf(':')
                                if (index <= 0) null
                                else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
                            }
                            .toMap()
                        val server = headers["server"] ?: ""
                        val location = headers["location"] ?: ""
                        val usn = headers["usn"] ?: ""
                        val host = packet.address?.hostAddress ?: ""
                        val name = server.ifBlank { usn }.ifBlank { location }.ifBlank { "UPnP device" }
                        add(
                            Found(
                                name = name,
                                kind = classify("$server $usn $location"),
                                host = host,
                                detail = location.ifBlank { usn },
                            ),
                        )
                    }
                }
            }
            if (_status.value.startsWith("LAN discovery")) {
                _status.value = "LAN discovery finished — ${_results.value.size} found"
            }
        }, "whaly-display-ssdp").apply { isDaemon = true; start() }
    }

    /** BLE name pass. Works with Wi-Fi off; needs Bluetooth on. */
    @SuppressLint("MissingPermission")
    fun scanBle() {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            _status.value = "Bluetooth is off or unavailable"
            return
        }
        bleScanner = scanner
        _scanning.value = true
        _status.value = "BLE name scan"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, bleCallback) }
        Thread({
            try {
                Thread.sleep(8000)
            } catch (_: InterruptedException) {
            }
            runCatching { scanner.stopScan(bleCallback) }
            _scanning.value = false
            _status.value = "BLE scan finished — ${_results.value.size} found"
        }, "whaly-display-ble").apply { isDaemon = true; start() }
    }

    private val bleCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = runCatching { result.device?.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: return
            if (name.isBlank()) return
            val kind = classify(name)
            if (kind == Kind.UNKNOWN) return
            add(
                Found(
                    name = name,
                    kind = kind,
                    host = result.device?.address ?: "",
                    detail = "BLE ${result.rssi} dBm",
                ),
            )
        }
    }
}
