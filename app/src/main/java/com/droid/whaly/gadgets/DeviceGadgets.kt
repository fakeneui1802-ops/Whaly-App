package com.droid.whaly.gadgets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A minimal RFC 6455 client. The app already depends on the Ktor server side, which cannot
 * act as a client, and pulling OkHttp in for three screens is not worth the APK.
 */
internal class MiniWebSocket(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val secure: Boolean,
    private val onMessage: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var open = false
    private var reader: Thread? = null

    fun connect(timeoutMs: Int = 4000): Boolean {
        return try {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = 15000
        val sock: Socket = if (secure) {
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
            }
            context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        } else {
            raw
        }
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        sock.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        sock.getOutputStream().flush()

        val stream = sock.getInputStream()
        val header = StringBuilder()
        while (true) {
            val byte = stream.read()
            if (byte < 0) return false
            header.append(byte.toChar())
            if (header.endsWith("\r\n\r\n")) break
            if (header.length > 8192) return false
        }
        if (!header.contains("101")) return false

        socket = sock
        input = stream
        output = sock.getOutputStream()
        open = true
        reader = Thread({
            try {
                while (open) {
                    val frame = readFrame() ?: break
                    if (frame.isNotEmpty()) onMessage(frame)
                }
            } catch (_: Exception) {
            }
            open = false
            onClosed("closed")
        }, "whaly-ws-reader").apply { isDaemon = true; start() }
        true
    } catch (error: Exception) {
        onClosed(error.message ?: "connect failed")
        false
    }
    }

    fun send(text: String): Boolean {
        if (!open) return false
        return try {
            val payload = text.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            out.write(0x81)                                  // FIN + text
            when {
                payload.size < 126 -> out.write(0x80 or payload.size)
                payload.size < 65536 -> {
                    out.write(0x80 or 126)
                    out.write(payload.size shr 8 and 0xFF)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) out.write((payload.size.toLong() shr shift).toInt() and 0xFF)
                }
            }
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            out.write(mask)
            payload.forEachIndexed { index, byte ->
                out.write((byte.toInt() xor mask[index % 4].toInt()) and 0xFF)
            }
            output?.write(out.toByteArray())
            output?.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        open = false
        runCatching { socket?.close() }
        reader?.interrupt()
        reader = null
        socket = null
    }

    fun isOpen(): Boolean = open

    private fun readFrame(): String? {
        val stream = input ?: return null
        val first = stream.read()
        if (first < 0) return null
        val opcode = first and 0x0F
        val second = stream.read()
        if (second < 0) return null
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = ((stream.read() shl 8) or stream.read()).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or stream.read().toLong() }
        }
        if (length > 1024 * 1024) return null
        val mask = if (masked) ByteArray(4).also { stream.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        stream.readFully(payload)
        if (mask != null) {
            payload.forEachIndexed { index, byte ->
                payload[index] = (byte.toInt() xor mask[index % 4].toInt()).toByte()
            }
        }
        return when (opcode) {
            0x1, 0x0 -> String(payload, Charsets.UTF_8)
            0x8 -> null
            0x9 -> {
                sendControl(0xA, payload)
                null
            }
            else -> ""
        }
    }

    private fun sendControl(opcode: Int, payload: ByteArray) {
        runCatching {
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            out.write(0x80 or payload.size)
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            out.write(mask)
            payload.forEachIndexed { index, byte ->
                out.write((byte.toInt() xor mask[index % 4].toInt()) and 0xFF)
            }
            output?.write(out.toByteArray())
            output?.flush()
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read <= 0) throw java.io.EOFException()
            offset += read
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

// ---------------------------------------------------------------------------------------------
// Samsung / LG television control
// ---------------------------------------------------------------------------------------------

/**
 * Drives a smart TV over the vendor's local WebSocket API.
 *
 * Samsung answers on 8001 with no pairing. LG answers on 3000 but shows a prompt on the
 * screen that someone has to accept once, which is why the pairing state is reported
 * instead of hidden.
 */
object TvHijack {

    enum class Key(val samsung: String, val lg: String) {
        POWER("KEY_POWER", "POWER"),
        VOLUME_UP("KEY_VOLUP", "VOLUMEUP"),
        VOLUME_DOWN("KEY_VOLDOWN", "VOLUMEDOWN"),
        MUTE("KEY_MUTE", "MUTE"),
        CHANNEL_UP("KEY_CHUP", "CHANNELUP"),
        CHANNEL_DOWN("KEY_CHDOWN", "CHANNELDOWN"),
        HOME("KEY_HOME", "HOME"),
        BACK("KEY_RETURN", "BACK"),
        INPUT("KEY_SOURCE", "INPUT_HUB"),
    }

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var samsung: MiniWebSocket? = null
    private var lg: MiniWebSocket? = null
    private var target: String? = null

    fun disconnect() {
        samsung?.close()
        lg?.close()
        samsung = null
        lg = null
        target = null
        _connected.value = false
        _status.value = "Disconnected"
    }

    suspend fun connectSamsung(ip: String): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        val name = Base64.getEncoder().encodeToString("Whaly".toByteArray())
        val ws = MiniWebSocket(
            host = ip,
            port = 8001,
            path = "/api/v2/channels/samsung.remote.control?name=$name",
            secure = false,
            onMessage = { append("samsung: ${it.take(120)}") },
            onClosed = { _connected.value = false; _status.value = it },
        )
        if (!ws.connect()) {
            append("$ip:8001 refused — the TV may need to allow the connection on screen")
            _status.value = "Refused"
            return@withContext false
        }
        samsung = ws
        target = ip
        _connected.value = true
        _status.value = "Samsung $ip connected"
        append("Samsung $ip connected")
        true
    }

    suspend fun connectLg(ip: String, clientKey: String? = null): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        val ws = MiniWebSocket(
            host = ip,
            port = 3000,
            path = "/",
            secure = true,
            onMessage = { message ->
                append("lg: ${message.take(160)}")
                if (message.contains("client-key")) {
                    _status.value = "Paired — key received"
                }
            },
            onClosed = { _connected.value = false },
        )
        if (!ws.connect()) {
            append("$ip:3000 refused")
            _status.value = "Refused"
            return@withContext false
        }
        val register = buildString {
            append("{\"type\":\"register\",\"id\":\"whaly\",\"payload\":{")
            append("\"forcePairing\":false,\"pairingType\":\"PROMPT\",")
            append("\"client-key\":").append(clientKey?.let { "\"$it\"" } ?: "null").append(",")
            append("\"manifest\":{")
            append("\"menu\":{\"label\":\"Whaly\"},")
            append("\"volume\":{\"label\":\"Volume\"},")
            append("\"channel\":{\"label\":\"Channel\"}")
            append("}}}")
        }
        ws.send(register)
        lg = ws
        target = ip
        _connected.value = true
        _status.value = "LG $ip — accept the prompt on the TV"
        append("LG $ip register sent, waiting for the on-screen prompt")
        true
    }

    fun press(key: Key): Boolean {
        val samsungSocket = samsung
        if (samsungSocket != null && samsungSocket.isOpen()) {
            val command = buildString {
                append("{\"method\":\"ms.remote.control\",\"params\":{")
                append("\"Cmd\":\"Click\",")
                append("\"DataOfCmd\":\"").append(key.samsung).append("\",")
                append("\"Option\":\"false\",")
                append("\"TypeOfRemote\":\"SendRemoteKey\"}}")
            }
            val ok = samsungSocket.send(command)
            append("samsung ${key.name} → $ok")
            return ok
        }
        val lgSocket = lg
        if (lgSocket != null && lgSocket.isOpen()) {
            val uri = "ssap://media.controls/${key.name.lowercase()}"
            val endpoint = when (key) {
                Key.VOLUME_UP -> "ssap://audio/volumeUp"
                Key.VOLUME_DOWN -> "ssap://audio/volumeDown"
                Key.MUTE -> "ssap://audio/setMute"
                Key.CHANNEL_UP -> "ssap://tv/channelUp"
                Key.CHANNEL_DOWN -> "ssap://tv/channelDown"
                Key.HOME -> "ssap://system/turnOff"
                Key.BACK -> "ssap://com.webos.applicationManager/getForegroundAppInfo"
                Key.INPUT -> "ssap://tv/switchInput"
                else -> uri
            }
            val command = "{\"id\":\"whaly\",\"type\":\"request\",\"uri\":\"$endpoint\"}"
            val ok = lgSocket.send(command)
            append("lg ${key.name} → $ok")
            return ok
        }
        append("not connected")
        return false
    }

    /** Repeats one key so a volume press becomes a hold. */
    suspend fun hold(key: Key, times: Int = 20, gapMs: Long = 180L) = withContext(Dispatchers.IO) {
        repeat(times) {
            if (!press(key)) return@withContext
            try {
                Thread.sleep(gapMs)
            } catch (_: InterruptedException) {
                return@withContext
            }
        }
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }
}

