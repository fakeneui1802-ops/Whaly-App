package com.droid.whaly.gadgets

import android.content.Context
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * LAN-side phantom gadgets.
 *
 * None of these need root: they are ordinary multicast and unicast traffic on the network
 * the phone is already on. What they do is put devices on the network that do not exist,
 * which every other device in the room then tries to talk to.
 */
internal object LanBase {

    fun localIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name
            if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("ap")) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
            }
        }
        return null
    }

    /** Minimal DNS name encoder: length-prefixed labels then a root byte. */
    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trim('.').split('.').forEach { label ->
            if (label.isEmpty()) return@forEach
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size.coerceAtMost(63))
            out.write(bytes, 0, bytes.size.coerceAtMost(63))
        }
        out.write(0)
        return out.toByteArray()
    }

    fun u16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun u32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }
}

// ---------------------------------------------------------------------------------------------
// mDNS phantom services
// ---------------------------------------------------------------------------------------------

/**
 * Announces fake Bonjour services. iPhones, Macs, printers and smart TVs all browse mDNS
 * continuously, so a phantom printer or Apple TV shows up in their lists within seconds
 * and cannot be opened.
 */
object MdnsPhantom {

    enum class Service(val type: String, val label: String) {
        PRINTER("_ipp._tcp.local", "Printer"),
        AIRPRINT("_ipps._tcp.local", "AirPrint"),
        AIRPLAY("_airplay._tcp.local", "AirPlay TV"),
        RAOP("_raop._tcp.local", "AirPlay audio"),
        CHROMECAST("_googlecast._tcp.local", "Chromecast"),
        SPOTIFY("_spotify-connect._tcp.local", "Spotify Connect"),
        SMB("_smb._tcp.local", "File share"),
        HOMEKIT("_hap._tcp.local", "HomeKit accessory"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _sent = MutableStateFlow(0)
    val sent: StateFlow<Int> = _sent.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)
    private const val GROUP = "224.0.0.251"
    private const val PORT = 5353

    private val names = listOf(
        "HP OfficeJet Pro", "Canon MG3600", "Living Room TV", "Apple TV",
        "Kitchen Speaker", "NAS-2BAY", "Brother HL-L2350", "Bedroom HomePod",
        "Whaly Phantom", "Office Printer",
    )

    fun stop() {
        active.set(false)
        _running.value = false
    }

