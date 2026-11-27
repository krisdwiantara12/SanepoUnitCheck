package com.sanepo.unitcheck.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.databinding.ActivityBatteryDetailBinding
import com.sanepo.unitcheck.engine.BatteryHardwareReader
import com.sanepo.unitcheck.model.BatteryStressSummary
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class BatteryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatteryDetailBinding
    private val handler = Handler(Looper.getMainLooper())
    private val stressRunning = AtomicBoolean(false)
    private val stressThreads = ArrayList<Thread>()
    private var torchCameraId: String? = null
    private var torchActive = false

    private var stressElapsedSec = 0
    private var stressStartVoltMv = 0
    private var stressMinVoltMv = Int.MAX_VALUE
    private var stressMinPerCellMv = Int.MAX_VALUE
    private var stressIsDualCell = false
    private var stressStartPct = 0
    private var stressMaxTempC = 0f

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshBattery()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootBatteryDetail) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        binding.btnBackBattery.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvBatteryTitle.text = Lang.t(this, "Diagnostik Baterai & Arus Cas", "Battery & Charging Circuit")
        binding.tvGraphHeader.text = Lang.t(this, "GRAFIK STABILITAS ARUS (30 SAMPEL LIVE)", "LIVE CURRENT STABILITY GRAPH (30 SAMPLES)")
        binding.tvStressHeader.text = Lang.t(this, "45-DETIK STRESS TEST (DETEKSI BATERAI DROP / BOCOR)", "45-SECOND VOLTAGE SAG STRESS TEST")
        binding.cbStressTorch.text = Lang.t(this, "Nyalakan Flash LED belakang saat stress test", "Enable Rear LED Torch during stress load")
        binding.btnStartStress.text = Lang.t(this, "MULAI STRESS TEST 45s", "START 45s STRESS TEST")
        binding.btnBatteryPass.text = Lang.t(this, "BATERAI PASS", "BATTERY PASS")
        binding.btnBatteryFail.text = Lang.t(this, "DROP / FAIL", "FAULTY / FAIL")

        renderExistingStressSummary()

        binding.btnBackBattery.setOnClickListener { finish() }
        binding.btnStartStress.setOnClickListener {
            if (stressRunning.get()) {
                stopStressTest(completed = false)
            } else {
                startStressTest()
            }
        }

        binding.btnBatteryPass.setOnClickListener {
            val stress = DiagnosticStore.batteryStressSummary
            if (stress != null && !stress.passed) {
                val msg = Lang.t(
                    this,
                    "Stress Test GAGAL (${stress.verdictNote}). Tidak bisa menandai PASS!",
                    "Stress Test FAILED (${stress.verdictNote}). Cannot mark PASS!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                binding.tvStressStatus.text = msg
                return@setOnClickListener
            }
            val t = BatteryHardwareReader.read(this)
            val healthStr = t.estimatedHealthPct?.let { "$it%" } ?: t.healthLabel
            val stressSuffix = stress?.let { " | Stress45s: Min ${it.minVoltageMv}mV (-${it.startLevelPct - it.endLevelPct}%)" } ?: ""
            DiagnosticStore.updateItem(
                "battery_charging",
                TestStatus.PASS,
                "${t.levelPct}% | ${t.currentMa}mA (${t.wattageW}W) | Health: $healthStr$stressSuffix",
                binding.tvBatteryMeta.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        binding.btnBatteryFail.setOnClickListener {
            val t = BatteryHardwareReader.read(this)
            val stressSuffix = DiagnosticStore.batteryStressSummary?.let { " | ${it.verdictNote}" } ?: ""
            DiagnosticStore.updateItem(
                "battery_charging",
                TestStatus.FAIL,
                "FAIL: ${t.levelPct}% | ${t.currentMa}mA | Temp ${t.tempCelsius}C$stressSuffix",
                binding.tvBatteryMeta.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        stopStressTest(completed = false)
        handler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        stopStressTest(completed = false)
        super.onDestroy()
    }

    private fun renderExistingStressSummary() {
        val prev = DiagnosticStore.batteryStressSummary
        if (prev == null) {
            binding.tvStressStatus.text = Lang.t(
                this,
                "Cabut charger lalu jalankan 45s load (All-Core CPU + Torch) untuk mendeteksi sel baterai kembung/bocor.",
                "Unplug charger and run 45s load (All-Core CPU + Torch) to catch voltage sag / worn cells."
            )
        } else {
            val tag = if (prev.passed) "PASS" else "FAIL"
            binding.pbStressProgress.progress = prev.durationSec.coerceIn(0, STRESS_DURATION_SEC)
            binding.tvStressStatus.text =
                "[$tag] ${prev.durationSec}s | V: ${prev.startVoltageMv} -> min ${prev.minVoltageMv} mV (Drop ${prev.voltageDropMv} mV) | SoC: ${prev.startLevelPct}% -> ${prev.endLevelPct}% | Max ${prev.maxTempCelsius}C\n${prev.verdictNote}"
        }
    }

    private fun startStressTest() {
        val init = BatteryHardwareReader.read(this)
        if (init.isCharging) {
            val msg = Lang.t(
                this,
                "CABUT CHARGER / POWERBANK DULU! Tidak bisa menjalankan Stress Test sambil mengecas.",
                "UNPLUG CHARGER FIRST! Cannot run battery sag stress test while charging."
            )
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            binding.tvStressStatus.text = msg
            return
        }

        stressRunning.set(true)
        stressElapsedSec = 0
        stressStartVoltMv = init.voltageMv
        stressMinVoltMv = if (init.voltageMv > 0) init.voltageMv else Int.MAX_VALUE
        stressMinPerCellMv = if (init.perCellVoltageMv > 0) init.perCellVoltageMv else Int.MAX_VALUE
        stressIsDualCell = init.isDualCellPack
        stressStartPct = init.levelPct
        stressMaxTempC = init.tempCelsius

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1.0f }

        if (binding.cbStressTorch.isChecked) {
            setTorchEnabled(true)
        }

        val coreCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 16)
        stressThreads.clear()
        for (idx in 0 until coreCount) {
            val t = Thread {
                var acc = 1.0001 + idx
                while (stressRunning.get()) {
                    acc = sqrt(acc * 1.000001 + 3.1415926)
                    if (acc > 1e9) acc = 1.0001
                }
            }.apply {
                name = "SanepoStressCpu-$idx"
                priority = Thread.NORM_PRIORITY
                start()
            }
            stressThreads.add(t)
        }

        binding.btnStartStress.text = Lang.t(this, "HENTIKAN STRESS TEST", "STOP STRESS TEST")
        binding.pbStressProgress.progress = 0
        binding.tvStressStatus.text = Lang.t(
            this,
            "Stress test ($coreCount core CPU) berjalan 0/${STRESS_DURATION_SEC}s... Jangan colok charger.",
            "Stress test ($coreCount CPU cores) running 0/${STRESS_DURATION_SEC}s... Keep charger unplugged."
        )
    }

    private fun stopStressTest(
        completed: Boolean,
        latestTelemetry: com.sanepo.unitcheck.model.BatteryTelemetry? = null,
        abortReason: String? = null
    ) {
        if (!stressRunning.getAndSet(false)) {
            setTorchEnabled(false)
            restoreBrightnessAndWakeLock()
            return
        }
        for (t in stressThreads) {
            try {
                t.join(150)
            } catch (_: Throwable) {
            }
        }
        stressThreads.clear()
        setTorchEnabled(false)
        restoreBrightnessAndWakeLock()
        binding.btnStartStress.text = Lang.t(this, "ULANGI STRESS TEST 45s", "RE-RUN 45s STRESS TEST")

        val t = latestTelemetry ?: BatteryHardwareReader.read(this)
        if (!completed) {
            binding.tvStressStatus.text = abortReason ?: Lang.t(
                this,
                "Stress test dihentikan pada ${stressElapsedSec}s (tidak disimpan).",
                "Stress test stopped at ${stressElapsedSec}s (not recorded)."
            )
            return
        }

        val minV = if (stressMinVoltMv == Int.MAX_VALUE) t.voltageMv else stressMinVoltMv
        val minPerCellV = if (stressMinPerCellMv == Int.MAX_VALUE) t.perCellVoltageMv else stressMinPerCellMv
        val dropMv = (stressStartVoltMv - minV).coerceAtLeast(0)
        val pctDrop = (stressStartPct - t.levelPct).coerceAtLeast(0)
        val sagFail = minPerCellV in 1..3449 && t.levelPct >= 30
        val dropFail = pctDrop > 3
        val passed = !sagFail && !dropFail

        val cellDesc = if (stressIsDualCell) "${minPerCellV}mV/sel (Pack ${minV}mV)" else "${minV}mV"
        val verdict = when {
            sagFail -> Lang.t(
                this,
                "SEL DROP PARAH: Tegangan anjlok ke $cellDesc (<3450mV/sel) pada baterai ${t.levelPct}%!",
                "SEVERE VOLTAGE SAG: Dropped to $cellDesc (<3450mV/cell) while SoC is ${t.levelPct}%!"
            )
            dropFail -> Lang.t(
                this,
                "BATERAI BOCOR: Persentase turun ${pctDrop}% dalam ${STRESS_DURATION_SEC}s (>3%)!",
                "RAPID DRAIN: Level dropped ${pctDrop}% in ${STRESS_DURATION_SEC}s (>3%)!"
            )
            else -> Lang.t(
                this,
                "Tegangan stabil (min $cellDesc, turun ${pctDrop}% dalam ${STRESS_DURATION_SEC}s).",
                "Voltage stable (min $cellDesc, -${pctDrop}% in ${STRESS_DURATION_SEC}s)."
            )
        }

        val summary = BatteryStressSummary(
            durationSec = STRESS_DURATION_SEC,
            startVoltageMv = stressStartVoltMv,
            minVoltageMv = minV,
            voltageDropMv = dropMv,
            startLevelPct = stressStartPct,
            endLevelPct = t.levelPct,
            maxTempCelsius = stressMaxTempC,
            passed = passed,
            verdictNote = verdict
        )
        DiagnosticStore.batteryStressSummary = summary
        DiagnosticStore.updateItem(
            "battery_charging",
            if (passed) TestStatus.PASS else TestStatus.FAIL,
            if (passed) {
                "${t.levelPct}% | Stress45s OK (min $cellDesc, -${pctDrop}%)"
            } else {
                "FAIL Stress45s: $verdict"
            },
            binding.tvBatteryMeta.text.toString()
        )
        renderExistingStressSummary()
    }

    private fun restoreBrightnessAndWakeLock() {
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } catch (_: Throwable) {
        }
    }

    private fun setTorchEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            if (enabled) {
                if (torchCameraId == null) {
                    torchCameraId = cm.cameraIdList.firstOrNull { id ->
                        val c = cm.getCameraCharacteristics(id)
                        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    } ?: cm.cameraIdList.firstOrNull { id ->
                        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                }
                val id = torchCameraId ?: return
                cm.setTorchMode(id, true)
                torchActive = true
            } else if (torchActive) {
                torchCameraId?.let { cm.setTorchMode(it, false) }
                torchActive = false
            }
        } catch (_: Throwable) {
            torchActive = false
        }
    }

    private fun refreshBattery() {
        val t = BatteryHardwareReader.read(this)
        DiagnosticStore.batteryTelemetry = t
        DiagnosticStore.appendBatterySample(t.currentMa)
        binding.currentChartView.setSamples(DiagnosticStore.batteryHistoryMa)

        val sign = if (t.currentMa > 0) "+" else ""
        binding.tvBatteryHero.textSize = 18f
        binding.tvBatteryHero.text = "${t.levelPct}% | ${sign}${t.currentMa}mA | ${t.wattageW}W"
        val voltLine = if (t.isDualCellPack) {
            "${t.voltageMv} mV (2S: ~${t.perCellVoltageMv} mV/sel)"
        } else {
            "${t.voltageMv} mV"
        }
        binding.tvBatteryMeta.text = buildString {
            appendLine("Status / Source : ${t.statusLabel} (${t.pluggedLabel})")
            appendLine("Protocol Hint   : ${t.chargingProtocolHint}")
            appendLine("Voltage / Temp  : $voltLine  |  ${t.tempCelsius} C  (${t.technology})")
            appendLine("Design / Full   : ${t.designCapacityMah?.let { "$it mAh" } ?: "Kernel Restricted"} / ${t.fullChargeCapacityMah?.let { "$it mAh" } ?: "N/A"}")
            appendLine("Est. Health     : ${t.estimatedHealthPct?.let { "$it%" } ?: t.healthLabel}  |  Cycles: ${t.cycleCount?.toString() ?: "N/A"}")
            append("Charge Counter  : ${t.chargeCounterMah?.let { "$it mAh" } ?: "N/A"}  |  Node: ${t.sysfsSourceUsed}")
        }

        if (stressRunning.get()) {
            if (t.isCharging) {
                stopStressTest(
                    completed = false,
                    latestTelemetry = t,
                    abortReason = Lang.t(
                        this,
                        "Charger terpasang di tengah tes! Stress test dibatalkan agar hasil tidak palsu.",
                        "Charger plugged mid-test! Stress test aborted for accuracy."
                    )
                )
                return
            }
            stressElapsedSec++
            if (t.voltageMv in 1 until stressMinVoltMv) {
                stressMinVoltMv = t.voltageMv
            }
            if (t.perCellVoltageMv in 1 until stressMinPerCellMv) {
                stressMinPerCellMv = t.perCellVoltageMv
            }
            if (t.tempCelsius > stressMaxTempC) {
                stressMaxTempC = t.tempCelsius
            }
            binding.pbStressProgress.progress = stressElapsedSec.coerceAtMost(STRESS_DURATION_SEC)
            val minShown = if (stressMinVoltMv == Int.MAX_VALUE) t.voltageMv else stressMinVoltMv
            binding.tvStressStatus.text = Lang.t(
                this,
                "Load ${stressElapsedSec}/${STRESS_DURATION_SEC}s | V: ${t.voltageMv}mV (Min ${minShown}mV) | ${t.levelPct}% | ${t.tempCelsius}C",
                "Load ${stressElapsedSec}/${STRESS_DURATION_SEC}s | V: ${t.voltageMv}mV (Min ${minShown}mV) | ${t.levelPct}% | ${t.tempCelsius}C"
            )
            if (stressElapsedSec >= STRESS_DURATION_SEC) {
                stopStressTest(completed = true, latestTelemetry = t)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val STRESS_DURATION_SEC = 45
    }
}
