package com.sanepo.unitcheck.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivityDisplayTestBinding
import com.sanepo.unitcheck.engine.DisplayDiagnosticsHelper
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang
import java.util.Locale

class DisplayTestActivity : AppCompatActivity() {

    private data class ColorStep(
        val hexColor: Int,
        val titleId: String,
        val titleEn: String,
        val gradientMode: Boolean = false
    )

    private lateinit var binding: ActivityDisplayTestBinding
    private var colorIdx = 0
    private var hudVisible = true
    private val visitedColorIndices = mutableSetOf<Int>()
    private var activeModes: List<Display.Mode> = emptyList()
    private var selectedModeId: Int = -1
    private var measuredFpsLabel: String? = null
    private var fpsCallback: Choreographer.FrameCallback? = null
    private var isExpressMode = false

    private val colorSteps: List<ColorStep> by lazy {
        if (isExpressMode) {
            listOf(
                ColorStep(Color.WHITE, "Putih Penuh (#FFFFFF) - Cek Flek / Kuning", "Pure White (#FFFFFF) - Stain / Tint Check"),
                ColorStep(Color.parseColor("#808080"), "Abu-Abu 50% (#808080) - Cek Shadow / Burn-In OLED", "50% Grey (#808080) - OLED Burn-In / Uniformity"),
                ColorStep(Color.parseColor("#1A1A1A"), "Abu-Abu Gelap 10% (#1A1A1A) - Cek Green Tint", "10% Dark Grey (#1A1A1A) - Green-Tint / Mura"),
                ColorStep(Color.RED, "Merah Penuh (#FF0000) - Cek Subpixel", "Pure Red (#FF0000) - Subpixel Check"),
                ColorStep(Color.GREEN, "Hijau Penuh (#00FF00) - Cek Subpixel", "Pure Green (#00FF00) - Subpixel Check"),
                ColorStep(Color.BLUE, "Biru Penuh (#0000FF) - Cek Subpixel", "Pure Blue (#0000FF) - Subpixel Check"),
                ColorStep(Color.BLACK, "Hitam Penuh (#000000) - Cek Light Bleed", "Pure Black (#000000) - Light Bleed")
            )
        } else {
            listOf(
                ColorStep(Color.RED, "Merah Penuh (#FF0000) - Cek Subpixel", "Pure Red (#FF0000) - Subpixel Check"),
                ColorStep(Color.GREEN, "Hijau Penuh (#00FF00) - Cek Subpixel", "Pure Green (#00FF00) - Subpixel Check"),
                ColorStep(Color.BLUE, "Biru Penuh (#0000FF) - Cek Subpixel", "Pure Blue (#0000FF) - Subpixel Check"),
                ColorStep(Color.WHITE, "Putih Penuh (#FFFFFF) - Cek Flek / Kuning", "Pure White (#FFFFFF) - Stain / Tint Check"),
                ColorStep(Color.BLACK, "Hitam Penuh (#000000) - Cek Light Bleed", "Pure Black (#000000) - Light Bleed / True OLED"),
                ColorStep(Color.parseColor("#1A1A1A"), "Abu-Abu Gelap 10% (#1A1A1A) - Cek Green Tint / Mura", "10% Dark Grey (#1A1A1A) - OLED Green-Tint / Mura"),
                ColorStep(Color.parseColor("#808080"), "Abu-Abu 50% (#808080) - Cek Shadow / Burn-In", "50% Grey (#808080) - OLED Burn-In / Uniformity"),
                ColorStep(Color.YELLOW, "Kuning Penuh (#FFFF00) - Cek White Spot", "Pure Yellow (#FFFF00) - White Spot / Subpixel"),
                ColorStep(Color.CYAN, "Sian Penuh (#00FFFF) - Cek Dead Pixel", "Pure Cyan (#00FFFF) - Dead Pixel Check"),
                ColorStep(Color.BLACK, "Gradien Banding RGB + Grayscale - Cek Bit Depth", "Gradient Banding RGB + Greyscale - Bit Depth Check", gradientMode = true)
            )
        }
    }

