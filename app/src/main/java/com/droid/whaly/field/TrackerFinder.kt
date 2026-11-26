package com.droid.whaly.field

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hot/cold tracker finder.
 *
 * The proximity alarm answers "did it leave". This answers "where is it". A single device is
 * selected by address, then the tone pitch and the beep rate both follow RSSI, so the phone
 * can be swept across a room without looking at the screen.
 *
 * RSSI is not distance — it is path loss, and a body between the phone and the tag drops it
 * 10-20 dB. The way to use it is to move slowly and watch the trend, not the number.
 */
object TrackerFinder {

    data class Target(val address: String, val name: String, val rssi: Int)

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: StateFlow<List<Target>> = _targets.asStateFlow()
    private val _tracking = MutableStateFlow(false)
    val tracking: StateFlow<Boolean> = _tracking.asStateFlow()
    private val _watched = MutableStateFlow<String?>(null)
    val watched: StateFlow<String?> = _watched.asStateFlow()
    private val _rssi = MutableStateFlow(-127)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val active = AtomicBoolean(false)
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var tone: ToneGenerator? = null
    private var lastBeep = 0L
    private var lastSeen = 0L

    @SuppressLint("MissingPermission")
    private fun bleScanner(): android.bluetooth.le.BluetoothLeScanner? = try {
        BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
    } catch (_: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        val scanner = bleScanner() ?: run {
            _status.value = "Bluetooth is off or unavailable"
            return
        }
        this.scanner = scanner
        _targets.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, discoveryCallback) }
        _status.value = "Scanning"
        Thread({
            try {
                Thread.sleep(9000)
            } catch (_: InterruptedException) {
            }
            runCatching { scanner.stopScan(discoveryCallback) }
            if (_status.value == "Scanning") _status.value = "Scan complete"
        }, "whaly-tracker-scan").apply { isDaemon = true; start() }
    }

    private val discoveryCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = runCatching { device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: ""
            val entry = Target(device.address, name, result.rssi)
            _targets.value = (_targets.value.filterNot { it.address == entry.address } + entry)
                .sortedByDescending { it.rssi }
                .take(60)
        }
    }

    @SuppressLint("MissingPermission")
    fun watch(address: String) {
        val scanner = bleScanner() ?: run {
            _status.value = "Bluetooth is off"
            return
        }
        if (!active.compareAndSet(false, true)) return
        _watched.value = address
        _tracking.value = true
        _rssi.value = -127
        lastSeen = 0L
        lastBeep = 0L
        _status.value = "Tracking $address"
        runCatching { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device?.address != address) return
                lastSeen = System.currentTimeMillis()
                _rssi.value = result.rssi
                // -40 dBm is on top of it, -100 is the far corner of the room.
                val closeness = ((result.rssi + 100) / 60f).coerceIn(0f, 1f)
                val now = System.currentTimeMillis()
                val gap = (900 - closeness * 800).toLong().coerceAtLeast(90)
                if (now - lastBeep >= gap) {
                    lastBeep = now
                    runCatching {
                        tone?.startTone(
                            if (closeness > 0.66f) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                            else ToneGenerator.TONE_PROP_BEEP,
                            60,
                        )
                    }
                }
            }
        }
        runCatching { scanner.startScan(null, settings, callback) }

        Thread({
            while (active.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                if (lastSeen != 0L && System.currentTimeMillis() - lastSeen > 10000) {
                    _status.value = "No advertisement for 10 s — out of range or asleep"
                }
            }
            runCatching { scanner.stopScan(callback) }
        }, "whaly-tracker-watch").apply { isDaemon = true; start() }
    }

    fun stop() {
        active.set(false)
        _tracking.value = false
        _watched.value = null
        runCatching { tone?.release() }
        tone = null
        _status.value = "Stopped"
    }
}
