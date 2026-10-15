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
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
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
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AX12 camera post-processing test (Camera2).
 *
 * One preview stream (a SurfaceView), no extra image copies. A scrollable panel on the right has a switch
 * for every post-processing setting this camera accepts: the standard Camera2 modes, manual sensor values
 * and MediaTek's own vendor keys. Every switch starts at DEF, which leaves the camera's default untouched,
 * and cycles through the values the camera offers.
 *
 * The text at the top left shows what the camera layer reports back: pipeline depth, frames per second and the
 * settings it actually applied.
 *
 * SWEEP tries every value of every switch one at a time (others at DEF), waits 2.5 s for each, and writes a
 * report. NOTE appends the current settings and readings to a notes file (use it with each stopwatch photo).
 * Tap the video, or press HIDE, to hide or show the overlay (hide it when you photograph the screen).
 *
 * Files are written to /sdcard/Android/data/com.example.ax12cam/files/ (sweep.txt and notes.txt).
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private val tag = "AX12CAM"

    // ---------------------------------------------------------------- the switch model

    /** One switch. Index 0 is always DEF = leave the camera's default. */
    inner class Sw(
        val name: String,
        val labels: List<String>,
        val isParam: Boolean = false,                 // a parameter for another switch, skipped by SWEEP
        val vendorName: String? = null,               // short name of a MediaTek vendor key, shown with its result
        val apply: (CaptureRequest.Builder, Int) -> Unit = { _, _ -> }
    ) {
        var idx = 0
        var button: Button? = null
        fun text(): String {
            val res = if (vendorName != null) vendorResults[vendorName] else null
            return "$name: ${labels[idx]}" + if (res != null) "  (now $res)" else ""
        }
    }

    private val switches = ArrayList<Sw>()
    private val vendorResults = ConcurrentHashMap<String, String>()
    private val vendorRequestKeys = HashMap<String, CaptureRequest.Key<Any>>()
    private val vendorResultKeys = HashMap<String, CaptureResult.Key<Any>>()

    private val expNs = longArrayOf(4_000_000L, 8_000_000L, 16_000_000L, 2_000_000L, 1_000_000L)
    private val isoValues = intArrayOf(100, 200, 400, 800, 1600)
    private val frameNs = longArrayOf(16_666_667L, 33_333_333L, 20_000_000L, 25_000_000L, 40_000_000L)
    private lateinit var swExposure: Sw
    private lateinit var swIso: Sw
    private lateinit var swFrame: Sw

    private val fpsRanges = listOf(Range(5, 30), Range(30, 30), Range(15, 15), Range(20, 20), Range(15, 20), Range(60, 60))

    private fun buildStandardSwitches() {
        val off = "OFF"
        // --- the automatic algorithms
        add(Sw("CTRL MODE", listOf("DEF", off, "AUTO", "SCENE", "OFF_KEEP")) { b, i -> b.set(CaptureRequest.CONTROL_MODE, i - 1) })
        add(Sw("AE MODE", listOf("DEF", off, "ON")) { b, i -> b.set(CaptureRequest.CONTROL_AE_MODE, i - 1) })
        add(Sw("AWB MODE", listOf("DEF", off, "AUTO")) { b, i -> b.set(CaptureRequest.CONTROL_AWB_MODE, i - 1) })
        add(Sw("ANTIBAND", listOf("DEF", off, "50Hz", "60Hz", "AUTO")) { b, i -> b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, i - 1) })
        add(Sw("VIDEO STAB", listOf("DEF", off, "ON")) { b, i -> b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, i - 1) })
        // --- image processing blocks
        add(Sw("NOISE RED", listOf("DEF", off, "FAST", "HQ")) { b, i -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, i - 1) })
        add(Sw("EDGE", listOf("DEF", off, "FAST", "HQ")) { b, i -> b.set(CaptureRequest.EDGE_MODE, i - 1) })
        add(Sw("HOT PIXEL", listOf("DEF", "FAST", "HQ")) { b, i -> b.set(CaptureRequest.HOT_PIXEL_MODE, i) })
        add(Sw("TONEMAP", listOf("DEF", "FAST", "HQ", "LINEAR")) { b, i ->
            if (i == 3) {
                val lin = floatArrayOf(0f, 0f, 1f, 1f)
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(lin, lin, lin))
            } else {
                b.set(CaptureRequest.TONEMAP_MODE, i)
            }
        })
        add(Sw("COLOR CORR", listOf("DEF", "FAST", "HQ", "MATRIX 1:1")) { b, i ->
            if (i == 3) {
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)))
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(1f, 1f, 1f, 1f))
            } else {
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, i)
            }
        })
        add(Sw("FACE DET", listOf("DEF", off, "SIMPLE")) { b, i -> b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, i - 1) })
        add(Sw("BLACK LOCK", listOf("DEF", off, "ON")) { b, i -> b.set(CaptureRequest.BLACK_LEVEL_LOCK, i == 2) })
        add(Sw("ZSL", listOf("DEF", off, "ON")) { b, i -> b.set(CaptureRequest.CONTROL_ENABLE_ZSL, i == 2) })
        // --- timing
        add(Sw("FPS RANGE", listOf("DEF") + fpsRanges.map { "${it.lower}-${it.upper}" }) { b, i ->
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRanges[i - 1])
        })
        add(Sw("INTENT", listOf("DEF", "PREVIEW", "RECORD", "MANUAL", "CUSTOM")) { b, i ->
            b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, intArrayOf(1, 3, 6, 0)[i - 1])
        })
        // --- manual sensor values (used when MANUAL SENSOR is ON)
        swExposure = Sw("  exposure", listOf("4 ms", "8 ms", "16 ms", "2 ms", "1 ms"), isParam = true)
        swIso = Sw("  ISO", listOf("100", "200", "400", "800", "1600"), isParam = true)
        swFrame = Sw("  frame time", listOf("16.7 ms", "33.3 ms", "20 ms", "25 ms", "40 ms"), isParam = true)
        add(Sw("MANUAL SENSOR", listOf("DEF", "ON")) { b, _ ->
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs[swExposure.idx])
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoValues[swIso.idx])
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs[swFrame.idx])
        })
        add(swExposure)
        add(swIso)
        add(swFrame)
    }

    /** MediaTek vendor keys looked for by name in the camera's own key list. */
    private val vendorNames = listOf(
        "3dnrmode", "hdrMode", "eismode", "aismode", "mfbmode", "asdmode", "gesturemode", "smiledetectmode",
        "forceface3a", "aeIsoSpeed", "aeMeteringMode", "awbValue", "recordState", "zsl.mode"
    )

    private fun add(sw: Sw) {
        switches.add(sw)
        if (panel != null) addSwitchButton(sw)
    }

    private fun setVendor(b: CaptureRequest.Builder, key: CaptureRequest.Key<Any>, v: Int) {
        var lastError = ""
        val attempts: List<Any> = listOf(v, v.toByte(), intArrayOf(v), byteArrayOf(v.toByte()), v.toLong())
        for (a in attempts) {
            try {
                b.set(key, a)
                return
            } catch (e: Exception) {
                lastError = e.toString()
            }
        }
        show("vendor key ${key.name} rejected: $lastError")
    }

    // ---------------------------------------------------------------- views and state

    private lateinit var surfaceView: SurfaceView
    private lateinit var infoView: TextView
    private lateinit var overlay: FrameLayout
    private var panel: LinearLayout? = null
    private lateinit var scroll: ScrollView
    private lateinit var barView: LinearLayout
    private lateinit var btnSweep: Button
    private lateinit var btnTemplate: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var templateIdx = 0                        // 0 PREVIEW, 1 RECORD, 2 MANUAL
    private val templates = intArrayOf(CameraDevice.TEMPLATE_PREVIEW, CameraDevice.TEMPLATE_RECORD, CameraDevice.TEMPLATE_MANUAL)
    private val templateNames = listOf("PREVIEW", "RECORD", "MANUAL")
    private var sizes: List<Size> = emptyList()
    private var sizeIdx = 0
    private var maxDepthStatic: Int? = null

    // Live readout
    @Volatile private var depthResult: Int? = null
    @Volatile private var frameDurResult: Long? = null
    @Volatile private var exposureResult: Long? = null
    @Volatile private var ctrlResult: Int? = null
    @Volatile private var aeResult: Int? = null
    @Volatile private var awbResult: Int? = null
    @Volatile private var nrResult: Int? = null
    @Volatile private var edgeResult: Int? = null
    @Volatile private var tonemapResult: Int? = null
    @Volatile private var fpsRangeResult: Range<Int>? = null
    @Volatile private var frameCount = 0L
    @Volatile private var lastTimestampNs = 0L
    @Volatile private var intervalMsSum = 0.0
    @Volatile private var intervalCount = 0
    private var framesAtLastTick = 0L
    @Volatile private var message = ""

    // Sweep state
    private var sweepRunning = false
    private val sweepSteps = ArrayList<Pair<Int, Int>>()
    private var sweepPos = 0
    private val sweepReport = StringBuilder()
    private var baselineDepth: Int? = null

    private fun outDir(): File = (getExternalFilesDir(null) ?: filesDir).also { it.mkdirs() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnClickListener { toggleOverlay() }
        root.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        infoView = TextView(this).apply {
            setTextColor(Color.GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setBackgroundColor(0x99000000.toInt())
            setPadding(10, 6, 10, 6)
        }
        overlay.addView(infoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // Scrollable list of switches on the right
        val widthPx = (resources.displayMetrics.widthPixels * 0.40f).toInt()
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = p
        scroll = ScrollView(this).apply { setBackgroundColor(0x88000000.toInt()); addView(p) }
        overlay.addView(scroll, FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))

        // Bottom bar on the left: SWEEP, NOTE, TEMPLATE, SIZE, RESET, HIDE
        barView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0x99000000.toInt()) }
        btnSweep = bar("SWEEP") { toggleSweep() }
        bar("NOTE") { writeNote() }
        btnTemplate = bar("TEMPLATE") { templateIdx = (templateIdx + 1) % templates.size; refreshAll(); submit() }
        bar("SIZE") { nextSize() }
        bar("RESET") { for (s in switches) s.idx = 0; refreshAll(); submit() }
        bar("HIDE") { toggleOverlay() }
        overlay.addView(barView, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.60f).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        setContentView(root)
        buildStandardSwitches()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam-bg").also { it.start() }
        bgHandler = Handler(bgThread.looper)
        uiHandler.post(ticker)
        if (surfaceView.holder.surface?.isValid == true) openCamera()
    }

    override fun onPause() {
        sweepRunning = false
        uiHandler.removeCallbacksAndMessages(null)
        closeCamera()
        bgThread.quitSafely()
        super.onPause()
    }

    // ---------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (sizes.isNotEmpty()) holder.setFixedSize(sizes[sizeIdx].width, sizes[sizeIdx].height)
        openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (cameraDevice != null && sizes.isNotEmpty() && width == sizes[sizeIdx].width && height == sizes[sizeIdx].height) startSession()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        closeSession()
    }

    // ---------------------------------------------------------------- camera

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
            findVendorKeys(chars)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val all = map?.getOutputSizes(SurfaceHolder::class.java)?.toList() ?: emptyList()
            val wanted = ArrayList<Size>()
            for (w in listOf(Size(1920, 1080), Size(1280, 720))) if (all.contains(w)) wanted.add(w)
            if (wanted.isEmpty() && all.isNotEmpty()) wanted.add(all.maxByOrNull { it.width * it.height }!!)
            sizes = wanted
            if (sizes.isEmpty()) { show("The camera lists no SurfaceView sizes."); return }
            if (sizeIdx >= sizes.size) sizeIdx = 0
            surfaceView.holder.setFixedSize(sizes[sizeIdx].width, sizes[sizeIdx].height)
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    if (surfaceView.holder.surface?.isValid == true) startSession()
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null; session = null; show("Camera disconnected") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null; session = null; show("Camera error $error") }
            }, bgHandler)
        } catch (e: CameraAccessException) {
            show("openCamera failed: $e")
        } catch (e: SecurityException) {
            show("No camera permission: $e")
        }
    }

    /** Looks up the MediaTek vendor keys by name in the camera's own key lists and adds a switch for each. */
    private fun findVendorKeys(chars: CameraCharacteristics) {
        if (vendorRequestKeys.isNotEmpty()) return            // already done
        @Suppress("UNCHECKED_CAST")
        val reqKeys = chars.availableCaptureRequestKeys as List<CaptureRequest.Key<Any>>
        @Suppress("UNCHECKED_CAST")
        val resKeys = chars.availableCaptureResultKeys as List<CaptureResult.Key<Any>>
        val found = ArrayList<Sw>()
        for (n in vendorNames) {
            val rk = reqKeys.firstOrNull { it.name.startsWith("com.mediatek.") && it.name.endsWith(".$n") } ?: continue
            vendorRequestKeys[n] = rk
            resKeys.firstOrNull { it.name == rk.name }?.let { vendorResultKeys[n] = it }
            found.add(Sw("MTK $n", listOf("DEF", "0", "1", "2", "3"), vendorName = n) { b, i -> setVendor(b, rk, i - 1) })
        }
        runOnUiThread {
            val p = panel ?: return@runOnUiThread
            if (found.isNotEmpty()) {
                p.addView(TextView(this).apply { text = "-- MediaTek keys --"; setTextColor(Color.YELLOW); textSize = 11f; setPadding(8, 8, 0, 0) })
            }
            for (sw in found) add(sw)
            refreshAll()
        }
    }

    private fun startSession() {
        val device = cameraDevice ?: return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) return
        try {
            closeSession()
            resetCounters()
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; submit() }
                override fun onConfigureFailed(s: CameraCaptureSession) { show("Session configuration failed") }
            }, bgHandler)
        } catch (e: Exception) {
            show("startSession failed: $e")
        }
    }

    /** Builds a fresh request from the template plus every switch that is not on DEF, and starts it repeating. */
    private fun submit() {
        val device = cameraDevice ?: return
        val s = session ?: return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) return
        try {
            val b = device.createCaptureRequest(templates[templateIdx])
            b.addTarget(surface)
            for (sw in switches) if (!sw.isParam && sw.idx > 0) {
                try { sw.apply(b, sw.idx) } catch (e: Exception) { show("${sw.name}: $e") }
            }
            s.setRepeatingRequest(b.build(), captureCallback, bgHandler)
        } catch (e: Exception) {
            show("submit failed: $e")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            depthResult = result.get(CaptureResult.REQUEST_PIPELINE_DEPTH)?.toInt()
            frameDurResult = result.get(CaptureResult.SENSOR_FRAME_DURATION)
            exposureResult = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ctrlResult = result.get(CaptureResult.CONTROL_MODE)
            aeResult = result.get(CaptureResult.CONTROL_AE_MODE)
            awbResult = result.get(CaptureResult.CONTROL_AWB_MODE)
            nrResult = result.get(CaptureResult.NOISE_REDUCTION_MODE)
            edgeResult = result.get(CaptureResult.EDGE_MODE)
            tonemapResult = result.get(CaptureResult.TONEMAP_MODE)
            fpsRangeResult = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
            for ((n, k) in vendorResultKeys) {
                try {
                    val v = result.get(k)
                    vendorResults[n] = when (v) {
                        is IntArray -> v.joinToString(",")
                        is ByteArray -> v.joinToString(",")
                        null -> "-"
                        else -> v.toString()
                    }
                } catch (e: Exception) { /* ignore */ }
            }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            if (lastTimestampNs != 0L && ts > lastTimestampNs) { intervalMsSum += (ts - lastTimestampNs) / 1e6; intervalCount++ }
            lastTimestampNs = ts
            frameCount++
        }
    }

    private fun resetCounters() {
        frameCount = 0; framesAtLastTick = 0; intervalMsSum = 0.0; intervalCount = 0; lastTimestampNs = 0; depthResult = null
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

    // ---------------------------------------------------------------- user interface

    private fun bar(label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { textSize = 10f; isAllCaps = false; text = label; setOnClickListener { onClick() } }
        barView.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return b
    }

    private fun addSwitchButton(sw: Sw) {
        val p = panel ?: return
        val b = Button(this).apply {
            textSize = 11f
            isAllCaps = false
            setOnClickListener {
                sw.idx = (sw.idx + 1) % sw.labels.size
                refreshAll()
                if (!sweepRunning) submit()
            }
        }
        sw.button = b
        p.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshAll() {
        for (sw in switches) sw.button?.text = sw.text()
        btnTemplate.text = "TMPL " + templateNames[templateIdx]
        btnSweep.text = if (sweepRunning) "STOP" else "SWEEP"
    }

    private fun toggleOverlay() {
        overlay.visibility = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun nextSize() {
        if (sizes.size < 2) return
        sizeIdx = (sizeIdx + 1) % sizes.size
        surfaceView.holder.setFixedSize(sizes[sizeIdx].width, sizes[sizeIdx].height)     // surfaceChanged() restarts the session
    }

    private fun show(text: String) {
        Log.w(tag, text)
        message = text
    }

    private fun sizeName(): String = if (sizes.isEmpty()) "?" else "${sizes[sizeIdx].width}x${sizes[sizeIdx].height}"

    private fun settingsSummary(): String {
        val on = switches.filter { !it.isParam && it.idx > 0 }.joinToString(", ") { "${it.name.trim()}=${it.labels[it.idx]}" }
        val manual = if (switches.any { it.name == "MANUAL SENSOR" && it.idx > 0 })
            " [exp ${swExposure.labels[swExposure.idx]}, ISO ${swIso.labels[swIso.idx]}, frame ${swFrame.labels[swFrame.idx]}]" else ""
        return "${templateNames[templateIdx]} ${sizeName()} | " + (if (on.isEmpty()) "all DEF" else on) + manual
    }

    private fun readings(): String {
        val fps = if (intervalCount > 0) 1000.0 / (intervalMsSum / intervalCount) else 0.0
        val mean = if (intervalCount > 0) intervalMsSum / intervalCount else 0.0
        return "depth=${depthResult ?: "-"} fps=${"%.1f".format(Locale.US, fps)} interval=${"%.1f".format(Locale.US, mean)}ms " +
            "frameDur=${frameDurResult?.let { "%.1f".format(Locale.US, it / 1e6) } ?: "-"}ms"
    }

    // ---------------------------------------------------------------- NOTE and SWEEP

    private fun writeNote() {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " | " + settingsSummary() + " | " + readings() + "\n"
        try { File(outDir(), "notes.txt").appendText(line); show("note saved") } catch (e: Exception) { show("note failed: $e") }
        Log.i(tag, line)
    }

    private fun toggleSweep() {
        if (sweepRunning) { sweepRunning = false; refreshAll(); show("sweep stopped"); return }
        if (cameraDevice == null || session == null) { show("Start the camera first"); return }
        for (s in switches) if (!s.isParam) s.idx = 0
        sweepSteps.clear()
        sweepSteps.add(Pair(-1, 0))                                        // baseline: everything on DEF
        for ((si, sw) in switches.withIndex()) {
            if (sw.isParam || sw.name == "MANUAL SENSOR") continue
            for (vi in 1 until sw.labels.size) sweepSteps.add(Pair(si, vi))
        }
        val manualIdx = switches.indexOfFirst { it.name == "MANUAL SENSOR" }
        if (manualIdx >= 0) sweepSteps.add(Pair(manualIdx, 1))
        sweepPos = 0
        baselineDepth = null
        sweepReport.setLength(0)
        sweepReport.append("AX12 camera sweep, ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())).append('\n')
        sweepReport.append("Each line: one switch changed, all others on DEF. Waits 2.5 s per step.\n")
        sweepReport.append("template ").append(templateNames[templateIdx]).append(", size ").append(sizeName()).append("\n\n")
        sweepRunning = true
        refreshAll()
        sweepNext()
    }

    private fun sweepNext() {
        if (!sweepRunning) return
        if (sweepPos >= sweepSteps.size) { finishSweep(); return }
        for (s in switches) if (!s.isParam) s.idx = 0
        val (si, vi) = sweepSteps[sweepPos]
        if (si >= 0) switches[si].idx = vi
        refreshAll()
        message = ""
        resetCounters()
        if (cameraDevice == null) openCamera() else submit()
        uiHandler.postDelayed({
            if (!sweepRunning) return@postDelayed
            val label = if (si < 0) "BASELINE (all DEF)" else "${switches[si].name.trim()} = ${switches[si].labels[vi]}"
            val depth = depthResult
            if (si < 0) baselineDepth = depth
            val base = baselineDepth
            val mark = if (depth != null && base != null && depth != base) "   <== DEPTH CHANGED" else ""
            val err = if (message.isNotEmpty() && message != "note saved") "   ! $message" else ""
            sweepReport.append(label.padEnd(34)).append(" | ").append(readings()).append(mark).append(err).append('\n')
            sweepPos++
            sweepNext()
        }, 2500)
    }

    private fun finishSweep() {
        sweepRunning = false
        for (s in switches) if (!s.isParam) s.idx = 0
        refreshAll()
        if (cameraDevice == null) openCamera() else submit()
        try {
            File(outDir(), "sweep.txt").writeText(sweepReport.toString())
            show("sweep done, saved sweep.txt")
        } catch (e: Exception) {
            show("sweep done, save failed: $e")
        }
        Log.i(tag, sweepReport.toString())
    }

    // ---------------------------------------------------------------- the text on screen (twice a second)

    private val ticker = object : Runnable {
        override fun run() {
            val frames = frameCount
            val fps = (frames - framesAtLastTick) * 2.0
            framesAtLastTick = frames
            val mean = if (intervalCount > 0) intervalMsSum / intervalCount else 0.0
            val sb = StringBuilder()
            sb.append("pipelineDepth: ").append(depthResult ?: "-").append("  (max ").append(maxDepthStatic ?: "-").append(")\n")
            sb.append("frames/s: ").append("%.1f".format(Locale.US, fps)).append("  interval: ").append("%.1f".format(Locale.US, mean)).append(" ms\n")
            sb.append("frame time: ").append(frameDurResult?.let { "%.1f".format(Locale.US, it / 1e6) } ?: "-")
                .append(" ms  exposure: ").append(exposureResult?.let { "%.1f".format(Locale.US, it / 1e6) } ?: "-").append(" ms\n")
            sb.append("result  ctrl ").append(ctrlResult ?: "-").append(" ae ").append(aeResult ?: "-").append(" awb ").append(awbResult ?: "-")
                .append(" nr ").append(nrResult ?: "-").append(" edge ").append(edgeResult ?: "-").append(" tone ").append(tonemapResult ?: "-").append('\n')
            sb.append("fps range: ").append(fpsRangeResult ?: "-").append("  ").append(sizeName()).append("  cam ").append(cameraId ?: "-").append('\n')
            if (sweepRunning) sb.append("SWEEP step ").append(sweepPos + 1).append(" / ").append(sweepSteps.size).append('\n')
            if (message.isNotEmpty()) sb.append("! ").append(message)
            infoView.text = sb.toString()
            for (sw in switches) sw.button?.text = sw.text()
            uiHandler.postDelayed(this, 500)
        }
    }
}
