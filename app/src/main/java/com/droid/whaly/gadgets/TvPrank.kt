package com.droid.whaly.gadgets

import android.content.Context
import com.droid.whaly.IrBackend
import com.droid.whaly.IrButton
import com.droid.whaly.offline.IrCodes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Autonomous television pranks.
 *
 * The difference between this and the IR Troll screen is that nobody is holding the phone.
 * A mode is started with an interval range and it keeps firing on its own for as long as it
 * is left running, which is what makes it land: a set that turns itself up by one step every
 * few minutes for an hour is far more unsettling than a single burst.
 *
 * Samsung NEC address 0x07 is the widest family and covers the volume, power, input and
 * channel keys on almost every panel sold in the last decade.
 */
object TvPrank {

    enum class Mode(val label: String, val note: String) {
        VOLUME_CRAWL("Volume crawl", "one step up every interval, never down"),
        RANDOM_POWER("Random power", "toggles the set at a random point in the window"),
        INPUT_FLIP("Input flip", "cycles the source, so the picture disappears"),
        MUTE_WAR("Mute war", "mutes, waits, unmutes, mutes again"),
        CHANNEL_DRIFT("Channel drift", "channel up in bursts of three"),
        SLEEP_TIMER("Sleep timer", "power off after the window, then again on the next"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _mode = MutableStateFlow(Mode.VOLUME_CRAWL)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private val _fired = MutableStateFlow(0)
    val fired: StateFlow<Int> = _fired.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null

    private fun samsung(name: String, command: Int) = IrCodes.necButton(name, 0x07, command)

    private val volumeUp = samsung("volume up", 0x07)
    private val volumeDown = samsung("volume down", 0x0B)
    private val mute = samsung("mute", 0x0F)
    private val power = samsung("power", 0x02)
    private val source = samsung("source", 0x01)
    private val channelUp = samsung("channel up", 0x12)

    fun stop() {
        active.set(false)
        worker?.interrupt()
        worker = null
        _running.value = false
    }

    fun start(
        context: Context,
        mode: Mode,
        minSeconds: Int = 20,
        maxSeconds: Int = 90,
    ) {
        if (!IrBackend.isAvailable(context)) {
            _log.value = listOf("No IR emitter — this phone has none and no USB blaster is attached")
            return
        }
        if (!active.compareAndSet(false, true)) return
        val low = minSeconds.coerceIn(3, 3600)
        val high = maxSeconds.coerceIn(low, 7200)

        _mode.value = mode
        _fired.value = 0
        _running.value = true
        _log.value = listOf("${mode.label} · every ${low}s to ${high}s")

        worker = Thread({
            var count = 0
            var muted = false
            while (active.get()) {
                val wait = Random.nextInt(low, high + 1)
                try {
                    Thread.sleep(wait * 1000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!active.get()) break

                val sent = when (mode) {
                    Mode.VOLUME_CRAWL -> send(context, volumeUp)
                    Mode.RANDOM_POWER -> send(context, power)
                    Mode.INPUT_FLIP -> send(context, source)
                    Mode.MUTE_WAR -> {
                        muted = !muted
                        send(context, mute)
                    }
                    Mode.CHANNEL_DRIFT -> {
                        repeat(3) {
                            send(context, channelUp)
                            try {
                                Thread.sleep(220)
                            } catch (_: InterruptedException) {
                            }
                        }
                        true
                    }
                    Mode.SLEEP_TIMER -> {
                        // A short volume drop first, so the set gives itself away before it dies.
                        send(context, volumeDown)
                        try {
                            Thread.sleep(400)
                        } catch (_: InterruptedException) {
                        }
                        send(context, power)
                    }
                }
                count++
                _fired.value = count
                _log.value = (_log.value + "${mode.label} #$count after ${wait}s → ${if (sent) "sent" else "rejected"}")
                    .takeLast(80)
            }
            active.set(false)
            _running.value = false
        }, "whaly-tv-prank").apply { isDaemon = true; start() }
    }

    private fun send(context: Context, button: IrButton): Boolean =
        runCatching { IrBackend.transmit(context, button) }.getOrDefault(false)
}
