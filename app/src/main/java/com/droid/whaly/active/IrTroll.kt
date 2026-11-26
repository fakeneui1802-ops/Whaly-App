package com.droid.whaly.active

import android.content.Context
import com.droid.whaly.IrBackend
import com.droid.whaly.IrButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Infrared trolling suite.
 *
 * Every mode encodes the command itself instead of looking up a remote file, so it fires
 * at whatever television is in the room without knowing the model. The brand table covers
 * NEC, Samsung32, Sony SIRC and Philips RC5, which is the bulk of the market.
 */
object IrTroll {

    enum class Mode(val label: String, val description: String) {
        VOLUME_MAX(
            "Volume to 100",
            "Hammers volume-up on every brand until the set is at maximum.",
        ),
        CHANNEL_CHAOS(
            "Channel chaos",
            "Random channel digits on every brand, so the set lands on something different each pass.",
        ),
        INPUT_HIJACK(
            "Input hijack",
            "Cycles HDMI 1 through 4 on every brand. The picture disappears and the input banner sits there.",
        ),
        POWER_LOOP(
            "Power loop",
            "Power toggle on every brand every few seconds. The set never settles on or off.",
        ),
        MUTE_WAR(
            "Mute war",
            "Mute toggles back and forth faster than the on-screen indicator can clear.",
        ),
        MENU_LOCK(
            "Menu lock",
            "Opens the settings menu on every brand. Closing it reopens it on the next pass.",
        ),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)

    // ---------------------------------------------------------------------------------------
    // Protocol encoders
    // ---------------------------------------------------------------------------------------

