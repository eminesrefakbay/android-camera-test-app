package com.example.ax12cam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal Camera2 test for the AX12's HDMI input.
 *
 * One preview stream only (a SurfaceView), so there are no extra ImageReader copies.
 * Buttons switch noise reduction, edge, the MediaTek 3D noise reduction key, the frame rate range,
 * the size and the capture intent. The top-left text shows what the camera HAL reports
 * (pipeline depth and the settings it actually applied).
 * Tap the video to hide or show the text and buttons (hide them when you photograph the screen).
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private val tag = "AX12CAM"

    // The MediaTek 3D noise reduction vendor key. The constructor for vendor keys is not public on Android 9,
    // so the key objects are taken from the camera's own lists of request and result keys.
    private val key3dnrName = "com.mediatek.nrfeature.3dnrmode"
    private var key3dnrRequest: CaptureRequest.Key<Any>? = null
    private var key3dnrResult: CaptureResult.Key<Any>? = null

    private lateinit var surfaceView: SurfaceView
    private lateinit var infoView: TextView
    private lateinit var controls: LinearLayout
    private val buttons = ArrayList<Pair<Button, () -> String>>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null

    // Settings that the buttons change
    private var dnr = 1                    // MediaTek 3DNR: 1 = on (as the stock app), 0 = off
    private var nrMode = 1                 // 0 OFF, 1 FAST, 2 HIGH_QUALITY
    private var edgeMode = 1               // 0 OFF, 1 FAST, 2 HIGH_QUALITY
    private var fixedFps = false           // true = [30,30], false = [5,30] (as the stock app)
    private var recordIntent = false       // false = PREVIEW, true = VIDEO_RECORD
    private var sizes: List<Size> = emptyList()
    private var sizeIndex = 0

    // Live readout
    @Volatile private var depthResult: Int? = null
    @Volatile private var dnrResult: Int? = null
    @Volatile private var nrResult: Int? = null
    @Volatile private var edgeResult: Int? = null
    @Volatile private var fpsRangeResult: Range<Int>? = null
    @Volatile private var frameCount = 0L
    @Volatile private var lastTimestampNs = 0L
    @Volatile private var intervalMsSum = 0.0
    @Volatile private var intervalCount = 0
    private var framesAtLastTick = 0L
    private var maxDepthStatic: Int? = null
    private var message = ""

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnClickListener { toggleOverlay() }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        infoView = TextView(this).apply {
            setTextColor(Color.GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setBackgroundColor(0x99000000.toInt())
            setPadding(12, 8, 12, 8)
        }
        root.addView(
            infoView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
        }
        addButton({ "3DNR: " + if (dnr == 1) "ON" else "OFF" }) { dnr = 1 - dnr; applyLive() }
        addButton({ "NR: " + modeName(nrMode) }) { nrMode = (nrMode + 1) % 3; applyLive() }
        addButton({ "EDGE: " + modeName(edgeMode) }) { edgeMode = (edgeMode + 1) % 3; applyLive() }
        addButton({ "FPS: " + if (fixedFps) "30-30" else "5-30" }) { fixedFps = !fixedFps; applyLive() }
        addButton({ "SIZE: " + sizeName() }) { nextSize() }
        addButton({ "INTENT: " + if (recordIntent) "RECORD" else "PREVIEW" }) { recordIntent = !recordIntent; startSession() }
        addButton({ "HIDE" }) { toggleOverlay() }
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)
        refreshButtons()
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam-bg").also { it.start() }
        bgHandler = Handler(bgThread.looper)
        uiHandler.post(ticker)
        if (surfaceView.holder.surface?.isValid == true) openCamera()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(ticker)
        closeCamera()
        bgThread.quitSafely()
        super.onPause()
    }

    // ------------------------------------------------------------------ surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (sizes.isNotEmpty()) holder.setFixedSize(sizes[sizeIndex].width, sizes[sizeIndex].height)
        openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Start (or restart) the session once the surface has the size we asked for.
        if (cameraDevice != null && sizes.isNotEmpty() &&
            width == sizes[sizeIndex].width && height == sizes[sizeIndex].height
        ) startSession()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        closeSession()
    }

    // ------------------------------------------------------------------ camera

    private fun openCamera() {
        if (cameraDevice != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val ids = mgr.cameraIdList
            if (ids.isEmpty()) { show("No camera found. Is the HDMI source connected?"); return }
            val id = ids[0]
            cameraId = id
            val chars = mgr.getCameraCharacteristics(id)
            maxDepthStatic = chars.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt()
            @Suppress("UNCHECKED_CAST")
            for (k in chars.availableCaptureRequestKeys) if (k.name == key3dnrName) key3dnrRequest = k as CaptureRequest.Key<Any>
            @Suppress("UNCHECKED_CAST")
            for (k in chars.availableCaptureResultKeys) if (k.name == key3dnrName) key3dnrResult = k as CaptureResult.Key<Any>
            if (key3dnrRequest == null) show("The 3DNR key is not in this camera's request keys")
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val all = map?.getOutputSizes(SurfaceHolder::class.java)?.toList() ?: emptyList()
            // Offer 1920x1080 and 1280x720 when the camera lists them, otherwise the largest size that fits.
            val wanted = ArrayList<Size>()
            for (w in listOf(Size(1920, 1080), Size(1280, 720))) if (all.contains(w)) wanted.add(w)
            if (wanted.isEmpty() && all.isNotEmpty()) wanted.add(all.maxByOrNull { it.width * it.height }!!)
            sizes = wanted
            if (sizes.isEmpty()) { show("The camera lists no SurfaceView sizes."); return }
            if (sizeIndex >= sizes.size) sizeIndex = 0
            surfaceView.holder.setFixedSize(sizes[sizeIndex].width, sizes[sizeIndex].height)
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    if (surfaceView.holder.surface?.isValid == true) startSession()
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null; show("Camera disconnected") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null; show("Camera error $error") }
            }, bgHandler)
        } catch (e: CameraAccessException) {
            show("openCamera failed: $e")
        } catch (e: SecurityException) {
            show("No camera permission: $e")
        }
    }

    private fun startSession() {
        val device = cameraDevice ?: return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) return
        try {
            closeSession()
            val template = if (recordIntent) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val b = device.createCaptureRequest(template)
            b.addTarget(surface)
            builder = b
            applySettings(b)
            frameCount = 0; intervalMsSum = 0.0; intervalCount = 0; lastTimestampNs = 0; framesAtLastTick = 0
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try { s.setRepeatingRequest(b.build(), captureCallback, bgHandler) }
                    catch (e: Exception) { show("setRepeatingRequest failed: $e") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { show("Session configuration failed") }
            }, bgHandler)
        } catch (e: Exception) {
            show("startSession failed: $e")
        }
    }

    private fun applySettings(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)
        b.set(CaptureRequest.EDGE_MODE, edgeMode)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, if (fixedFps) Range(30, 30) else Range(5, 30))
        val key = key3dnrRequest
        if (key != null) {
            try {
                b.set(key, dnr as Any)                 // as a single integer
            } catch (e: Exception) {
                try {
                    b.set(key, intArrayOf(dnr))        // or as an integer array, if the camera lists it that way
                } catch (e2: Exception) {
                    show("3DNR key not accepted: $e2")
                }
            }
        }
    }

    /** Change the settings without restarting the session. */
    private fun applyLive() {
        val b = builder
        val s = session
        refreshButtons()
        if (b == null || s == null) return
        try {
            applySettings(b)
            s.setRepeatingRequest(b.build(), captureCallback, bgHandler)
        } catch (e: Exception) {
            show("Update failed: $e")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            depthResult = result.get(CaptureResult.REQUEST_PIPELINE_DEPTH)?.toInt()
            nrResult = result.get(CaptureResult.NOISE_REDUCTION_MODE)
            edgeResult = result.get(CaptureResult.EDGE_MODE)
            fpsRangeResult = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
            dnrResult = try {
                val kr = key3dnrResult
                when (val v = if (kr != null) result.get(kr) else null) {
                    is Int -> v
                    is IntArray -> v.firstOrNull()
                    else -> null
                }
            } catch (e: Exception) { null }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            if (lastTimestampNs != 0L && ts > lastTimestampNs) {
                intervalMsSum += (ts - lastTimestampNs) / 1e6
                intervalCount++
            }
            lastTimestampNs = ts
            frameCount++
        }
    }

    private fun closeSession() {
        try { session?.close() } catch (e: Exception) { Log.w(tag, "close session: $e") }
        session = null
    }

    private fun closeCamera() {
        closeSession()
        try { cameraDevice?.close() } catch (e: Exception) { Log.w(tag, "close camera: $e") }
        cameraDevice = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openCamera()
        else show("Camera permission was refused")
    }

    // ------------------------------------------------------------------ user interface

    private fun addButton(label: () -> String, onClick: () -> Unit) {
        val b = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick(); refreshButtons() }
        }
        controls.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.add(Pair(b, label))
    }

    private fun refreshButtons() {
        for ((b, label) in buttons) b.text = label()
    }

    private fun toggleOverlay() {
        val v = if (infoView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        infoView.visibility = v
        controls.visibility = v
    }

    private fun nextSize() {
        if (sizes.size < 2) return
        sizeIndex = (sizeIndex + 1) % sizes.size
        // The surface size changes, surfaceChanged() then restarts the session.
        surfaceView.holder.setFixedSize(sizes[sizeIndex].width, sizes[sizeIndex].height)
    }

    private fun modeName(m: Int) = when (m) { 0 -> "OFF"; 1 -> "FAST"; else -> "HQ" }

    private fun sizeName(): String = if (sizes.isEmpty()) "?" else "${sizes[sizeIndex].width}x${sizes[sizeIndex].height}"

    private fun show(text: String) {
        Log.w(tag, text)
        message = text
    }

    /** Updates the on-screen text twice a second. */
    private val ticker = object : Runnable {
        override fun run() {
            val frames = frameCount
            val fps = (frames - framesAtLastTick) * 2.0
            framesAtLastTick = frames
            val mean = if (intervalCount > 0) intervalMsSum / intervalCount else 0.0
            val sb = StringBuilder()
            sb.append("pipelineDepth (result): ").append(depthResult ?: "-")
                .append("   max (static): ").append(maxDepthStatic ?: "-").append('\n')
            sb.append("frames/s: ").append(String.format("%.1f", fps))
                .append("   mean interval: ").append(String.format("%.1f", mean)).append(" ms\n")
            sb.append("3DNR  asked ").append(dnr).append(" / result ").append(dnrResult ?: "-").append('\n')
            sb.append("NR    asked ").append(modeName(nrMode)).append(" / result ").append(nrResult?.let { modeName(it) } ?: "-").append('\n')
            sb.append("EDGE  asked ").append(modeName(edgeMode)).append(" / result ").append(edgeResult?.let { modeName(it) } ?: "-").append('\n')
            sb.append("fps range asked ").append(if (fixedFps) "[30,30]" else "[5,30]")
                .append(" / result ").append(fpsRangeResult ?: "-").append('\n')
            sb.append("size ").append(sizeName()).append("   intent ").append(if (recordIntent) "RECORD" else "PREVIEW")
                .append("   camera ").append(cameraId ?: "-").append('\n')
            if (message.isNotEmpty()) sb.append("! ").append(message)
            infoView.text = sb.toString()
            uiHandler.postDelayed(this, 500)
        }
    }
}
