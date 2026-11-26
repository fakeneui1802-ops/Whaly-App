package com.droid.whaly.field

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * GATT explorer and UART terminal.
 *
 * The Flipper has no 2.4 GHz radio, so this is the one category where a phone is strictly
 * better: every service, every characteristic, read, write, notify, and a Nordic UART
 * terminal that talks to any ESP32 or devboard.
 */
object BleGattExplorer {

    data class Node(
        val serviceUuid: String,
        val characteristicUuid: String,
        val properties: String,
        val value: String,
    )

    data class Peer(val address: String, val name: String, val rssi: Int)

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes.asStateFlow()
    private val _connected = MutableStateFlow<String?>(null)
    val connected: StateFlow<String?> = _connected.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _uart = MutableStateFlow<List<String>>(emptyList())
    val uart: StateFlow<List<String>> = _uart.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private const val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    private var gatt: BluetoothGatt? = null
    private var contextRef: Context? = null

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }

    private fun appendUart(line: String) {
        _uart.value = (_uart.value + line).takeLast(200)
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        val scanner = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
        }.getOrNull() ?: run {
            _status.value = "Bluetooth is off"
            return
        }
        _peers.value = emptyList()
        _status.value = "Scanning"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
        Thread({
            try {
                Thread.sleep(9000)
            } catch (_: InterruptedException) {
            }
            runCatching { scanner.stopScan(scanCallback) }
            if (_status.value == "Scanning") _status.value = "Scan complete"
        }, "whaly-gatt-scan").apply { isDaemon = true; start() }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = runCatching { device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: ""
            val peer = Peer(device.address, name, result.rssi)
            _peers.value = (_peers.value.filterNot { it.address == peer.address } + peer)
                .sortedByDescending { it.rssi }
                .take(60)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, address: String) {
        contextRef = context.applicationContext
        val device: BluetoothDevice = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        }.getOrNull() ?: run {
            append("cannot resolve $address")
            return
        }
        disconnect()
        _nodes.value = emptyList()
        _status.value = "Connecting to $address"
        append("connect $address")
        gatt = runCatching {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) _status.value = "connectGatt refused"
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        _connected.value = null
        _nodes.value = emptyList()
        _status.value = "Disconnected"
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connected.value = gatt.device.address
                _status.value = "Connected — discovering services"
                append("connected, status $status")
                runCatching { gatt.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = null
                _status.value = "Disconnected"
                append("disconnected, status $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val services: List<BluetoothGattService> = gatt.services ?: emptyList()
            append("${services.size} service(s), status $status")
            val nodes = mutableListOf<Node>()
            services.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    nodes += Node(
                        serviceUuid = service.uuid.toString(),
                        characteristicUuid = characteristic.uuid.toString(),
                        properties = describe(characteristic.properties),
                        value = readValue(characteristic) ?: "",
                    )
                }
            }
            _nodes.value = nodes
            _status.value = "${services.size} service(s), ${nodes.size} characteristic(s)"
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val value = readValue(characteristic) ?: ""
            append("read ${characteristic.uuid} → $value")
            updateNode(characteristic, value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val hex = hex(value)
            append("read ${characteristic.uuid} → $hex")
            updateNode(characteristic, hex)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            append("write ${characteristic.uuid} → status $status")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = readValue(characteristic) ?: return
            handleNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic, hex(value))
        }
    }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: String) {
        updateNode(characteristic, value)
        if (characteristic.uuid.toString().lowercase() == NUS_TX) {
            val text = value.split(" ")
                .mapNotNull { it.toIntOrNull(16)?.toChar() }
                .joinToString("")
            appendUart("← $text")
        } else {
            append("notify ${characteristic.uuid} → $value")
        }
    }

    private fun updateNode(characteristic: BluetoothGattCharacteristic, value: String) {
        val uuid = characteristic.uuid.toString()
        _nodes.value = _nodes.value.map {
            if (it.characteristicUuid == uuid) it.copy(value = value) else it
        }
    }

    @SuppressLint("MissingPermission")
    fun read(uuid: String) {
        val target = gatt ?: return
        val characteristic = find(target, uuid) ?: run {
            append("no characteristic $uuid")
            return
        }
        runCatching { target.readCharacteristic(characteristic) }
    }

    @SuppressLint("MissingPermission")
    fun write(uuid: String, hexBytes: String, withResponse: Boolean = true): Boolean {
        val target = gatt ?: return false
        val characteristic = find(target, uuid) ?: run {
            append("no characteristic $uuid")
            return false
        }
        val bytes = hexBytes.replace(" ", "")
            .chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()
        if (bytes.isEmpty()) return false
        characteristic.writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.writeCharacteristic(characteristic, bytes, characteristic.writeType) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                target.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        append("write $uuid → ${if (ok) "queued" else "refused"}")
        return ok
    }

    @SuppressLint("MissingPermission")
    fun subscribe(uuid: String, enabled: Boolean) {
        val target = gatt ?: return
        val characteristic = find(target, uuid) ?: return
        runCatching {
            target.setCharacteristicNotification(characteristic, enabled)
            val descriptor: BluetoothGattDescriptor? =
                characteristic.getDescriptor(UUID.fromString(CCCD))
            descriptor?.let {
                val payload = if (enabled) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    target.writeDescriptor(it, payload)
                } else {
                    @Suppress("DEPRECATION")
                    it.value = payload
                    @Suppress("DEPRECATION")
                    target.writeDescriptor(it)
                }
            }
        }
        append("notify $uuid → $enabled")
    }

    /** Nordic UART: the service every ESP32 example exposes. */
    @SuppressLint("MissingPermission")
    fun uartSend(text: String): Boolean {
        val target = gatt ?: run {
            appendUart("not connected")
            return false
        }
        val service = target.getService(UUID.fromString(NUS_SERVICE)) ?: run {
            appendUart("no Nordic UART service on this device")
            return false
        }
        val rx = service.getCharacteristic(UUID.fromString(NUS_RX)) ?: return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.writeCharacteristic(rx, bytes, rx.writeType) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                rx.value = bytes
                @Suppress("DEPRECATION")
                target.writeCharacteristic(rx)
            }
        }.getOrDefault(false)
        appendUart("→ $text${if (ok) "" else "  (refused)"}")
        return ok
    }

    fun uartReady(): Boolean {
        val target = gatt ?: return false
        val service = target.getService(UUID.fromString(NUS_SERVICE)) ?: return false
        return service.getCharacteristic(UUID.fromString(NUS_RX)) != null
    }

    private fun find(target: BluetoothGatt, uuid: String): BluetoothGattCharacteristic? {
        val wanted = uuid.lowercase()
        target.services?.forEach { service ->
            service.characteristics?.forEach { characteristic ->
                if (characteristic.uuid.toString().lowercase() == wanted) return characteristic
            }
        }
        return null
    }

    private fun describe(properties: Int): String {
        val parts = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "read"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "write"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "writeNR"
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "notify"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "indicate"
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) parts += "broadcast"
        return parts.joinToString("|").ifEmpty { "none" }
    }

    @Suppress("DEPRECATION")
    private fun readValue(characteristic: BluetoothGattCharacteristic): String? =
        characteristic.value?.let { hex(it) }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }
}
