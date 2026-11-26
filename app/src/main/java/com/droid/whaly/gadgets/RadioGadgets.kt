package com.droid.whaly.gadgets

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.ParcelUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------------------------
// BLE MIDI flood
// ---------------------------------------------------------------------------------------------

/**
 * Advertises the BLE-MIDI service. Every music app that scans for controllers picks the
 * entry up, and none of them can connect because there is no MIDI characteristic behind it.
 */
object BleMidiFlood {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val active = AtomicBoolean(false)
    private val midiUuid: UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")

    fun stop() {
        active.set(false)
        _running.value = false
        _status.value = "Stopped"
    }

    @SuppressLint("MissingPermission")
    fun start(names: List<String>, rotateMs: Long = 800L) {
        if (!active.compareAndSet(false, true)) return
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: SecurityException) {
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            active.set(false)
            _status.value = "Bluetooth is off"
            return
        }
        val advertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (_: SecurityException) {
            null
        }
        if (advertiser == null) {
            active.set(false)
            _status.value = "This phone cannot advertise"
            return
        }
        val pool = names.ifEmpty { listOf("MIDI Controller", "Piano", "Drum Pad", "Whaly MIDI") }
        _running.value = true
        _count.value = 0
        _status.value = "BLE MIDI advertising"
        val originalName = runCatching { adapter.name }.getOrNull()

        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                _status.value = "Advertise failed ($errorCode)"
                active.set(false)
                _running.value = false
            }
        }

        Thread({
            var index = 0
            while (active.get()) {
                val name = pool[index % pool.size]
                runCatching { adapter.name = name }
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .addServiceUuid(ParcelUuid(midiUuid))
                    .build()
                try {
                    advertiser.stopAdvertising(callback)
                    advertiser.startAdvertising(
                        AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                            .setConnectable(false)
                            .setTimeout(0)
                            .build(),
                        data,
                        callback,
                    )
                    _count.value = _count.value + 1
                } catch (_: Exception) {
                    active.set(false)
                    _running.value = false
                    break
                }
                index++
                try {
                    Thread.sleep(rotateMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
            runCatching { advertiser.stopAdvertising(callback) }
            if (originalName != null) runCatching { adapter.name = originalName }
        }, "whaly-ble-midi").start()
    }
}

// ---------------------------------------------------------------------------------------------
// BLE proximity alarm
// ---------------------------------------------------------------------------------------------

/**
 * The anti-theft gadget. Watches one device by address and beeps the moment its RSSI drops
 * past a threshold or it stops being seen, which is what happens when something is picked
 * up and carried out of range.
 */
object ProximityAlarm {

    data class Target(val address: String, val name: String, val rssi: Int)

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: StateFlow<List<Target>> = _targets.asStateFlow()
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _distance = MutableStateFlow(-100)
    val distance: StateFlow<Int> = _distance.asStateFlow()

    private val active = AtomicBoolean(false)
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var watched: String? = null
    private var threshold = -75
    private var lastSeen = 0L
    private var tone: ToneGenerator? = null

    @SuppressLint("MissingPermission")
    fun scan() {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: SecurityException) {
            null
        }
        val ble = try {
            adapter?.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        } ?: return
        scanner = ble
        _targets.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            ble.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            _status.value = "Scan permission denied"
            return
        }
        Thread({
            try {
                Thread.sleep(8000)
            } catch (_: InterruptedException) {
            }
            runCatching { ble.stopScan(scanCallback) }
        }, "whaly-proximity-scan").start()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = runCatching { device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: ""
            val entry = Target(device.address, name, result.rssi)
            _targets.value = (_targets.value.filterNot { it.address == entry.address } + entry)
                .sortedByDescending { it.rssi }
                .take(40)
        }
    }

    @SuppressLint("MissingPermission")
    fun arm(address: String, thresholdRssi: Int = -75) {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: SecurityException) {
            null
        } ?: return
        val ble = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        } ?: return
        if (!active.compareAndSet(false, true)) return

        watched = address
        threshold = thresholdRssi
        lastSeen = System.currentTimeMillis()
        _armed.value = true
        _status.value = "Watching $address, trip at $thresholdRssi dBm"
        scanner = ble

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device?.address != address) return
                lastSeen = System.currentTimeMillis()
                _distance.value = result.rssi
                if (result.rssi < threshold) trip("signal dropped to ${result.rssi} dBm")
            }
        }
        try {
            ble.startScan(null, settings, callback)
        } catch (_: SecurityException) {
            _status.value = "Scan permission denied"
            disarm()
            return
        }

        // A device that simply stops advertising has also left.
        Thread({
            while (active.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                if (System.currentTimeMillis() - lastSeen > 12000) {
                    trip("no advertisement for 12 s")
                }
            }
            runCatching { ble.stopScan(callback) }
        }, "whaly-proximity-watch").start()
    }

    fun disarm() {
        active.set(false)
        _armed.value = false
        _status.value = "Disarmed"
        runCatching { tone?.release() }
        tone = null
    }

    private fun trip(reason: String) {
        if (!active.get()) return
        _status.value = "TRIPPED: $reason"
        active.set(false)
        _armed.value = false
        try {
            val generator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone = generator
            generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 4000)
        } catch (_: Exception) {
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DIAL launcher
// ---------------------------------------------------------------------------------------------

/**
 * Launches and closes apps on smart TVs and streaming sticks over DIAL, which is plain
 * HTTP on port 8008 and needs no authentication on most devices.
 */
object DialLauncher {

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** App names DIAL servers accept, per platform. */
    val apps: List<String> = listOf(
        "YouTube", "Netflix", "Amazon", "Spotify", "Plex", "Hulu",
        "Disney+", "Prime Video", "Twitch", "HBO Max",
    )

    suspend fun launch(ip: String, app: String): Boolean = withContext(Dispatchers.IO) {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\">")
            append("<name>").append(app).append("</name>")
            append("</service>")
        }
        val result = post(ip, "/apps/$app", body)
        append("$ip launch $app → ${if (result) "ok" else "refused"}")
        _status.value = if (result) "$app on $ip" else "refused"
        result
    }

    suspend fun stop(ip: String, app: String): Boolean = withContext(Dispatchers.IO) {
        val result = runCatching {
            val connection = URL("http://$ip:8008/apps/$app").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.responseCode in 200..299
        }.getOrDefault(false)
        append("$ip stop $app → $result")
        result
    }

    private fun post(ip: String, path: String, body: String): Boolean = try {
        val connection = URL("http://$ip:8008$path").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2000
        connection.readTimeout = 2500
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain; charset=\"utf-8\"")
        connection.outputStream.write(body.toByteArray(Charsets.UTF_8))
        connection.responseCode in 200..299
    } catch (error: Exception) {
        append("$ip: ${error.message}")
        false
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(100)
    }
}
