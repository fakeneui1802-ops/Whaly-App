package com.droid.whaly.field

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden camera finder.
 *
 * Two passes, both through the rear sensor with no preview surface:
 *
 * 1. **IR pass** — in a dark room, a covert camera's 850-940 nm illuminator is invisible to
 *    the eye and bright to the sensor. The luma maximum inside the centre crop is the meter.
 * 2. **Glint pass** — sweep the torch. A lens is a retro-reflector, so it answers back as a
 *    small, very bright point that moves with the sweep and does not match the wall around it.
 *
 * The maximum is used rather than the mean because the target is a point source; averaging
 * would bury a single LED in the rest of the frame.
 */
object CameraFinder {

    private val _peak = MutableStateFlow(0)
    val peak: StateFlow<Int> = _peak.asStateFlow()
    private val _mean = MutableStateFlow(0)
    val mean: StateFlow<Int> = _mean.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _torch = MutableStateFlow(false)
    val torch: StateFlow<Boolean> = _torch.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _alerts = MutableStateFlow(0)
    val alerts: StateFlow<Int> = _alerts.asStateFlow()

    private val active = AtomicBoolean(false)
    private var manager: CameraManager? = null
    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var tone: ToneGenerator? = null
    private var lastAlert = 0L

    private const val ALERT_THRESHOLD = 235

    private fun cameraManager(context: Context): CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (!active.compareAndSet(false, true)) return
        val manager = cameraManager(context)
        if (manager == null) {
            active.set(false)
            _status.value = "No camera service"
            return
        }
        this.manager = manager
        val id = runCatching { manager.cameraIdList.firstOrNull() }.getOrNull()
        if (id == null) {
            active.set(false)
            _status.value = "No camera"
            return
        }
        cameraId = id
        thread = HandlerThread("whaly-camera").apply { start() }
        handler = Handler(thread!!.looper)
        runCatching { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70) }

        val size = runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageReader::class.java)
                ?.minByOrNull { it.width * it.height }
        }.getOrNull() ?: Size(640, 480)

        val imageReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ available ->
            val image = runCatching { available.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = image.width
                val height = image.height
                // Centre 50 % crop, sampled every second pixel in both axes.
                val x0 = width / 4
                val x1 = width * 3 / 4
                val y0 = height / 4
                val y1 = height * 3 / 4
                var max = 0
                var sum = 0L
                var count = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val index = y * rowStride + x * pixelStride
                        if (index < buffer.limit()) {
                            val luma = buffer.get(index).toInt() and 0xFF
                            if (luma > max) max = luma
                            sum += luma
                            count++
                        }
                        x += 2
                    }
                    y += 2
                }
                if (count > 0) {
                    _peak.value = max
                    _mean.value = (sum / count).toInt()
                    if (max >= ALERT_THRESHOLD) {
                        val now = System.currentTimeMillis()
                        if (now - lastAlert > 1200) {
                            lastAlert = now
                            _alerts.value = _alerts.value + 1
                            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
                        }
                    }
                }
            } finally {
                runCatching { image.close() }
            }
        }, handler)

        _running.value = true
        _status.value = "Opening camera ${size.width}x${size.height}"

        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    startSession(camera, imageReader)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    device = null
                    _status.value = "Camera disconnected"
                    stop(context)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    runCatching { camera.close() }
                    device = null
                    _status.value = "Camera error $error"
                    stop(context)
                }
            }, handler)
        }.onFailure {
            _status.value = "Camera open refused: ${it.message}"
            active.set(false)
            _running.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSession(camera: CameraDevice, imageReader: ImageReader) {
        runCatching {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // Long exposure in a dark room, otherwise the sensor sees nothing.
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 2)
            }
            camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    runCatching {
                        configured.setRepeatingRequest(request.build(), null, handler)
                        _status.value = "Scanning — sweep slowly, watch the peak"
                    }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    _status.value = "Session configuration failed"
                }
            }, handler)
        }.onFailure { _status.value = "Session refused: ${it.message}" }
    }

    @SuppressLint("MissingPermission")
    fun setTorch(context: Context, on: Boolean) {
        val manager = cameraManager(context) ?: return
        val id = cameraId ?: runCatching { manager.cameraIdList.firstOrNull() }.getOrNull() ?: return
        val ok = runCatching {
            manager.setTorchMode(id, on)
            true
        }.getOrDefault(false)
        _torch.value = ok && on
        _status.value = if (ok) "Torch ${if (on) "on" else "off"}" else "Torch unavailable"
    }

    fun stop(context: Context) {
        active.set(false)
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        runCatching { tone?.release() }
        tone = null
        thread?.quitSafely()
        thread = null
        handler = null
        runCatching { setTorch(context, false) }
        _running.value = false
        if (_status.value != "Stopped") _status.value = "Stopped"
    }

    fun resetPeak() {
        _alerts.value = 0
    }
}