// ---------------------------------------------------------------------------------------------
// Sonos / UPnP speaker takeover
// ---------------------------------------------------------------------------------------------

object SonosTakeover {

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _speakers = MutableStateFlow<List<String>>(emptyList())
    val speakers: StateFlow<List<String>> = _speakers.asStateFlow()

    private const val PORT = 1400

    suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        val search = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        val found = linkedSetOf<String>()
        runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 900
                socket.send(
                    java.net.DatagramPacket(
                        search,
                        search.size,
                        java.net.InetAddress.getByName("239.255.255.250"),
                        1900,
                    ),
                )
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val location = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", true) }
                        ?.substringAfter(':')?.trim()
                    if (location != null) found += location.substringAfter("//").substringBefore(':')
                }
            }
        }
        _speakers.value = found.toList()
        append("${found.size} speaker(s)")
        found.toList()
    }

    suspend fun setVolume(ip: String, volume: Int): Boolean = withContext(Dispatchers.IO) {
        val body = soap(
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
        )
        post(ip, "/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume", body)
    }

    suspend fun play(ip: String, url: String): Boolean = withContext(Dispatchers.IO) {
        soap(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>$url</CurrentURI><CurrentURIMetaData></CurrentURI" + "MetaData>",
        ).let { }
        val setUri = soap(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>$url</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>",
        )
        if (!post(ip, "/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", setUri)) {
            return@withContext false
        }
        val play = soap(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>",
        )
        post(ip, "/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1#Play", play)
    }

    suspend fun pause(ip: String): Boolean = withContext(Dispatchers.IO) {
        val body = soap(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Pause",
            "<InstanceID>0</InstanceID>",
        )
        post(ip, "/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1#Pause", body)
    }

    private fun soap(service: String, action: String, inner: String): String = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body><u:$action xmlns:u="$service">$inner</u:$action></s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun post(ip: String, path: String, soapAction: String, body: String): Boolean = try {
        val connection = URL("http://$ip:$PORT$path").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2000
        connection.readTimeout = 2500
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
        connection.outputStream.write(body.toByteArray(Charsets.UTF_8))
        val code = connection.responseCode
        append("$ip $soapAction → $code")
        code in 200..299
    } catch (error: Exception) {
        append("$ip $soapAction failed: ${error.message}")
        false
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(100)
    }
}

