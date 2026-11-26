package com.droid.whaly.scanned

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class ScannedDevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var adapter: BluetoothAdapter? = null
    private val lastWrite = HashMap<String, Long>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = result.device.name
                ?: result.scanRecord?.deviceName
                ?: return
            val now = System.currentTimeMillis()
            val previous = lastWrite[address] ?: 0L
            // Two writes a second is plenty; the store only needs the newest name and rssi.
            if (now - previous > 2_000L) {
                lastWrite[address] = now
                ScannedDeviceStore.record(getApplication(), address, name, result.rssi)
                publish()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _error.value = "Scan failed: $errorCode"
        }
    }

    fun ensureAdapter(context: Context) {
        if (adapter == null) {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            adapter = manager?.adapter
        }
    }

    fun load(context: Context) {
        ScannedDeviceStore.ensureLoaded(context)
        publish()
    }

    fun start(context: Context) {
        ensureAdapter(context)
        val radio = adapter ?: return
        if (!radio.isEnabled) {
            _error.value = "Bluetooth is off"
            return
        }
        if (_scanning.value) return
        val scanner = radio.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, callback)
            _scanning.value = true
            _error.value = null
        } catch (error: Exception) {
            _error.value = error.message ?: "scan refused"
            _scanning.value = false
        }
    }

    fun stop() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        _scanning.value = false
    }

    fun clear(context: Context) {
        ScannedDeviceStore.clear(context)
        publish()
    }

    private fun publish() {
        _devices.value = ScannedDeviceStore.all()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
