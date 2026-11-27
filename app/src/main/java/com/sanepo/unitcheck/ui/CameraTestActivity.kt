package com.sanepo.unitcheck.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivityCameraTestBinding
import com.sanepo.unitcheck.engine.CameraEnumerator
import com.sanepo.unitcheck.model.CameraLensInfo
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang
import java.util.Locale
import kotlin.math.abs

class CameraTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraTestBinding
    private lateinit var cameraManager: CameraManager
    private var lenses: List<CameraLensInfo> = emptyList()
    private var currentLensIndex = 0
    private var torchEnabled = false
    private var currentZoomRatio = 1.0f
    private var currentActiveArray: Rect? = null
    private var currentMinFocusDistance = 0f
    private var zoomOptions: List<Float> = listOf(1.0f)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    private val verifiedLenses = LinkedHashSet<String>()
    private val testedZoomLabels = LinkedHashSet<String>()
    private var currentLensFrames = 0
    private var currentLensHasSignal = false
    private var afVerifiedSuccess = false
    private var spotGuideActive = false
    private var lastAfBadgeState = -1

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (binding.cameraPreviewTexture.isAvailable && lenses.isNotEmpty()) {
                openLens(currentLensIndex)
            }
        } else {
            binding.tvCameraInfo.text = Lang.t(
                this,
                "ERROR: Izin Kamera ditolak. Berikan izin Kamera agar dapat cek semua lensa.",
                "ERROR: CAMERA permission denied. Grant permission to test physical lenses."
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCameraTest) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        binding.btnBackCamera.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvCameraTitle.text = Lang.t(this, "Kamera, Jamur & Fokus", "Camera, Spot & AF")
        binding.btnSpotCheckGuide.text = Lang.t(this, "CEK JAMUR", "SPOT/AF")
        binding.btnTorchToggle.text = "TORCH OFF"
        binding.btnCameraPass.text = Lang.t(this, "KAMERA PASS", "CAMERA PASS")
        binding.btnCameraFail.text = Lang.t(this, "KAMERA FAIL", "CAMERA FAIL")
        binding.tvSpotCheckBanner.text = Lang.t(
            this,
            "PANDUAN JAMUR & OIS: Arahkan kamera ke bidang PUTIH TERANG (kertas/layar HP lain) di Zoom 0.6x/1x/2x untuk cek titik jamur ungu/hitam. Lalu arahkan ke objek dekat (~10cm) & TAP layar untuk tes kunci Autofokus + cek apakah OIS bergetar/bunyi krek.",
            "SENSOR SPOT & OIS GUIDE: Point lens at bright white paper/screen (0.6x/1x/2x) to spot dust/fungus dots. Then tap near a close-up object (~10cm) to verify AF lock & OIS stability."
        )

        lenses = CameraEnumerator.enumerate(this)
        DiagnosticStore.cameraLenses = lenses

        buildLensButtons()
        if (lenses.isEmpty()) {
            binding.tvCameraInfo.text = Lang.t(this, "Tidak ada modul kamera ditemukan.", "No camera hardware detected.")
        } else {
            buildZoomButtons(lenses[currentLensIndex])
        }

        binding.btnBackCamera.setOnClickListener { finish() }
        binding.btnTorchToggle.setOnClickListener { toggleTorch() }
        binding.btnSpotCheckGuide.setOnClickListener {
            spotGuideActive = !spotGuideActive
            binding.tvSpotCheckBanner.visibility = if (spotGuideActive) View.VISIBLE else View.GONE
            binding.btnSpotCheckGuide.setBackgroundResource(if (spotGuideActive) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            binding.btnSpotCheckGuide.setTextColor(
                ContextCompat.getColor(this, if (spotGuideActive) R.color.text_on_accent else R.color.text_primary)
            )
        }

        binding.cameraPreviewTexture.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                triggerTapAutofocus(ev.x, ev.y, v.width, v.height)
            }
            true
        }

        binding.btnCameraPass.setOnClickListener {
            val hasBack = lenses.any { it.facingLabel == "BACK" }
            val hasFront = lenses.any { it.facingLabel == "FRONT" }
            val backVerified = verifiedLenses.any { id ->
                lenses.firstOrNull { it.cameraId == id }?.facingLabel == "BACK"
            }
            val frontVerified = verifiedLenses.any { id ->
                lenses.firstOrNull { it.cameraId == id }?.facingLabel == "FRONT"
            }
            if ((hasBack && !backVerified) || (hasFront && !frontVerified)) {
                val msg = Lang.t(
                    this,
                    "Wajib tampilkan frame gambar nyata minimal 1 kamera BELAKANG dan 1 kamera DEPAN sebelum PASS!",
                    "Must stream live non-black frames on at least 1 BACK and 1 FRONT lens before PASS!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvCameraInfo.text = msg
                return@setOnClickListener
            }
            val zoomNote = if (testedZoomLabels.isNotEmpty()) " | Zoom: ${testedZoomLabels.joinToString(",")}" else ""
            val afNote = if (afVerifiedSuccess) " | AF=LOCKED" else ""
            DiagnosticStore.updateItem(
                "camera_lenses",
                TestStatus.PASS,
                "Verified ${verifiedLenses.size}/${lenses.size} active lenses$zoomNote$afNote | Back+Front OK"
            )
            CodWizardFlow.advanceOrFinish(this)
        }
        binding.btnCameraFail.setOnClickListener {
            DiagnosticStore.updateItem(
                "camera_lenses",
                TestStatus.FAIL,
                "Camera defect / black frame / focus / spot / torch failure flagged"
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        binding.cameraPreviewTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                onPreviewFrameArrived()
            }
        }
    }

    private fun onPreviewFrameArrived() {
        val lens = lenses.getOrNull(currentLensIndex) ?: return
        currentLensFrames++
        if (currentLensHasSignal) return

        // Validate real sensor frame data after stream warms up (>=5 frames) so dead/unplugged black-frame HALs cannot pass falsely.
        if (currentLensFrames >= 5 && (currentLensFrames == 5 || currentLensFrames % 15 == 0)) {
            val luma = samplePreviewLuma()
            if (luma >= 1.2f) {
                currentLensHasSignal = true
                verifiedLenses.add(lens.cameraId)
                runOnUiThread {
                    if (!isDestroyed && !afVerifiedSuccess && currentMinFocusDistance <= 0f) {
                        binding.tvAfLiveBadge.text = "STREAM OK (FIXED)"
                        binding.tvAfLiveBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                        binding.tvAfLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.status_pass_fg))
                    } else if (!isDestroyed && currentLensFrames <= 6) {
                        binding.tvAfLiveBadge.text = "STREAM OK | TAP AF"
                        binding.tvAfLiveBadge.setBackgroundResource(R.drawable.bg_badge_ready)
                        binding.tvAfLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    }
                }
            } else if (currentLensFrames >= 20) {
                runOnUiThread {
                    if (!isDestroyed) {
                        binding.tvAfLiveBadge.text = "BLACK FRAME (0% LUMA)!"
                        binding.tvAfLiveBadge.setBackgroundResource(R.drawable.bg_badge_fail)
                        binding.tvAfLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.status_fail_fg))
                    }
                }
            }
        }
    }

    private fun samplePreviewLuma(): Float {
        return try {
            val bmp = binding.cameraPreviewTexture.getBitmap(16, 16) ?: return 10f
            var sum = 0L
            val pixels = IntArray(256)
            bmp.getPixels(pixels, 0, 16, 0, 0, 16, 16)
            bmp.recycle()
            for (px in pixels) {
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                sum += (r + g + b) / 3
            }
            sum.toFloat() / pixels.size.toFloat()
        } catch (_: Throwable) {
            10f
        }
    }

    private fun buildLensButtons() {
        binding.rowLensButtons.removeAllViews()
        for (idx in lenses.indices) {
            val lens = lenses[idx]
            val btn = Button(this).apply {
                text = "ID ${lens.cameraId} (${lens.facingLabel})"
                textSize = 12f
                isFocusable = true
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setBackgroundResource(if (idx == currentLensIndex) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (46 * resources.displayMetrics.density).toInt()
                )
                lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setPadding(24, 0, 24, 0)
                setOnClickListener {
                    currentLensIndex = idx
                    currentZoomRatio = 1.0f.coerceIn(lens.minZoomRatio, lens.maxZoomRatio)
                    resetTorchOnSwitch()
                    refreshLensButtonHighlights()
                    buildZoomButtons(lens)
                    openLens(idx)
                }
            }
            binding.rowLensButtons.addView(btn)
        }
    }

    private fun resetTorchOnSwitch() {
        if (torchEnabled) {
            torchEnabled = false
            binding.btnTorchToggle.text = "TORCH OFF"
            lenses.firstOrNull { it.flashSupported }?.let {
                CameraEnumerator.setTorch(this, it.cameraId, false)
            }
        }
    }

    private fun buildZoomButtons(lens: CameraLensInfo) {
        binding.rowZoomButtons.removeAllViews()
        val presets = LinkedHashSet<Float>()
        if (lens.minZoomRatio < 0.9f) presets.add(lens.minZoomRatio)
        val candidates = listOf(0.6f, 1.0f, 2.0f, 3.0f, 5.0f)
        for (z in candidates) {
            if (z >= lens.minZoomRatio - 0.05f && z <= lens.maxZoomRatio + 0.05f) {
                val clamped = z.coerceIn(lens.minZoomRatio, lens.maxZoomRatio)
                if (presets.none { abs(it - clamped) < 0.12f }) {
                    presets.add(clamped)
                }
            }
        }
        if (presets.none { abs(it - 1.0f) < 0.1f } && 1.0f in lens.minZoomRatio..lens.maxZoomRatio) {
            presets.add(1.0f)
        }
        zoomOptions = presets.sorted()
        currentZoomRatio = currentZoomRatio.coerceIn(lens.minZoomRatio, lens.maxZoomRatio)

        for (ratio in zoomOptions) {
            val label = formatZoomLabel(ratio)
            val btn = Button(this).apply {
                text = label
                textSize = 11f
                isFocusable = true
                val selected = abs(ratio - currentZoomRatio) < 0.08f
                setBackgroundResource(if (selected) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.text_on_accent else R.color.text_primary
                    )
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (44 * resources.displayMetrics.density).toInt()
                )
                lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setPadding(20, 0, 20, 0)
                setOnClickListener {
                    testedZoomLabels.add(label)
                    applyZoom(ratio)
                }
            }
            binding.rowZoomButtons.addView(btn)
        }
    }

    private fun formatZoomLabel(ratio: Float): String {
        val clean = if (ratio < 1.0f) {
            String.format(Locale.US, "%.1fx (UW)", ratio)
        } else if (abs(ratio - ratio.toInt()) < 0.05f) {
            "${ratio.toInt()}x"
        } else {
            String.format(Locale.US, "%.1fx", ratio)
        }
        return clean
    }

    private fun refreshZoomHighlights() {
        for (i in 0 until binding.rowZoomButtons.childCount) {
            val child = binding.rowZoomButtons.getChildAt(i) as? Button ?: continue
            val ratio = zoomOptions.getOrNull(i) ?: continue
            val selected = abs(ratio - currentZoomRatio) < 0.08f
            child.setBackgroundResource(if (selected) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            child.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.text_on_accent else R.color.text_primary)
            )
        }
    }

    private fun applyZoomToBuilder(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        } else {
            val rect = currentActiveArray ?: return
            if (currentZoomRatio <= 1.0f) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, rect)
            } else {
                val cw = (rect.width() / currentZoomRatio).toInt().coerceAtLeast(1)
                val ch = (rect.height() / currentZoomRatio).toInt().coerceAtLeast(1)
                val left = rect.left + (rect.width() - cw) / 2
                val top = rect.top + (rect.height() - ch) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cw, top + ch))
            }
        }
    }

    private fun applyZoom(ratio: Float) {
        val lens = lenses.getOrNull(currentLensIndex) ?: return
        currentZoomRatio = ratio.coerceIn(lens.minZoomRatio, lens.maxZoomRatio)
        refreshZoomHighlights()
        val builder = previewBuilder ?: return
        val session = captureSession ?: return
        try {
            applyZoomToBuilder(builder)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (_: Throwable) {
        }
    }

    private fun refreshLensButtonHighlights() {
        for (i in 0 until binding.rowLensButtons.childCount) {
            val child = binding.rowLensButtons.getChildAt(i) as? Button ?: continue
            val selected = i == currentLensIndex
            child.setBackgroundResource(if (selected) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            child.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.text_on_accent else R.color.text_primary)
            )
        }
    }

    private fun startCameraIfReady() {
        if (lenses.isEmpty()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        openLens(currentLensIndex)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            if (afState == lastAfBadgeState) return
            lastAfBadgeState = afState
            runOnUiThread {
                if (isDestroyed || !currentLensHasSignal) return@runOnUiThread
                when (afState) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> {
                        afVerifiedSuccess = true
                        binding.tvAfLiveBadge.text = if (currentMinFocusDistance <= 0f) "FIXED FOCUS OK" else "AF: LOCKED OK"
                        binding.tvAfLiveBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                        binding.tvAfLiveBadge.setTextColor(ContextCompat.getColor(this@CameraTestActivity, R.color.status_pass_fg))
                    }
                    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> {
                        binding.tvAfLiveBadge.text = "AF: FOCUSING..."
                        binding.tvAfLiveBadge.setBackgroundResource(R.drawable.bg_badge_warn)
                        binding.tvAfLiveBadge.setTextColor(ContextCompat.getColor(this@CameraTestActivity, R.color.status_warn_fg))
                    }
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        binding.tvAfLiveBadge.text = "AF: GAGAL LOCK!"
                        binding.tvAfLiveBadge.setBackgroundResource(R.drawable.bg_badge_fail)
                        binding.tvAfLiveBadge.setTextColor(ContextCompat.getColor(this@CameraTestActivity, R.color.status_fail_fg))
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openLens(index: Int) {
        if (index !in lenses.indices) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        closeCamera()
        ensureCameraThread()
        currentLensFrames = 0
        currentLensHasSignal = false
        lastAfBadgeState = -1
        binding.tvAfLiveBadge.text = "STREAM..."
        binding.tvAfLiveBadge.setBackgroundResource(R.drawable.bg_badge_ready)
        binding.tvAfLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

        val lens = lenses[index]
        val focal = lens.focalLengthsMm.joinToString("/") { "${it}mm" }.ifEmpty { "N/A" }
        val ap = lens.apertures.joinToString("/") { "f/$it" }.ifEmpty { "N/A" }
        val zoomSpan = String.format(Locale.US, "%.1fx..%.1fx", lens.minZoomRatio, lens.maxZoomRatio)
        binding.tvCameraInfo.text = buildString {
            appendLine("ID ${lens.cameraId} [${lens.facingLabel}] - ${lens.roleLabel} | Zoom: $zoomSpan")
            appendLine("Max: ${lens.maxResolution} | Focal: $focal | Aperture: $ap")
            append("OIS: ${lens.oisSupported} | Flash: ${lens.flashSupported} | Level: ${lens.hardwareLevel} | Tap preview to AF")
        }

        try {
            cameraManager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreviewSession(camera, lens)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                    runOnUiThread {
                        binding.tvCameraInfo.text = "Camera ID ${lens.cameraId} restricted by OEM ($error). Use Zoom buttons (0.6x/2x) on main ID 0."
                    }
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            binding.tvCameraInfo.text = "Open exception on ID ${lens.cameraId}: ${t.message}"
        }
    }

    private fun startPreviewSession(device: CameraDevice, lens: CameraLensInfo) {
        val st = binding.cameraPreviewTexture.surfaceTexture ?: return
        try {
            val ch = cameraManager.getCameraCharacteristics(lens.cameraId)
            currentActiveArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            currentMinFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val chosen = sizes?.firstOrNull { it.width <= 1920 && it.height <= 1080 } ?: sizes?.firstOrNull()
            if (chosen != null) {
                st.setDefaultBufferSize(chosen.width, chosen.height)
                runOnUiThread { configureTransform(chosen.width, chosen.height) }
            }
            val surface = Surface(st)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                applyZoomToBuilder(this)
                if (torchEnabled && lens.flashSupported) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }
            previewBuilder = builder

            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                    } catch (_: Throwable) {
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { binding.tvCameraInfo.text = "Preview session failed on ID ${lens.cameraId}" }
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            runOnUiThread { binding.tvCameraInfo.text = "Preview error: ${t.message}" }
        }
    }

    private fun configureTransform(bufW: Int, bufH: Int) {
        val vw = binding.cameraPreviewTexture.width.toFloat()
        val vh = binding.cameraPreviewTexture.height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        // Camera buffer natural orientation in portrait view has height/width swapped for aspect calculation.
        val bufferRect = RectF(0f, 0f, bufH.toFloat(), bufW.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(vh / bufH.toFloat(), vw / bufW.toFloat())
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), cx, cy)
        } else {
            val scale = maxOf(vw / bufH.toFloat(), vh / bufW.toFloat())
            matrix.setScale((bufH * scale) / vw, (bufW * scale) / vh, cx, cy)
            if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, cx, cy)
            }
        }
        binding.cameraPreviewTexture.setTransform(matrix)
    }

    private fun triggerTapAutofocus(touchX: Float, touchY: Float, viewW: Int, viewH: Int) {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        val activeRect = currentActiveArray
        if (currentMinFocusDistance <= 0f) {
            binding.tvAfLiveBadge.text = "FIXED FOCUS"
            return
        }
        try {
            if (activeRect != null && viewW > 0 && viewH > 0) {
                val nx = (touchY / viewH.toFloat()).coerceIn(0.05f, 0.95f)
                val ny = (1.0f - (touchX / viewW.toFloat())).coerceIn(0.05f, 0.95f)
                val focusW = (activeRect.width() * 0.14f).toInt().coerceAtLeast(80)
                val focusH = (activeRect.height() * 0.14f).toInt().coerceAtLeast(80)
                val fx = (activeRect.left + nx * activeRect.width()).toInt()
                val fy = (activeRect.top + ny * activeRect.height()).toInt()
                val region = Rect(
                    (fx - focusW / 2).coerceIn(activeRect.left, activeRect.right - 2),
                    (fy - focusH / 2).coerceIn(activeRect.top, activeRect.bottom - 2),
                    (fx + focusW / 2).coerceIn(activeRect.left + 2, activeRect.right),
                    (fy + focusH / 2).coerceIn(activeRect.top + 2, activeRect.bottom)
                )
                val meteringRect = MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            }
            lastAfBadgeState = -1
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), captureCallback, cameraHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        } catch (_: Throwable) {
        }
    }

    private fun toggleTorch() {
        val lens = lenses.getOrNull(currentLensIndex)
        if (lens != null && !lens.flashSupported) {
            Toast.makeText(
                this,
                Lang.t(this, "Lensa ${lens.facingLabel} ini tidak memiliki LED Flash. Pindah ke lensa BACK untuk tes Torch.", "This lens has no Flash. Switch to BACK lens for Torch."),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        torchEnabled = !torchEnabled
        binding.btnTorchToggle.text = if (torchEnabled) "TORCH ON" else "TORCH OFF"
        val builder = previewBuilder
        val session = captureSession
        if (builder != null && session != null && lens != null && lens.flashSupported) {
            try {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
                session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                return
            } catch (_: Throwable) {
            }
        }
        val backFlashLens = lenses.firstOrNull { it.flashSupported } ?: return
        CameraEnumerator.setTorch(this, backFlashLens.cameraId, torchEnabled)
    }

    private fun ensureCameraThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("SanepoCamThread").also {
                it.start()
                cameraHandler = Handler(it.looper)
            }
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
        } catch (_: Throwable) {
        }
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLensButtonHighlights()
        if (binding.cameraPreviewTexture.isAvailable) {
            startCameraIfReady()
        }
    }

    override fun onPause() {
        resetTorchOnSwitch()
        closeCamera()
        try {
            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
