package com.droid.whaly.field

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Magnetometer field finder.
 *
 * The Earth's field is about 25-65 uT depending on latitude, so the baseline is measured
 * first and only the deviation from it is reported. A magnet behind plasterboard, a speaker
 * driver, a steel stud or a live conductor all stand out against that baseline.
 *
 * The tone is the interface: pitch follows the deviation, so a wall can be swept without
 * looking at the screen.
 */
object MagFinder {

    private val _microTesla = MutableStateFlow(0f)
    val microTesla: StateFlow<Float> = _microTesla.asStateFlow()
    private val _baseline = MutableStateFlow(0f)
    val baseline: StateFlow<Float> = _baseline.asStateFlow()
    private val _peak = MutableStateFlow(0f)
    val peak: StateFlow<Float> = _peak.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private var listener: SensorEventListener? = null
    private var tone: ToneGenerator? = null
    private var smoothed = 0f
    private var calibrated = false

    fun stop() {
        listener?.let { active ->
            runCatching {
                (contextRef?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                    ?.unregisterListener(active)
            }
        }
        listener = null
        runCatching { tone?.release() }
        tone = null
        contextRef = null
        _running.value = false
        _status.value = "Stopped"
    }

    private var contextRef: Context? = null

    fun start(context: Context) {
        if (_running.value) return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (manager == null || sensor == null) {
            _status.value = "This device has no magnetometer"
            return
        }
        contextRef = context.applicationContext
        calibrated = false
        smoothed = 0f
        _peak.value = 0f
        _running.value = true
        _status.value = "Hold still for a moment — measuring the local baseline"

        runCatching { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60) }

        val active = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                if (!calibrated) {
                    smoothed = magnitude
                    _baseline.value = magnitude
                    calibrated = true
                    _status.value = "Baseline ${"%.1f".format(magnitude)} uT"
                    return
                }
                // 0.15 is slow enough that a hand tremor does not drive the tone.
                smoothed += (magnitude - smoothed) * 0.15f
                _microTesla.value = smoothed
                val deviation = smoothed - _baseline.value
                if (deviation > _peak.value) _peak.value = deviation
                val level = (deviation / 40f).coerceIn(0f, 1f)
                if (level > 0.05f) {
                    val frequency = 400 + (level * 1200).toInt()
                    runCatching {
                        tone?.startTone(
                            if (frequency > 1000) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                            else ToneGenerator.TONE_PROP_BEEP,
                            90,
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = active
        manager.registerListener(active, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /** Re-reads the baseline, for when the phone is moved to a different spot. */
    fun recalibrate() {
        calibrated = false
        _peak.value = 0f
        _status.value = "Recalibrating"
    }
}
