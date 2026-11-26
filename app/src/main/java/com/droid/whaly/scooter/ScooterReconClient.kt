package com.droid.whaly.scooter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generic GATT explorer for devices that do not use the Nordic UART service.
 *
 * The scooter client is pinned to NUS because Ninebot and Xiaomi speak it. Voi, Lime and
 * the rest of the rental fleet ship a custom service, so this client discovers whatever
 * is there, picks the first characteristic that accepts writes, subscribes to everything
 * that can notify, and records the whole exchange.
 */
@SuppressLint("MissingPermission")
class ScooterReconClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onServices(report: String, writeUuid: UUID?, notifyCount: Int)
        fun onNotify(uuid: UUID, value: ByteArray)
        fun onWrite(label: String, ok: Boolean)
        fun onDisconnected(reason: String?)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = AtomicBoolean(false)

    /** Everything the peripheral pushed since connect, oldest first. */
    val captures = mutableListOf<ByteArray>()

    fun connect() {
        close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        close()
    }

    fun close() {
        ready.set(false)
        writeChar = null
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    fun send(frame: ScooterFrames.Frame) {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || !ready.get()) {
            main.post { listener.onWrite(frame.label, false) }
            return
        }
        try {
            var offset = 0
            val payload = frame.bytes
            while (offset < payload.size) {
                val end = minOf(offset + 20, payload.size)
                val chunk = payload.copyOfRange(offset, end)
                val type = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, chunk, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = chunk
                    @Suppress("DEPRECATION")
                    ch.writeType = type
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
                if (!ok) {
                    main.post { listener.onWrite(frame.label, false) }
                    return
                }
                offset = end
                if (offset < payload.size) Thread.sleep(20)
            }
            main.post { listener.onWrite(frame.label, true) }
        } catch (error: Throwable) {
            main.post { listener.onError(error.message ?: "write failed") }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        g.requestMtu(185)
                        g.discoverServices()
                    } else {
                        main.post { listener.onError("connect status=$status") }
                        close()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready.set(false)
                    main.post { listener.onDisconnected("disconnected") }
                    close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener.onError("discovery status=$status") }
                return
            }
            val report = StringBuilder()
            var write: BluetoothGattCharacteristic? = null
            var notifyCount = 0

            for (service in g.services) {
                report.append("SERVICE ").append(service.uuid).append('\n')
                for (ch in service.characteristics) {
                    report.append("  CHAR ").append(ch.uuid)
                        .append("  props=").append(describe(ch.properties)).append('\n')
                    if (write == null && (
                            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                                ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                            )
                    ) {
                        write = ch
                    }
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                        ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    ) {
                        notifyCount++
                        g.setCharacteristicNotification(ch, true)
                        val cccd = ch.getDescriptor(CCCD)
                        if (cccd != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                g.writeDescriptor(cccd)
                            }
                        }
                    }
                }
            }

            writeChar = write
            ready.set(true)
            val text = report.toString()
            main.post { listener.onServices(text, write?.uuid, notifyCount) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            record(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            record(characteristic.uuid, value)
        }
    }

    private fun record(uuid: UUID, value: ByteArray) {
        captures += value
        main.post { listener.onNotify(uuid, value) }
    }

    private fun describe(properties: Int): String {
        val parts = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "read"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "write"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "write-no-rsp"
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "notify"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "indicate"
        return if (parts.isEmpty()) "none" else parts.joinToString("|")
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