    private fun minColorsRequired(): Int = if (isExpressMode) 2 else 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isExpressMode = CodWizardFlow.isExpress(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        binding = ActivityDisplayTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        val specs = DisplayDiagnosticsHelper.readSpecs(this)
        DiagnosticStore.displaySpecs = specs
        updateSpecsText(specs, null)

        binding.tvTapRestoreHint.text = Lang.t(this, "Ketuk layar untuk tampilkan panel / ganti warna", "Tap screen to cycle or restore HUD")
        binding.btnNextColor.text = Lang.t(this, "WARNA BERIKUTNYA", "NEXT COLOR")
        binding.btnToggleHud.text = Lang.t(this, "SEMBUNYI", "HIDE HUD")
        binding.btnDisplayPass.text = Lang.t(this, "LAYAR PASS", "DISPLAY PASS")
        binding.btnDisplayFail.text = Lang.t(this, "ADA MINUS / FAIL", "DEFECT / FAIL")

        setupRefreshRateButtons()
        applyColorStep()

        binding.rootDisplayTest.setOnClickListener {
            if (!hudVisible) {
                hudVisible = true
                binding.hudDisplay.visibility = View.VISIBLE
                binding.tvTapRestoreHint.visibility = View.GONE
            } else {
                advanceColor()
            }
        }
        binding.btnNextColor.setOnClickListener { advanceColor() }
        binding.btnToggleHud.setOnClickListener {
            hudVisible = false
            binding.hudDisplay.visibility = View.GONE
            binding.tvTapRestoreHint.visibility = View.VISIBLE
        }
        binding.btnDisplayPass.setOnClickListener {
            val minReq = minColorsRequired()
            if (visitedColorIndices.size < minReq) {
                val msg = Lang.t(
                    this,
                    "Cek minimal $minReq warna dulu (${visitedColorIndices.size}/$minReq) sebelum PASS!",
                    "Check at least $minReq colors first (${visitedColorIndices.size}/$minReq) before PASS!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvDisplaySpecs.text = msg
                return@setOnClickListener
            }
            val s = DisplayDiagnosticsHelper.readSpecs(this)
            DiagnosticStore.displaySpecs = s
            DiagnosticStore.persistSnapshotMeta()
            val fpsSuffix = measuredFpsLabel?.let { " | $it" } ?: ""
            DiagnosticStore.updateItem(
                id = "display_pixels",
                status = TestStatus.PASS,
                summary = "${s.widthPx}x${s.heightPx} @ ${s.activeRefreshHz}Hz$fpsSuffix | ${visitedColorIndices.size}/${colorSteps.size} colors clean",
                details = binding.tvDisplaySpecs.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }
        binding.btnDisplayFail.setOnClickListener {
            val s = DisplayDiagnosticsHelper.readSpecs(this)
            DiagnosticStore.displaySpecs = s
            DiagnosticStore.persistSnapshotMeta()
            DiagnosticStore.updateItem(
                id = "display_pixels",
                status = TestStatus.FAIL,
                summary = Lang.t(this, "Ditemukan dead pixel / shadow / tint / banding layar", "Dead pixel, shadow burn-in, tint, or banding defect flagged"),
                details = binding.tvDisplaySpecs.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }
    }

    private fun updateSpecsText(specs: com.sanepo.unitcheck.model.DisplaySpecSummary, measuredNote: String?) {
        val hdrStr = if (specs.hdrCapabilities.isEmpty()) "SDR" else specs.hdrCapabilities.joinToString("/")
        val base = "${specs.widthPx}x${specs.heightPx} | ${specs.densityDpi}dpi (~${specs.approxInches}\") | ${specs.activeRefreshHz}Hz | $hdrStr | WideGamut=${specs.wideColorGamut} | MaxBright=100%"
        binding.tvDisplaySpecs.text = if (measuredNote != null) "$base\n$measuredNote" else base
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupRefreshRateButtons() {
        binding.rowRefreshRates.removeAllViews()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        display ?: return
        val currentMode = display.mode ?: return
        selectedModeId = currentMode.modeId
        val sameResModes = display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .distinctBy { it.refreshRate.toInt() }
            .sortedBy { it.refreshRate }
        activeModes = sameResModes

        for (mode in sameResModes) {
            val hz = mode.refreshRate.toInt()
            val selected = mode.modeId == selectedModeId
            val btn = Button(this).apply {
                text = "${hz}Hz"
                textSize = 12f
                isFocusable = true
                setBackgroundResource(if (selected) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.text_on_accent else R.color.text_primary
                    )
                )
                val lp = LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f)
                lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setOnClickListener {
                    selectedModeId = mode.modeId
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = mode.modeId
                    }
                    refreshHzButtonHighlights()
                    measureRealFps(mode.refreshRate)
                }
            }
            binding.rowRefreshRates.addView(btn)
        }
    }

    private fun refreshHzButtonHighlights() {
        for (i in 0 until binding.rowRefreshRates.childCount) {
            val child = binding.rowRefreshRates.getChildAt(i) as? Button ?: continue
            val mode = activeModes.getOrNull(i) ?: continue
            val selected = mode.modeId == selectedModeId
            child.setBackgroundResource(if (selected) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            child.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.text_on_accent else R.color.text_primary)
            )
        }
    }

