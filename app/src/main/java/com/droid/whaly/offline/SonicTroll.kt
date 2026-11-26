package com.droid.whaly.offline

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Speaker-only nuisance generator. Nothing here touches the network, a radio or root.
 *
 * The interesting modes are above 17 kHz. Almost every phone speaker rolls off hard past
 * 20 kHz, but the small ones still push enough energy at 21-23 kHz to be audible to children,
 * dogs and cats, and MEMS microphones demodulate that band down into the audible range
 * because of their own nonlinearity — which is what the jammer mode is for.
 */
object SonicTroll {

    enum class Mode(val label: String, val note: String) {
        MOSQUITO("Mosquito", "17.4 kHz steady — the classic, inaudible to most adults"),
        CHILD_SWEEP("Child sweep", "17.0 to 20.0 kHz, slow triangle, no endpoint"),
        ULTRASONIC("Ultrasonic sweep", "20.0 to 23.5 kHz, above what any adult hears"),
        MIC_JAMMER("Microphone jammer", "23.2 kHz carrier amplitude-modulated with noise"),
        DISSONANCE("Dissonance", "two tones a semitone apart, beating at 8 Hz"),
        SWEEP("Full sweep", "20 Hz to 22 kHz in 12 s, a hearing test nobody asked for"),
        LOW_PULSE("Sub pulse", "38 Hz at 2 Hz gate — the door-rattle one"),
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _mode = MutableStateFlow(Mode.MOSQUITO)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val active = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    private const val SAMPLE_RATE = 48000
    private const val BLOCK = 1024

    fun stop() {
        active.set(false)
        worker?.interrupt()
        worker = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null
        _running.value = false
        _status.value = "Stopped"
    }

    fun start(mode: Mode, amplitude: Float = 0.92f) {
        if (!active.compareAndSet(false, true)) return
        _mode.value = mode
        val level = amplitude.coerceIn(0.05f, 1.0f)

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            active.set(false)
            _status.value = "This device refused a 48 kHz mono track"
            return
        }
        val bufferBytes = maxOf(minBuffer, BLOCK * 4)

        val audio = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            null
        }
        if (audio == null) {
            active.set(false)
            _status.value = "Could not open the audio track"
            return
        }

        track = audio
        _running.value = true
        _status.value = "${mode.label} — ${(level * 100).toInt()}%"

        worker = Thread({
            try {
                audio.play()
                val block = ShortArray(BLOCK)
                var phase = 0.0
                var phase2 = 0.0
                var elapsed = 0.0
                var noiseState = 0x2545F491
                val dt = 1.0 / SAMPLE_RATE

                while (active.get()) {
                    val frequency = when (mode) {
                        Mode.MOSQUITO -> 17400.0
                        Mode.CHILD_SWEEP -> {
                            val period = 9.0
                            val position = (elapsed % period) / period
                            val triangle = 1.0 - kotlin.math.abs(2.0 * position - 1.0)
                            17000.0 + triangle * 3000.0
                        }
                        Mode.ULTRASONIC -> {
                            val period = 6.0
                            val position = (elapsed % period) / period
                            20000.0 + position * 3500.0
                        }
                        Mode.MIC_JAMMER -> 23200.0
                        Mode.DISSONANCE -> 440.0
                        Mode.SWEEP -> {
                            val period = 12.0
                            val position = (elapsed % period) / period
                            20.0 * Math.pow(22000.0 / 20.0, position)
                        }
                        Mode.LOW_PULSE -> 38.0
                    }

                    val step = 2.0 * PI * frequency / SAMPLE_RATE
                    val step2 = 2.0 * PI * (frequency * 1.059463) / SAMPLE_RATE

                    for (index in 0 until BLOCK) {
                        var sample = when (mode) {
                            Mode.DISSONANCE -> {
                                val a = sin(phase)
                                val b = sin(phase2) * 0.7
                                (a + b) * 0.5
                            }
                            Mode.MIC_JAMMER -> {
                                noiseState = noiseState xor (noiseState shl 13)
                                noiseState = noiseState xor (noiseState ushr 17)
                                noiseState = noiseState xor (noiseState shl 5)
                                val noise = (noiseState and 0xFFFF) / 32768.0 - 1.0
                                sin(phase) * (0.55 + 0.45 * noise)
                            }
                            Mode.LOW_PULSE -> {
                                val gate = if ((elapsed % 0.5) < 0.25) 1.0 else 0.0
                                sin(phase) * gate
                            }
                            else -> sin(phase)
                        }

                        // Above 17 kHz the speaker is already down 10-20 dB, so the sample is
                        // driven hot and soft-clipped rather than attenuated further.
                        sample *= level
                        if (sample > 1.0) sample = 1.0
                        if (sample < -1.0) sample = -1.0
                        block[index] = (sample * 32767.0).toInt().toShort()

                        phase += step
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                        phase2 += step2
                        if (phase2 > 2.0 * PI) phase2 -= 2.0 * PI
                        elapsed += dt
                    }

                    val written = audio.write(block, 0, BLOCK)
                    if (written < 0) break
                }
            } catch (_: Exception) {
            } finally {
                runCatching {
                    audio.stop()
                    audio.release()
                }
                if (track === audio) track = null
                active.set(false)
                _running.value = false
                if (_status.value != "Stopped") _status.value = "Finished"
            }
        }, "whaly-sonic").apply { isDaemon = true; start() }
    }

    /** The band the track is opened at. Anything above is not representable. */
    fun describe(): String = "PCM mono at $SAMPLE_RATE Hz, Nyquist ${SAMPLE_RATE / 2} Hz"
}
