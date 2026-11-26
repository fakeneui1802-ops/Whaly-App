package com.droid.whaly.field

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Ultrasonic listener.
 *
 * A phone microphone samples at 48 kHz, so it can hear to 24 kHz — well above what anyone
 * can. The trick is to make that band audible: multiplying the signal by a 12 kHz carrier
 * shifts everything down by 12 kHz, which puts 15-24 kHz at 3-12 kHz where the ear works.
 * That is ring modulation, and it needs no FFT.
 *
 * What turns up: mosquito repellents at 21-23 kHz, failing switch-mode supplies, ultrasonic
 * pest devices, and the beacon some retail tags use.
 */
object Ultrasonic {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _peak = MutableStateFlow(0f)
    val peak: StateFlow<Float> = _peak.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null
    private var shiftHz = 12000.0
    private var playback = true

    private const val RATE = 48000
    private const val BLOCK = 1024

    fun stop() {
        active.set(false)
        worker?.interrupt()
        worker = null
        _running.value = false
        _status.value = "Stopped"
    }

    fun setShift(hz: Int) {
        shiftHz = hz.coerceIn(6000, 16000).toDouble()
    }

    fun setPlayback(enabled: Boolean) {
        playback = enabled
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!active.compareAndSet(false, true)) return
        val minRecord = AudioRecord.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minRecord <= 0) {
            active.set(false)
            _status.value = "This device refused a 48 kHz capture"
            return
        }
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRecord, BLOCK * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            active.set(false)
            runCatching { record?.release() }
            _status.value = "Microphone unavailable"
            return
        }

        val minTrack = AudioTrack.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = runCatching {
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
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minTrack, BLOCK * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        _running.value = true
        _status.value = "Listening — shifting ${shiftHz.toInt()} Hz down"

        worker = Thread({
            try {
                record.startRecording()
                track?.play()
                val input = ShortArray(BLOCK)
                val output = ShortArray(BLOCK)
                var phase = 0.0
                var peakLevel = 0f

                while (active.get()) {
                    val read = record.read(input, 0, BLOCK)
                    if (read <= 0) continue
                    var sum = 0.0
                    for (index in 0 until read) {
                        val sample = input[index] / 32768.0
                        val carrier = cos(phase)
                        // Ring modulation: the product carries both sum and difference
                        // frequencies; the difference is the band we want.
                        val shifted = sample * carrier
                        phase += 2.0 * PI * shiftHz / RATE
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                        val clamped = shifted.coerceIn(-1.0, 1.0)
                        output[index] = (clamped * 32767.0).toInt().toShort()
                        sum += abs(shifted)
                    }
                    val rms = (sum / read).toFloat()
                    _level.value = rms
                    if (rms > peakLevel) {
                        peakLevel = rms
                        _peak.value = rms
                    }
                    if (playback) {
                        runCatching { track?.write(output, 0, read) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching {
                    record.stop()
                    record.release()
                }
                runCatching {
                    track?.stop()
                    track?.release()
                }
                active.set(false)
                _running.value = false
                if (_status.value != "Stopped") _status.value = "Finished"
            }
        }, "whaly-ultrasonic").apply { isDaemon = true; start() }
    }
}
