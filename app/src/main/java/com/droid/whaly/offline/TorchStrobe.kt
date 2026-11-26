package com.droid.whaly.offline

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Torch patterns through CameraManager.setTorchMode, which needs no permission and no root.
 *
 * The slow patterns are a nuisance. The fast ones are the reason this exists: a phone torch
 * driven at 10-15 Hz in a dark room is a strobe, and a room full of them is worse.
 */
object TorchStrobe {

    enum class Pattern(val label: String, val note: String) {
        STROBE_10("Strobe 10 Hz", "50 ms on, 50 ms off"),
        STROBE_15("Strobe 15 Hz", "33 ms on, 33 ms off — the ugly one"),
        SOS("SOS", "three short, three long, three short, then a pause"),
        HEARTBEAT("Heartbeat", "double thump at 1 Hz"),
        RAMP("Ramp", "brightness illusion through duty cycle, 0 to 100 %"),
        RANDOM("Random", "unpredictable bursts, 20 to 400 ms"),
        SOLID("Solid", "torch on, no pattern"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _pattern = MutableStateFlow(Pattern.STROBE_10)
    val pattern: StateFlow<Pattern> = _pattern.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null
    private var cameraId: String? = null

    private fun manager(context: Context): CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private fun findTorchCamera(manager: CameraManager): String? {
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        for (id in ids) {
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (flash) return id
        }
        return null
    }

    private fun setTorch(context: Context, on: Boolean): Boolean {
        val manager = manager(context) ?: return false
        val id = cameraId ?: findTorchCamera(manager) ?: return false
        cameraId = id
        return runCatching {
            manager.setTorchMode(id, on)
            true
        }.getOrDefault(false)
    }

    fun stop(context: Context) {
        active.set(false)
        worker?.interrupt()
        worker = null
        setTorch(context, false)
        _running.value = false
        _status.value = "Stopped"
    }

    fun start(context: Context, pattern: Pattern) {
        if (!active.compareAndSet(false, true)) return
        val manager = manager(context)
        if (manager == null) {
            active.set(false)
            _status.value = "No camera service"
            return
        }
        val id = findTorchCamera(manager)
        if (id == null) {
            active.set(false)
            _status.value = "No flash unit on this device"
            return
        }
        cameraId = id
        _pattern.value = pattern
        _running.value = true
        _status.value = pattern.label

        worker = Thread({
            fun hold(on: Boolean, ms: Long) {
                setTorch(context, on)
                try {
                    Thread.sleep(ms.coerceAtLeast(16L))
                } catch (_: InterruptedException) {
                }
            }

            try {
                when (pattern) {
                    Pattern.STROBE_10 -> while (active.get()) {
                        hold(true, 50); hold(false, 50)
                    }
                    Pattern.STROBE_15 -> while (active.get()) {
                        hold(true, 33); hold(false, 33)
                    }
                    Pattern.SOS -> while (active.get()) {
                        repeat(3) { hold(true, 150); hold(false, 150) }
                        repeat(3) { hold(true, 450); hold(false, 150) }
                        repeat(3) { hold(true, 150); hold(false, 150) }
                        hold(false, 1200)
                    }
                    Pattern.HEARTBEAT -> while (active.get()) {
                        hold(true, 90); hold(false, 120)
                        hold(true, 140); hold(false, 900)
                    }
                    Pattern.RAMP -> while (active.get()) {
                        for (duty in 1..20) {
                            hold(true, duty * 4L)
                            hold(false, (20 - duty) * 4L)
                        }
                        for (duty in 20 downTo 1) {
                            hold(true, duty * 4L)
                            hold(false, (20 - duty) * 4L)
                        }
                    }
                    Pattern.RANDOM -> while (active.get()) {
                        hold(true, Random.nextLong(20, 400))
                        hold(false, Random.nextLong(40, 500))
                    }
                    Pattern.SOLID -> {
                        setTorch(context, true)
                        while (active.get()) Thread.sleep(500)
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                setTorch(context, false)
                active.set(false)
                _running.value = false
                if (_status.value != "Stopped") _status.value = "Finished"
            }
        }, "whaly-torch").apply { isDaemon = true; start() }
    }
}