// ---------------------------------------------------------------------------------------------
// Philips Hue
// ---------------------------------------------------------------------------------------------

object HueRave {

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _bridges = MutableStateFlow<List<String>>(emptyList())
    val bridges: StateFlow<List<String>> = _bridges.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var username: String? = null
    private var bridge: String? = null
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        runCatching {
            val connection = URL("https://discovery.meethue.com").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val text = connection.inputStream.bufferedReader().readText()
            Regex("\"internalipaddress\"\\s*:\\s*\"([^\"]+)\"").findAll(text).forEach {
                found += it.groupValues[1]
            }
        }
        _bridges.value = found.toList()
        append("${found.size} bridge(s) from discovery")
        found.toList()
    }

    /** The bridge only issues a username while its physical link button is pressed. */
    suspend fun pair(ip: String): String? = withContext(Dispatchers.IO) {
        val body = "{\"devicetype\":\"whaly#android\"}"
        val response = put("https://$ip/api", body) ?: return@withContext null
        if (response.contains("link button not pressed")) {
            append("press the link button on the bridge, then pair again")
            _status.value = "Press the link button"
            return@withContext null
        }
        val match = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(response)
        if (match == null) {
            append("pair failed: ${response.take(120)}")
            return@withContext null
        }
        username = match.groupValues[1]
        bridge = ip
        _status.value = "Paired"
        append("paired as ${match.groupValues[1]}")
        match.groupValues[1]
    }

    fun stop() {
        active.set(false)
        _running.value = false
    }

    /** Cycles every light through the spectrum at a fixed rate. */
    fun rave(hz: Int = 6) {
        val ip = bridge ?: run {
            append("pair with a bridge first")
            return
        }
        val user = username ?: run {
            append("pair with a bridge first")
            return
        }
        if (!active.compareAndSet(false, true)) return
        _running.value = true
        Thread({
            var hue = 0
            while (active.get()) {
                hue = (hue + 40) % 65536
                val body = "{\"on\":true,\"hue\":$hue,\"sat\":254,\"bri\":254}"
                runCatching {
                    val connection = URL("https://$ip/api/$user/groups/0/action")
                        .openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "PUT"
                    connection.connectTimeout = 1500
                    connection.readTimeout = 1500
                    connection.doOutput = true
                    connection.outputStream.write(body.toByteArray())
                    connection.responseCode
                }
                try {
                    Thread.sleep((1000L / hz.coerceIn(1, 20)))
                } catch (_: InterruptedException) {
                    break
                }
            }
            _running.value = false
            active.set(false)
        }, "whaly-hue-rave").start()
    }

    suspend fun allOff(): Boolean = withContext(Dispatchers.IO) {
        val ip = bridge ?: return@withContext false
        val user = username ?: return@withContext false
        put("https://$ip/api/$user/groups/0/action", "{\"on\":false}") != null
    }

    private fun put(url: String, body: String): String? = try {
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "PUT"
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.write(body.toByteArray())
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        append("PUT $code")
        text
    } catch (error: Exception) {
        append("bridge error: ${error.message}")
        null
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(100)
    }
}
