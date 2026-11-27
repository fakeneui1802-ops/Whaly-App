package com.droid.whaly

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.util.Log
import com.droid.whaly.ghost.GhostIdentity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phantom spammer.
 *
 * Rotates manufacturer payloads so a nearby scanner sees a stream of unfamiliar devices.
 * The adapter name is never read, written or included in the payload — the phone's real
 * name cannot leak, and a force-stop cannot leave the phone renamed.
 */
class BluetoothPhantomSpammer : Spammer {

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null
    private var blinkRunnable: Runnable? = null

    override fun isSpamming(): Boolean = active.get()

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!active.compareAndSet(false, true)) return
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val advertiser = runCatching { adapter?.bluetoothLeAdvertiser }.getOrNull()
        if (adapter == null || advertiser == null) {
            active.set(false)
            Log.w("BluetoothPhantom", "no advertiser on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w("BluetoothPhantom", "advertise failed $errorCode")
            }
        }

        worker = Thread({
            var index = 0
            while (active.get()) {
                val company = 0x004C + (index % 0x30)
                val payload = GhostIdentity.randomHex(18)
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addManufacturerData(company, payload)
                    .build()
                runCatching {
                    advertiser.stopAdvertising(callback)
                    advertiser.startAdvertising(settings, data, callback)
                }
                index++
                blinkRunnable?.run()
                try {
                    Thread.sleep(900)
                } catch (_: InterruptedException) {
                    break
                }
            }
            runCatching { advertiser.stopAdvertising(callback) }
            active.set(false)
        }, "whaly-phantom").apply { isDaemon = true; start() }
    }

    override fun stop() {
        active.set(false)
        worker?.interrupt()
        worker = null
    }

    override fun setBlinkRunnable(runnable: Runnable?) {
        blinkRunnable = runnable
    }

    override fun getBlinkRunnable(): Runnable? = blinkRunnable
}