    private fun measureRealFps(targetHz: Float) {
        stopFpsProbe()
        val startNanos = System.nanoTime()
        var frames = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frames++
                // Force a continuous draw pass so LTPO OLED panels hold peak refresh rate instead of throttling to 10/24Hz while idle.
                binding.rootDisplayTest.postInvalidateOnAnimation()
                val elapsedNs = frameTimeNanos - startNanos
                if (elapsedNs >= 650_000_000L && frames > 2) {
                    val actualFps = (frames * 1_000_000_000.0) / elapsedNs.toDouble()
                    val label = String.format(Locale.US, "Target %.0fHz -> Measured %.1f FPS", targetHz, actualFps)
                    measuredFpsLabel = label
                    val refreshed = DisplayDiagnosticsHelper.readSpecs(this@DisplayTestActivity)
                    DiagnosticStore.displaySpecs = refreshed
                    updateSpecsText(refreshed, label)
                    fpsCallback = null
                } else {
                    fpsCallback = this
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        fpsCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun stopFpsProbe() {
        fpsCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        fpsCallback = null
    }

    private fun advanceColor() {
        colorIdx = (colorIdx + 1) % colorSteps.size
        applyColorStep()
    }

    private fun applyColorStep() {
        visitedColorIndices.add(colorIdx)
        val step = colorSteps[colorIdx]
        if (step.gradientMode) {
            binding.rootDisplayTest.background = BandingGradientDrawable()
        } else {
            binding.rootDisplayTest.setBackgroundColor(step.hexColor)
        }
        val checkedCount = visitedColorIndices.size
        val minReq = minColorsRequired()
        val gateTag = if (checkedCount >= minReq) {
            Lang.t(this, "SIAP PASS ($checkedCount/${colorSteps.size})", "READY ($checkedCount/${colorSteps.size})")
        } else {
            Lang.t(this, "Dicek $checkedCount/$minReq min", "Checked $checkedCount/$minReq min")
        }
        binding.tvColorTitle.text = "${colorIdx + 1}/${colorSteps.size} [$gateTag]: ${Lang.t(this, step.titleId, step.titleEn)}"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        stopFpsProbe()
        super.onDestroy()
    }

    private class BandingGradientDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedWidth = -1
        private var cachedHeight = -1
        private val shaders = arrayOfNulls<LinearGradient>(4)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width()
            val h = b.height()
            if (w <= 0 || h <= 0) return
            val bandH = h / 4f
            if (w != cachedWidth || h != cachedHeight) {
                cachedWidth = w
                cachedHeight = h
                val starts = intArrayOf(Color.BLACK, Color.BLACK, Color.BLACK, Color.BLACK)
                val ends = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
                for (i in 0..3) {
                    shaders[i] = LinearGradient(
                        0f, 0f, w.toFloat(), 0f,
                        starts[i], ends[i], Shader.TileMode.CLAMP
                    )
                }
            }
            for (i in 0..3) {
                paint.shader = shaders[i]
                canvas.drawRect(0f, i * bandH, w.toFloat(), (i + 1) * bandH, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
