package com.droid.whaly.gadgets

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wake-on-LAN flood.
 *
 * A magic packet is six 0xFF bytes followed by the target MAC sixteen times, sent to UDP port
 * 9 or 7. Every sleeping machine on the segment that matches the MAC powers on. It needs no
 * root, no monitor mode and no handshake — it is a single broadcast datagram.
 *
 * MAC addresses come from the ARP table when it is readable. On builds where /proc/net/arp is
 * restricted, the list has to be entered by hand, which is why the manual field exists.
 */
object WakeBomb {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _sent = MutableStateFlow(0)
    val sent: StateFlow<Int> = _sent.asStateFlow()
    private val _targets = MutableStateFlow<List<String>>(emptyList())
    val targets: StateFlow<List<String>> = _targets.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(80)
    }

    /** /proc/net/arp is readable on many builds and refused on others. Both outcomes are fine. */
    fun scanArp(): List<String> {
        val macs = linkedSetOf<String>()
        runCatching {
            val file = File("/proc/net/arp")
            if (!file.canRead()) {
                append("/proc/net/arp is not readable on this build — add addresses by hand")
                return@runCatching
            }
            file.readLines().drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val mac = parts[3].uppercase()
                    if (mac != "00:00:00:00:00:00" && mac.count { it == ':' } == 5) macs += mac
                }
            }
        }
        _targets.value = macs.toList()
        append("${macs.size} address(es) from ARP")
        return macs.toList()
    }

    fun addManual(mac: String) {
        val normalised = mac.trim().uppercase()
        if (normalised.count { it == ':' } != 5) {
            append("not a MAC: $mac")
            return
        }
        _targets.value = (_targets.value + normalised).distinct()
        append("added $normalised")
    }

    fun clear() {
        _targets.value = emptyList()
        _sent.value = 0
    }

    fun stop() {
        active.set(false)
        worker?.interrupt()
        worker = null
        _running.value = false
    }

    fun start(context: Context, intervalSeconds: Int = 5, repeatCount: Int = 3) {
        if (_targets.value.isEmpty()) {
            scanArp()
        }
        if (_targets.value.isEmpty()) {
            append("no targets — ARP unreadable, add a MAC by hand")
            return
        }
        if (!active.compareAndSet(false, true)) return
        val gap = intervalSeconds.coerceIn(1, 600)
        val repeats = repeatCount.coerceIn(1, 50)
        _running.value = true
        append("waking ${_targets.value.size} host(s) every ${gap}s × $repeats")

        worker = Thread({
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val broadcast = runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()
                var count = 0
                while (active.get()) {
                    _targets.value.forEach { mac ->
                        val packet = magicPacket(mac) ?: return@forEach
                        listOf(9, 7).forEach { port ->
                            runCatching {
                                socket.send(DatagramPacket(packet, packet.size, broadcast, port))
                            }
                        }
                    }
                    count++
                    _sent.value = count
                    append("round $count → ${_targets.value.size} host(s)")
                    try {
                        Thread.sleep(gap * 1000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            active.set(false)
            _running.value = false
        }, "whaly-wake-bomb").apply { isDaemon = true; start() }
    }

    private fun magicPacket(mac: String): ByteArray? {
        val bytes = mac.split(':').mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (bytes.size != 6) return null
        val out = ByteArray(102)
        for (index in 0 until 6) out[index] = 0xFF.toByte()
        for (repeat in 0 until 16) {
            bytes.forEachIndexed { offset, byte -> out[6 + repeat * 6 + offset] = byte }
        }
        return out
    }
}
