package com.droid.whaly.active

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.droid.whaly.IrBackend
import com.droid.whaly.IrButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------------------------
// TV-B-Gone
// ---------------------------------------------------------------------------------------------

/**
 * Sends the power toggle for every major television family in one run, which is what the
 * Flipper's IR blaster does with its universal power file.
 *
 * Codes are encoded here rather than looked up, because the built-in remote database is
 * organised by model and this needs to fire blind at whatever is in the room.
 */
object TvBGone {

    data class BrandCode(val brand: String, val frequency: Int, val pattern: IntArray)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val active = AtomicBoolean(false)

    /** NEC: 9 ms lead, 4.5 ms space, 32 bits LSB-first, 560 µs per unit. */
    private fun nec(address: Int, command: Int, frequency: Int = 38000): IntArray {
        val out = ArrayList<Int>(70)
        out += 9000
        out += -4500
        fun bit(one: Boolean) {
            out += 560
            out += if (one) -1690 else -560
        }
        // Address low, address high, command, command inverted.
        for (index in 0 until 8) bit((address shr index) and 1 == 1)
        for (index in 8 until 16) bit((address shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 0)
        out += 560
        out += -40000
        return out.toIntArray()
    }

    /** Samsung32: same timing as NEC but the address is sent twice, not inverted. */
    private fun samsung(address: Int, command: Int): IntArray {
        val out = ArrayList<Int>(70)
        out += 4500
        out += -4500
        fun bit(one: Boolean) {
            out += 560
            out += if (one) -1690 else -560
        }
        for (index in 0 until 8) bit((address shr index) and 1 == 1)
        for (index in 8 until 16) bit((address shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 0)
        out += 560
        out += -40000
        return out.toIntArray()
    }

    /** Sony SIRC: 40 kHz, 2.4 ms lead, then 0.6/1.2 ms pulses for 0/1. */
    private fun sirc(command: Int, device: Int, bits: Int = 12): IntArray {
        val out = ArrayList<Int>(bits * 2 + 4)
        out += 2400
        out += -600
        for (index in 0 until bits) {
            val one = when {
                index < 7 -> (command shr index) and 1 == 1
                else -> (device shr (index - 7)) and 1 == 1
            }
            out += if (one) 1200 else 600
            out += -600
        }
        out += -25000
        return out.toIntArray()
    }

    /** Philips RC5: 36 kHz, 889 µs half-bits, Manchester, 14 bits. */
    private fun rc5(command: Int, address: Int, toggle: Boolean = false): IntArray {
        val bits = ArrayList<Boolean>(14)
        bits += true                       // start bit 1
        bits += !toggle                    // field bit (inverted toggle)
        for (index in 4 downTo 0) bits += (address shr index) and 1 == 1
        for (index in 5 downTo 0) bits += (command shr index) and 1 == 1

        val out = ArrayList<Int>(40)
        for (bit in bits) {
            // 1 is a low-to-high transition: space then pulse.
            if (bit) {
                out += -889
                out += 889
            } else {
                out += 889
                out += -889
            }
        }
        out += -89000
        return out.toIntArray()
    }

    val codes: List<BrandCode> by lazy {
        listOf(
            BrandCode("Samsung", 38000, samsung(0x0707, 0x02FD)),
            BrandCode("LG", 38000, nec(0x20DF, 0x10EF)),
            BrandCode("Vizio", 38000, nec(0x20DF, 0x10EF)),
            BrandCode("Toshiba", 38000, nec(0x40BF, 0x12ED)),
            BrandCode("Panasonic", 38000, nec(0x4004, 0x100C)),
            BrandCode("Sharp", 38000, nec(0x01FE, 0x40BF)),
            BrandCode("TCL", 38000, nec(0x04FB, 0x08F7)),
            BrandCode("Hisense", 38000, nec(0x00FF, 0x00FF)),
            BrandCode("Philips", 36000, rc5(0x0C, 0x00)),
            BrandCode("Grundig", 36000, rc5(0x0C, 0x00)),
            BrandCode("Sony", 40000, sirc(0x15, 0x01, 12)),
            BrandCode("Sony Bravia", 40000, sirc(0x2E9, 0x01, 15)),
            BrandCode("JVC", 38000, nec(0xC0F3, 0x00FF)),
            BrandCode("Roku TV", 38000, nec(0x5743, 0x00FF)),
            BrandCode("Insignia", 38000, nec(0x5743, 0x00FF)),
            BrandCode("Sceptre", 38000, nec(0x00FF, 0x00FF)),
            BrandCode("Element", 38000, nec(0x00FF, 0x00FF)),
            BrandCode("Westinghouse", 38000, nec(0x00FF, 0x00FF)),
        )
    }

    fun stop() {
        active.set(false)
        _running.value = false
    }

    /** Cycles every brand [rounds] times. Each code is a full power toggle. */
    fun fire(context: Context, rounds: Int = 2) {
        if (!active.compareAndSet(false, true)) return
        if (!IrBackend.isAvailable(context.applicationContext)) {
            active.set(false)
            _log.value = listOf(
                "No IR emitter on this phone. Plug a USB-C IR blaster or install an IR plugin.",
            )
            return
        }
        _running.value = true
        _log.value = emptyList()
        val appContext = context.applicationContext
        Thread({
            var sent = 0
            repeat(rounds.coerceIn(1, 5)) { round ->
                codes.forEachIndexed { position, code ->
                    if (!active.get()) return@Thread
                    _index.value = position
                    val ok = IrBackend.transmit(
                        appContext,
                        IrButton(name = code.brand, frequency = code.frequency, pattern = code.pattern),
                    )
                    sent++
                    _log.value = (_log.value + "${code.brand} ${if (ok) "sent" else "failed"}").takeLast(60)
                    try {
                        Thread.sleep(120)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                _log.value = (_log.value + "round ${round + 1}/$rounds complete — $sent codes").takeLast(60)
            }
            _running.value = false
            active.set(false)
            _log.value = (_log.value + "done").takeLast(60)
        }, "whaly-tvbgone").start()
    }
}

// ---------------------------------------------------------------------------------------------
// Tracker detector
// ---------------------------------------------------------------------------------------------

/**
 * The anti-stalking half of the tracker feature: listens for Find My, SmartTag and Tile
 * advertisements and flags anything that keeps showing up while you move, which is what a
 * tracker planted on you does and what a passing tag does not.
 */
object TrackerDetector {

    data class Sighting(
        val key: String,
        val ecosystem: String,
        val rssi: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val count: Int,
    ) {
        val following: Boolean get() = count >= 4 && lastSeen - firstSeen > 60_000
    }

    private val _sightings = MutableStateFlow<List<Sighting>>(emptyList())
    val sightings: StateFlow<List<Sighting>> = _sightings.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val active = AtomicBoolean(false)
    private val seen = linkedMapOf<String, MutableSighting>()
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    private class MutableSighting(
        val key: String,
        val ecosystem: String,
        var rssi: Int,
        val firstSeen: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
        var count: Int = 1,
    )

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val ecosystem = when {
                record.getManufacturerSpecificData(0x004C) != null -> "Apple Find My"
                record.getServiceData(ParcelUuid(SMARTTAG_UUID)) != null -> "Samsung SmartTag"
                record.getServiceData(ParcelUuid(TILE_UUID)) != null -> "Tile"
                else -> return
            }
            val payload = record.getManufacturerSpecificData(0x004C)
                ?: record.getServiceData(ParcelUuid(SMARTTAG_UUID))
                ?: record.getServiceData(ParcelUuid(TILE_UUID))
                ?: return
            // The rotating identifier is what distinguishes one tag from another.
            val key = ecosystem + ":" + payload.take(8).joinToString("") { "%02x".format(it) }
            synchronized(seen) {
                val existing = seen[key]
                if (existing == null) {
                    seen[key] = MutableSighting(key, ecosystem, result.rssi)
                } else {
                    existing.rssi = result.rssi
                    existing.lastSeen = System.currentTimeMillis()
                    existing.count++
                }
                _sightings.value = seen.values.map {
                    Sighting(it.key, it.ecosystem, it.rssi, it.firstSeen, it.lastSeen, it.count)
                }.sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = "Scan failed ($errorCode)"
            stop()
        }
    }

    fun start() {
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
        val ble = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        }
        if (ble == null) {
            active.set(false)
            _status.value = "No BLE scanner"
            return
        }
        scanner = ble
        seen.clear()
        _sightings.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            ble.startScan(null, settings, callback)
            _running.value = true
            _status.value = "Listening for trackers"
        } catch (error: SecurityException) {
            active.set(false)
            _status.value = "Scan permission denied"
        }
    }

    fun stop() {
        active.set(false)
        runCatching { scanner?.stopScan(callback) }
        scanner = null
        _running.value = false
        _status.value = "Stopped"
    }

    fun clear() {
        synchronized(seen) {
            seen.clear()
            _sightings.value = emptyList()
        }
    }

    private val SMARTTAG_UUID: UUID = UUID.fromString("0000fd5a-0000-1000-8000-00805f9b34fb")
    private val TILE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")
}

// ---------------------------------------------------------------------------------------------
// Beacon spam
// ---------------------------------------------------------------------------------------------

/**
 * iBeacon and Eddystone flooding with arbitrary names. This is the Flipper's beacon mode:
 * it does not attack a device, it pollutes the scan list of every phone in range.
 */
object BeaconSpam {