    fun start(services: List<Service>, intervalMs: Long = 900L) {
        if (!active.compareAndSet(false, true)) return
        val ip = LanBase.localIpv4()
        if (ip == null) {
            _log.value = listOf("No LAN address — join Wi-Fi first")
            active.set(false)
            return
        }
        _running.value = true
        _sent.value = 0
        _log.value = listOf("announcing ${services.size} service type(s) as $ip")

        Thread({
            DatagramSocket().use { socket ->
                socket.broadcast = true
                var counter = 0
                while (active.get()) {
                    services.forEach { service ->
                        val name = names[Random.nextInt(names.size)]
                        val packet = buildResponse(service, name, ip, counter)
                        runCatching {
                            socket.send(
                                DatagramPacket(
                                    packet,
                                    packet.size,
                                    InetAddress.getByName(GROUP),
                                    PORT,
                                ),
                            )
                        }
                        counter++
                    }
                    _sent.value = counter
                    if (counter % 40 == 0) {
                        _log.value = (_log.value + "$counter announcements").takeLast(60)
                    }
                    try {
                        Thread.sleep(intervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "whaly-mdns-phantom").start()
    }

    /**
     * One authoritative response with a PTR, an SRV, a TXT and an A record, which is the
     * minimum a browser needs to show an entry.
     */
    private fun buildResponse(service: Service, instance: String, ip: String, seed: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val host = "whaly-${seed % 40}.local"
        val instanceName = "$instance.${service.type}"
        val port = 1024 + (seed % 4000)

        LanBase.u16(out, 0)          // id
        LanBase.u16(out, 0x8400)     // response, authoritative
        LanBase.u16(out, 0)          // qdcount
        LanBase.u16(out, 4)          // ancount: PTR, SRV, TXT, A
        LanBase.u16(out, 0)          // nscount
        LanBase.u16(out, 0)          // arcount

        // PTR _service._tcp.local -> instance
        out.write(LanBase.encodeName(service.type))
        LanBase.u16(out, 12); LanBase.u16(out, 0x0001)
        LanBase.u32(out, 4500); LanBase.u16(out, 0)
        val ptrTarget = LanBase.encodeName(instanceName)
        LanBase.u16(out, ptrTarget.size)
        out.write(ptrTarget)

        // SRV instance -> host:port
        out.write(LanBase.encodeName(instanceName))
        LanBase.u16(out, 33); LanBase.u16(out, 0x8001)
        LanBase.u32(out, 120); LanBase.u16(out, 0)
        LanBase.u16(out, 0); LanBase.u16(out, 0); LanBase.u16(out, port)
        val srvTarget = LanBase.encodeName(host)
        LanBase.u16(out, srvTarget.size)
        out.write(srvTarget)

        // TXT
        out.write(LanBase.encodeName(instanceName))
        LanBase.u16(out, 16); LanBase.u16(out, 0x8001)
        LanBase.u32(out, 120); LanBase.u16(out, 0)
        val txt = buildString {
            append('\u0004').append("md=1")
            append('\u0007').append("model=X")
        }.toByteArray(Charsets.US_ASCII)
        LanBase.u16(out, txt.size)
        out.write(txt)

        // A host -> ip
        out.write(LanBase.encodeName(host))
        LanBase.u16(out, 1); LanBase.u16(out, 0x8001)
        LanBase.u32(out, 120); LanBase.u16(out, 4)
        InetAddress.getByName(ip).address.forEach { out.write(it.toInt()) }

        return out.toByteArray()
    }
}

// ---------------------------------------------------------------------------------------------
// SSDP phantom devices
// ---------------------------------------------------------------------------------------------

/**
 * Announces fake UPnP root devices. Windows Explorer shows them under Network, media
 * players list them as renderers, and none of them answer when opened.
 */
object SsdpPhantom {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _sent = MutableStateFlow(0)
    val sent: StateFlow<Int> = _sent.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)
    private const val GROUP = "239.255.255.250"
    private const val PORT = 1900

    private val deviceTypes = listOf(
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:device:MediaServer:1",
        "urn:schemas-upnp-org:device:Basic:1",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:dial-multiscreen-org:service:dial:1",
    )

    private val names = listOf(
        "Living Room TV", "Kitchen Display", "Office Router",
        "Whaly Media Server", "Bedroom Renderer", "Garage Gateway",
    )

    fun stop() {
        active.set(false)
        _running.value = false
    }

    fun start(intervalMs: Long = 700L) {
        if (!active.compareAndSet(false, true)) return
        val ip = LanBase.localIpv4()
        if (ip == null) {
            _log.value = listOf("No LAN address — join Wi-Fi first")
            active.set(false)
            return
        }
        _running.value = true
        _sent.value = 0
        _log.value = listOf("announcing phantom UPnP devices as $ip")

        Thread({
            DatagramSocket().use { socket ->
                socket.broadcast = true
                var counter = 0
                while (active.get()) {
                    val type = deviceTypes[counter % deviceTypes.size]
                    val name = names[counter % names.size]
                    val uuid = "uuid:whaly-%08x".format(counter * 2654435761L)
                    val message = buildString {
                        append("NOTIFY * HTTP/1.1\r\n")
                        append("HOST: $GROUP:$PORT\r\n")
                        append("NT: $type\r\n")
                        append("NTS: ssdp:alive\r\n")
                        append("USN: $uuid::$type\r\n")
                        append("CACHE-CONTROL: max-age=1800\r\n")
                        append("LOCATION: http://$ip:9/desc.xml\r\n")
                        append("SERVER: Linux/3.14 UPnP/1.0 $name\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    runCatching {
                        socket.send(
                            DatagramPacket(
                                message,
                                message.size,
                                InetAddress.getByName(GROUP),
                                PORT,
                            ),
                        )
                    }
                    counter++
                    _sent.value = counter
                    if (counter % 30 == 0) {
                        _log.value = (_log.value + "$counter notifications · $name").takeLast(60)
                    }
                    try {
                        Thread.sleep(intervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "whaly-ssdp-phantom").start()
    }
}

// ---------------------------------------------------------------------------------------------
// Wi-Fi probe flood
// ---------------------------------------------------------------------------------------------

/**
 * Fills the phone's saved-network list with hundreds of suggestions.
 *
 * Android probes for every network it knows about, and every probe request carries the
 * SSID in the clear. Scrapper running on another phone reads all of them. This is the
 * Flipper's probe flood, done through a public API instead of a monitor interface.
 */
object WifiProbeFlood {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _added = MutableStateFlow(0)
    val added: StateFlow<Int> = _added.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val names = listOf(
        "Free WiFi", "Airport_Free", "Starbucks", "McDonalds", "Hotel_Guest",
        "HOME-2.4G", "HOME-5G", "FRITZ!Box 7590", "TP-Link_5G", "NETGEAR",
        "AndroidAP", "iPhone", "Corp-Secure", "eduroam", "Whaly",
    )

    suspend fun flood(context: Context, count: Int = 120): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _log.value = listOf("Network suggestions need Android 10 or newer")
            return@withContext 0
        }
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifi == null) {
            _log.value = listOf("No Wi-Fi service")
            return@withContext 0
        }
        _running.value = true
        var total = 0
        val batch = mutableListOf<WifiNetworkSuggestion>()
        for (index in 0 until count) {
            val base = names[index % names.size]
            val suffix = index / names.size
            val ssid = if (suffix == 0) base else "$base-$suffix"
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase("whalywhaly")
                .build()
            batch += suggestion
            if (batch.size == 20 || index == count - 1) {
                val result = runCatching { wifi.addNetworkSuggestions(batch.toList()) }
                val status = result.getOrDefault(2)
                _log.value = (_log.value + "batch ${index / 20 + 1}: status $status").takeLast(80)
                if (status == android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    total += batch.size
                }
                _added.value = total
                batch.clear()
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                }
            }
        }
        _running.value = false
        _log.value = (_log.value + "done — $total suggestion(s) accepted").takeLast(80)
        total
    }

    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return@withContext false
        val suggestions = names.map {
            WifiNetworkSuggestion.Builder()
                .setSsid(it)
                .setWpa2Passphrase("whalywhaly")
                .build()
        }
        val removed = runCatching { wifi.removeNetworkSuggestions(suggestions) }
            .getOrDefault(android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL)
        _added.value = 0
        _log.value = (_log.value + "cleared: status $removed").takeLast(80)
        removed == android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }
}