    private fun nec(address: Int, command: Int): IntArray {
        val out = ArrayList<Int>(70)
        out += 9000
        out += -4500
        fun bit(one: Boolean) {
            out += 560
            out += if (one) -1690 else -560
        }
        for (index in 0 until 8) bit((address shr index) and 1 == 1)
        for (index in 8 until 16) bit((address shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 0)
        out += 560
        out += -40000
        return out.toIntArray()
    }

    private fun samsung(address: Int, command: Int): IntArray {
        val out = ArrayList<Int>(70)
        out += 4500
        out += -4500
        fun bit(one: Boolean) {
            out += 560
            out += if (one) -1690 else -560
        }
        for (index in 0 until 8) bit((address shr index) and 1 == 1)
        for (index in 8 until 16) bit((address shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 1)
        for (index in 0 until 8) bit((command shr index) and 1 == 0)
        out += 560
        out += -40000
        return out.toIntArray()
    }

    private fun sirc(command: Int, device: Int = 0x01): IntArray {
        val out = ArrayList<Int>(28)
        out += 2400
        out += -600
        for (index in 0 until 12) {
            val one = if (index < 7) (command shr index) and 1 == 1
            else (device shr (index - 7)) and 1 == 1
            out += if (one) 1200 else 600
            out += -600
        }
        out += -25000
        return out.toIntArray()
    }

    private fun rc5(command: Int, address: Int = 0x00, toggle: Boolean = false): IntArray {
        val bits = ArrayList<Boolean>(14)
        bits += true
        bits += !toggle
        for (index in 4 downTo 0) bits += (address shr index) and 1 == 1
        for (index in 5 downTo 0) bits += (command shr index) and 1 == 1
        val out = ArrayList<Int>(30)
        for (bit in bits) {
            if (bit) {
                out += -889
                out += 889
            } else {
                out += 889
                out += -889
            }
        }
        out += -89000
        return out.toIntArray()
    }

    private data class Command(val brand: String, val frequency: Int, val pattern: IntArray)

    /** Every brand's code for one logical button. */
    private fun commandsFor(mode: Mode, seed: Int): List<Command> = when (mode) {
        Mode.VOLUME_MAX -> listOf(
            Command("Samsung", 38000, samsung(0x0707, 0x07)),
            Command("LG", 38000, nec(0x20DF, 0x40)),
            Command("Sony", 40000, sirc(0x12)),
            Command("Philips", 36000, rc5(0x10)),
            Command("Toshiba", 38000, nec(0x40BF, 0x02)),
            Command("Panasonic", 38000, nec(0x4004, 0x05)),
        )
        Mode.CHANNEL_CHAOS -> {
            val digit = seed % 10
            val digitCodes = mapOf(
                0 to 0x00, 1 to 0x01, 2 to 0x02, 3 to 0x03, 4 to 0x04,
                5 to 0x05, 6 to 0x06, 7 to 0x07, 8 to 0x08, 9 to 0x09,
            )
            val code = digitCodes[digit] ?: 0x01
            listOf(
                Command("Samsung", 38000, samsung(0x0707, code)),
                Command("LG", 38000, nec(0x20DF, 0x00 + digit * 0x04)),
                Command("Sony", 40000, sirc(0x01 + digit)),
                Command("Philips", 36000, rc5(0x00 + digit)),
            )
        }
        Mode.INPUT_HIJACK -> listOf(
            Command("Samsung", 38000, samsung(0x0707, 0x01)),
            Command("LG", 38000, nec(0x20DF, 0xD0)),
            Command("Sony", 40000, sirc(0x25)),
            Command("Philips", 36000, rc5(0x38)),
            Command("Panasonic", 38000, nec(0x4004, 0x0A)),
        )
        Mode.POWER_LOOP -> listOf(
            Command("Samsung", 38000, samsung(0x0707, 0x02)),
            Command("LG", 38000, nec(0x20DF, 0x10)),
            Command("Sony", 40000, sirc(0x15)),
            Command("Philips", 36000, rc5(0x0C)),
            Command("Toshiba", 38000, nec(0x40BF, 0x12)),
            Command("Panasonic", 38000, nec(0x4004, 0x0C)),
        )
        Mode.MUTE_WAR -> listOf(
            Command("Samsung", 38000, samsung(0x0707, 0x0F)),
            Command("LG", 38000, nec(0x20DF, 0x90)),
            Command("Sony", 40000, sirc(0x14)),
            Command("Philips", 36000, rc5(0x0D)),
            Command("Panasonic", 38000, nec(0x4004, 0x0B)),
        )
        Mode.MENU_LOCK -> listOf(
            Command("Samsung", 38000, samsung(0x0707, 0x1A)),
            Command("LG", 38000, nec(0x20DF, 0xC2)),
            Command("Sony", 40000, sirc(0x60)),
            Command("Philips", 36000, rc5(0x52)),
            Command("Panasonic", 38000, nec(0x4004, 0x0E)),
        )
    }

    private fun gapFor(mode: Mode): Long = when (mode) {
        Mode.VOLUME_MAX -> 90L
        Mode.CHANNEL_CHAOS -> 700L
        Mode.INPUT_HIJACK -> 1200L
        Mode.POWER_LOOP -> 4000L
        Mode.MUTE_WAR -> 250L
        Mode.MENU_LOCK -> 1500L
    }

    fun stop() {
        active.set(false)
        _running.value = false
        _status.value = "Stopped"
    }

    fun start(context: Context, mode: Mode) {
        if (!active.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        if (!IrBackend.isAvailable(appContext)) {
            active.set(false)
            _status.value = "No IR emitter — plug a USB-C blaster or install an IR plugin"
            return
        }
        _running.value = true
        _count.value = 0
        _status.value = mode.label
        _log.value = listOf("started: ${mode.label}")

        Thread({
            var pass = 0
            while (active.get()) {
                val commands = commandsFor(mode, pass)
                commands.forEach { command ->
                    if (!active.get()) return@Thread
                    IrBackend.transmit(
                        appContext,
                        IrButton(
                            name = "${mode.label} ${command.brand}",
                            frequency = command.frequency,
                            pattern = command.pattern,
                        ),
                    )
                    _count.value = _count.value + 1
                    try {
                        Thread.sleep(gapFor(mode) / commands.size.coerceAtLeast(1))
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                pass++
                if (pass % 10 == 0) {
                    _log.value = (_log.value + "pass $pass · ${_count.value} codes").takeLast(40)
                }
                try {
                    Thread.sleep(gapFor(mode))
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "whaly-ir-troll").start()
    }
}
