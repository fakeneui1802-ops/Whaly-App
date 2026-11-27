package com.droid.whaly.scooter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Bluetooth Classic SPP client for Lime Gen 2.5 hardware.
 *
 * The SJ2.5 box exposes a serial port profile, not a GATT service, so a BLE client can
 * never see it. Frames are `46 43 <rw> <code> <len> <payload> <crc hi> <crc lo>` with a
 * CRC-16/XMODEM trailer. The codes below are the ones published for firmware
 * LIS01_FW_1.8.5_201911162121000 — the front light, the wheel lock and the wheel unlock.
 *
 * A scooter on newer firmware does not answer these. That is a firmware fact, not a bug
 * in the client, and the screen reports the write result rather than pretending.
 */
object LimeSppClient {

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val executor = Executors.newSingleThreadExecutor()

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var address: String? = null

    @SuppressLint("MissingPermission")
    fun send(context: Context, target: String, frame: ScooterFrames.Frame, onResult: (Boolean, String) -> Unit) {
        executor.execute {
            val ok = runCatching {
                if (socket?.isConnected != true || address != target) {
                    close()
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                        ?: return@runCatching false
                    val device = adapter.getRemoteDevice(target)
                    val created = device.createRfcommSocketToServiceRecord(SPP)
                    socket = created
                    address = target
                    runCatching { adapter.cancelDiscovery() }
                    created.connect()
                    output = created.outputStream
                }
                val stream = output ?: return@runCatching false
                stream.write(frame.bytes)
                stream.flush()
                true
            }.getOrDefault(false)
            onResult(ok, if (ok) "${frame.label} sent" else "${frame.label} refused")
        }
    }

    fun close() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        address = null
    }
}
