package com.sanepo.unitcheck.ui

import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivityTouchTestBinding
import com.sanepo.unitcheck.engine.DisplayDiagnosticsHelper
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.ui.views.TouchDiagnosticView
import com.sanepo.unitcheck.util.Lang
import java.util.Locale
import kotlin.math.roundToInt

class TouchTestActivity : AppCompatActivity(), TouchDiagnosticView.Listener {

    private lateinit var binding: ActivityTouchTestBinding
    private var ghostTimer: CountDownTimer? = null
    private var ghostFailed = false
    private var ghostMonitorCompleted = false
    private var maxMultiObserved = 0
    private var overlayAtTop = false
    private var isExpressMode = false
    private var medianSamplingHz = 0f
    private var peakSamplingHz = 0f
    private var samplingSamples = 0

    private fun requiredGridPct(): Int = if (isExpressMode) 80 else 95
    private fun ghostDurationMs(): Long = if (isExpressMode) 2000L else 5000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isExpressMode = CodWizardFlow.isExpress(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        binding = ActivityTouchTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        if (DiagnosticStore.displaySpecs == null) {
            DiagnosticStore.displaySpecs = DisplayDiagnosticsHelper.readSpecs(this)
        }

        binding.touchDiagnosticView.listener = this
        binding.btnModeGrid.text = "GRID"
        binding.btnModeMulti.text = "MULTI"
        binding.btnModeGhost.text = if (isExpressMode) "GHOST 2s" else "GHOST 5s"
        binding.btnMoveOverlay.text = Lang.t(this, "PINDAH ATAS/BAWAH", "MOVE TOP/BOT")
        binding.btnHideOverlay.text = Lang.t(this, "SEMBUNYI HUD", "HIDE HUD")
        binding.btnShowOverlay.text = Lang.t(this, "BUKA PANEL TOUCH", "SHOW TOUCH HUD")
        binding.btnTouchPass.text = "PASS"
        binding.btnTouchFail.text = "FAIL"
        binding.btnTouchReset.text = Lang.t(this, "ULANG", "RESET")

        switchMode(TouchDiagnosticView.Mode.GRID)

        binding.btnModeGrid.setOnClickListener { switchMode(TouchDiagnosticView.Mode.GRID) }
        binding.btnModeMulti.setOnClickListener { switchMode(TouchDiagnosticView.Mode.MULTITOUCH) }
        binding.btnModeGhost.setOnClickListener { switchMode(TouchDiagnosticView.Mode.GHOST) }

        binding.btnMoveOverlay.setOnClickListener {
            overlayAtTop = !overlayAtTop
            val lp = binding.touchOverlayPanel.layoutParams as FrameLayout.LayoutParams
            lp.gravity = (if (overlayAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            binding.touchOverlayPanel.layoutParams = lp
        }

        binding.btnHideOverlay.setOnClickListener {
            binding.touchOverlayPanel.visibility = View.GONE
            binding.btnShowOverlay.visibility = View.VISIBLE
        }

        binding.btnShowOverlay.setOnClickListener {
            binding.touchOverlayPanel.visibility = View.VISIBLE
            binding.btnShowOverlay.visibility = View.GONE
        }

        binding.btnTouchReset.setOnClickListener {
            ghostTimer?.cancel()
            ghostFailed = false
            ghostMonitorCompleted = false
            maxMultiObserved = 0
            medianSamplingHz = 0f
            peakSamplingHz = 0f
            samplingSamples = 0
            binding.touchDiagnosticView.resetState()
            if (binding.touchDiagnosticView.mode == TouchDiagnosticView.Mode.GHOST) {
                startGhostMonitor()
            }
        }

        binding.btnTouchPass.setOnClickListener {
            val (hit, total) = binding.touchDiagnosticView.getGridCoverage()
            val pct = if (total > 0) (hit * 100) / total else 0
            val ghostHits = binding.touchDiagnosticView.getGhostHitCount()
            val reqPct = requiredGridPct()
            if (pct < reqPct) {
                val msg = Lang.t(
                    this,
                    "Grid baru $pct% (min $reqPct%). Pindah/sembunyikan HUD untuk sentuh semua tepi!",
                    "Grid coverage only $pct% (min $reqPct%). Move/hide HUD to trace all edges!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvTouchStatus.text = msg
                return@setOnClickListener
            }
            if (!ghostMonitorCompleted || ghostFailed || ghostHits > 0) {
                val ghostSec = (ghostDurationMs() / 1000L).toInt()
                val msg = if (ghostFailed || ghostHits > 0) {
                    Lang.t(this, "Terdeteksi Ghost Touch ($ghostHits hit), tidak bisa PASS!", "Ghost touch detected ($ghostHits hits), cannot PASS!")
                } else {
                    Lang.t(this, "Jalankan tes GHOST ${ghostSec}s sampai selesai bersih sebelum PASS!", "Run GHOST ${ghostSec}s test cleanly before PASS!")
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvTouchStatus.text = msg
                return@setOnClickListener
            }
            DiagnosticStore.touchSamplingHzPeak = peakSamplingHz
            val panelWarning = evaluatePanelKwWarning()
            val rateStr = if (samplingSamples >= 10) " | Touch ~${medianSamplingHz.roundToInt()}Hz (Peak ${peakSamplingHz.roundToInt()}Hz)" else ""
            val ghostSec = (ghostDurationMs() / 1000L).toInt()
            if (panelWarning != null) {
                DiagnosticStore.updateItem(
                    "touch_digitizer",
                    TestStatus.WARN,
                    "WARN LCD KW/Drop: $panelWarning | Grid $hit/$total ($pct%)$rateStr | Multi=${maxMultiObserved}pt"
                )
            } else {
                DiagnosticStore.updateItem(
                    "touch_digitizer",
                    TestStatus.PASS,
                    "Grid $hit/$total ($pct%)$rateStr | Multi=${maxMultiObserved}pt | Ghost=0 (${ghostSec}s)"
                )
            }
            CodWizardFlow.advanceOrFinish(this)
        }

        binding.btnTouchFail.setOnClickListener {
            val (hit, total) = binding.touchDiagnosticView.getGridCoverage()
            val ghostHits = binding.touchDiagnosticView.getGhostHitCount()
            val summary = "FAIL: Grid $hit/$total | Multi=${maxMultiObserved}pt | Ghost=${if (ghostFailed || ghostHits > 0) "DETECTED ($ghostHits)" else "Dead Zone / Manual Fail"}"
            DiagnosticStore.updateItem("touch_digitizer", TestStatus.FAIL, summary)
            CodWizardFlow.advanceOrFinish(this)
        }
    }

    private fun evaluatePanelKwWarning(): String? {
        val disp = DiagnosticStore.displaySpecs
        val maxHz = disp?.supportedRefreshRatesHz?.maxOrNull() ?: 60f
        if (samplingSamples >= 25 && medianSamplingHz < 85f) {
            return "Sampling Rate rendah (${medianSamplingHz.roundToInt()}Hz < 85Hz)"
        }
        if (maxMultiObserved in 2..4) {
            return "Multi-touch mentok $maxMultiObserved jari (<5 jari, ciri LCD KW)"
        }
        if (maxHz >= 90f && samplingSamples >= 25 && medianSamplingHz < 95f) {
            return "Display ${maxHz.toInt()}Hz tapi Touch ${medianSamplingHz.roundToInt()}Hz"
        }
        return null
    }

    private fun updateHudStatus(primaryText: String) {
        val rateLine = if (samplingSamples >= 8) {
            val kwWarn = evaluatePanelKwWarning()
            val verdictTag = if (kwWarn != null) "WASPADA: $kwWarn" else "Touch: ${medianSamplingHz.roundToInt()}Hz (Peak ${peakSamplingHz.roundToInt()}Hz)"
            "\n$verdictTag | Multi: ${maxMultiObserved}pt"
        } else {
            "\nMulti-Touch tercatat: ${maxMultiObserved} jari (sapu cepat untuk ukur Hz)"
        }
        binding.tvTouchStatus.text = primaryText + rateLine
    }

    private fun switchMode(mode: TouchDiagnosticView.Mode) {
        ghostTimer?.cancel()
        binding.touchDiagnosticView.ghostMonitoringActive = false
        binding.touchDiagnosticView.mode = mode

        binding.btnModeGrid.setBackgroundResource(if (mode == TouchDiagnosticView.Mode.GRID) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        binding.btnModeMulti.setBackgroundResource(if (mode == TouchDiagnosticView.Mode.MULTITOUCH) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        binding.btnModeGhost.setBackgroundResource(if (mode == TouchDiagnosticView.Mode.GHOST) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)

        when (mode) {
            TouchDiagnosticView.Mode.GRID -> {
                val (hit, total) = binding.touchDiagnosticView.getGridCoverage()
                val pct = if (total > 0) (hit * 100) / total else 0
                updateHudStatus(
                    Lang.t(
                        this,
                        "Usap seluruh layar & tepi: $hit/$total ($pct% | min ${requiredGridPct()}%)",
                        "Trace entire screen & edges: $hit/$total ($pct% | min ${requiredGridPct()}%)"
                    )
                )
            }
            TouchDiagnosticView.Mode.MULTITOUCH -> {
                updateHudStatus(
                    Lang.t(
                        this,
                        "Tempelkan 5-10 jari bersamaan untuk cek keaslian digitizer",
                        "Place 5-10 fingers simultaneously to verify OEM digitizer"
                    )
                )
            }
            TouchDiagnosticView.Mode.GHOST -> startGhostMonitor()
        }
    }

    private fun startGhostMonitor() {
        ghostFailed = false
        ghostMonitorCompleted = false
        binding.touchDiagnosticView.ghostMonitoringActive = true
        ghostTimer?.cancel()
        ghostTimer = object : CountDownTimer(ghostDurationMs(), 200L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = String.format(Locale.US, "%.1f", millisUntilFinished / 1000f)
                val statusPrefix = if (ghostFailed) "GHOST DETECTED! " else ""
                binding.tvTouchStatus.text = Lang.t(
                    this@TouchTestActivity,
                    "${statusPrefix}Jangan sentuh layar... ${sec}s",
                    "${statusPrefix}Hands off screen... ${sec}s"
                )
            }

            override fun onFinish() {
                binding.touchDiagnosticView.ghostMonitoringActive = false
                if (ghostFailed || binding.touchDiagnosticView.getGhostHitCount() > 0) {
                    ghostMonitorCompleted = false
                    binding.tvTouchStatus.text = Lang.t(
                        this@TouchTestActivity,
                        "FAIL: Terdeteksi sentuhan liar!",
                        "FAIL: Ghost touch events detected!"
                    )
                } else {
                    ghostMonitorCompleted = true
                    binding.tvTouchStatus.text = Lang.t(
                        this@TouchTestActivity,
                        "BERSIH: 0 sentuhan liar (${ghostDurationMs() / 1000}s Clean)",
                        "CLEAN: 0 ghost touches (${ghostDurationMs() / 1000}s)"
                    )
                }
            }
        }.start()
    }

    override fun onGridProgress(hitCells: Int, totalCells: Int) {
        if (binding.touchDiagnosticView.mode != TouchDiagnosticView.Mode.GRID) return
        val pct = if (totalCells > 0) (hitCells * 100) / totalCells else 0
        val ghostTag = if (ghostMonitorCompleted && !ghostFailed) "Ghost:OK" else "Ghost:Pending"
        updateHudStatus(
            Lang.t(
                this,
                "Kotak: $hitCells/$totalCells ($pct%) | $ghostTag",
                "Grid: $hitCells/$totalCells ($pct%) | $ghostTag"
            )
        )
    }

    override fun onMultiTouchChanged(activeCount: Int, maxSimultaneous: Int) {
        if (maxSimultaneous > maxMultiObserved) {
            maxMultiObserved = maxSimultaneous
        }
        if (binding.touchDiagnosticView.mode != TouchDiagnosticView.Mode.MULTITOUCH) return
        updateHudStatus(
            Lang.t(
                this,
                "Jari aktif: $activeCount | Maks: $maxMultiObserved jari",
                "Active fingers: $activeCount | Max: $maxMultiObserved pts"
            )
        )
    }

    override fun onSamplingRateUpdated(medianHz: Float, peakHz: Float, sampleCount: Int) {
        medianSamplingHz = medianHz
        peakSamplingHz = peakHz
        samplingSamples = sampleCount
    }

    override fun onGhostTouchHit(x: Float, y: Float) {
        ghostFailed = true
        ghostMonitorCompleted = false
        binding.tvTouchStatus.text = "FAIL: GHOST TOUCH (${x.toInt()}, ${y.toInt()})"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ghostTimer?.cancel()
        super.onDestroy()
    }
}