    enum class Format(val label: String) {
        IBEACON("iBeacon"),
        EDDYSTONE_UID("Eddystone UID"),
        EDDYSTONE_URL("Eddystone URL"),
        ALTBEACON("AltBeacon"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val active = AtomicBoolean(false)

    private val eddystoneUuid: UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

    fun stop() {
        active.set(false)
        _running.value = false
        _status.value = "Stopped"
    }

    @SuppressLint("MissingPermission")
    fun start(format: Format, names: List<String>, rotateMs: Long = 700L) {
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
        val pool = names.ifEmpty { listOf("Whaly") }
        _running.value = true
        _count.value = 0
        _status.value = "${format.label} spam running"

        val callback = object : android.bluetooth.le.AdvertiseCallback() {
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
                index++
                val data = when (format) {
                    Format.IBEACON -> AdvertiseData.Builder()
                        .addManufacturerData(0x004C, ibeaconPayload(name))
                        .setIncludeDeviceName(false)
                        .build()
                    Format.EDDYSTONE_UID -> AdvertiseData.Builder()
                        .addServiceData(ParcelUuid(eddystoneUuid), eddystoneUid(name))
                        .setIncludeDeviceName(false)
                        .build()
                    Format.EDDYSTONE_URL -> AdvertiseData.Builder()
                        .addServiceData(ParcelUuid(eddystoneUuid), eddystoneUrl("https://$name.example"))
                        .setIncludeDeviceName(false)
                        .build()
                    Format.ALTBEACON -> AdvertiseData.Builder()
                        .addManufacturerData(0x0118, altBeaconPayload(name))
                        .setIncludeDeviceName(false)
                        .build()
                }
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
                    return@Thread
                }
                try {
                    Thread.sleep(rotateMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
            runCatching { advertiser.stopAdvertising(callback) }
        }, "whaly-beacon-spam").start()
    }

    private fun ibeaconPayload(name: String): ByteArray {
        val uuid = java.util.UUID.nameUUIDFromBytes(name.toByteArray())
        val out = java.io.ByteArrayOutputStream()
        out.write(0x02); out.write(0x15)          // iBeacon type, 21 bytes follow
        uuid.asBytes(out)
        out.write(0x00); out.write(0x01)          // major
        out.write(0x00); out.write(0x02)          // minor
        out.write(0xC5.toInt())                   // measured power
        return out.toByteArray()
    }

    private fun UUID.asBytes(out: java.io.ByteArrayOutputStream) {
        fun writeLong(value: Long, bytes: Int) {
            for (index in bytes - 1 downTo 0) out.write(((value shr (index * 8)) and 0xFF).toInt())
        }
        writeLong(mostSignificantBits, 8)
        writeLong(leastSignificantBits, 8)
    }

    private fun eddystoneUid(name: String): ByteArray {
        val hash = name.toByteArray().let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        val out = java.io.ByteArrayOutputStream()
        out.write(0x00)                            // frame type UID
        out.write(0xED.toInt())                    // tx power
        out.write(hash, 0, 10)                     // namespace
        out.write(hash, 10, 6)                     // instance
        return out.toByteArray()
    }

    private fun eddystoneUrl(url: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x10)                            // frame type URL
        out.write(0xED.toInt())
        out.write(0x00)                            // scheme https://
        out.write(url.removePrefix("https://").toByteArray(Charsets.UTF_8).take(17).toByteArray())
        return out.toByteArray()
    }

    private fun altBeaconPayload(name: String): ByteArray {
        val hash = name.toByteArray().let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        val out = java.io.ByteArrayOutputStream()
        out.write(0xBE.toInt()); out.write(0xAC.toInt())   // beacon id
        out.write(hash, 0, 20)                             // proximity uuid
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x02)
        out.write(0xC5.toInt())
        return out.toByteArray()
    }
}
