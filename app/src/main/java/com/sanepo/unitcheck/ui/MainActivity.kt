package com.sanepo.unitcheck.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivityMainBinding
import com.sanepo.unitcheck.engine.AppUpdateManager
import com.sanepo.unitcheck.engine.BatteryHardwareReader
import com.sanepo.unitcheck.engine.CameraEnumerator
import com.sanepo.unitcheck.engine.DeviceFingerprintEngine
import com.sanepo.unitcheck.engine.DisplayDiagnosticsHelper
import com.sanepo.unitcheck.engine.ReportExporter
import com.sanepo.unitcheck.engine.SensorAndRFEngine
import com.sanepo.unitcheck.model.DiagnosticItem
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestCategory
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), DiagnosticStore.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DashboardAdapter
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticStore.ensureLoaded(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootMain) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, 0)
            binding.recyclerDashboard.setPadding(
                binding.recyclerDashboard.paddingLeft,
                binding.recyclerDashboard.paddingTop,
                binding.recyclerDashboard.paddingRight,
                sys.bottom + (24 * resources.displayMetrics.density).toInt()
            )
            insets
        }

        adapter = DashboardAdapter(
            context = this,
            onRunAutoScan = { runFullAutoScan(onDone = null) },
            onShowFullSpecs = { showFullFingerprintDialog() },
            onStartCodWizard = { showCodWizardModeDialog() },
            onResetSession = { confirmResetSession() },
            onOpenAbout = { startActivity(Intent(this, AboutActivity::class.java)) },
            onItemClick = { item -> handleDiagnosticItemClick(item) }
        )

        binding.recyclerDashboard.layoutManager = LinearLayoutManager(this)
        binding.recyclerDashboard.adapter = adapter

        binding.btnLangToggle.setOnClickListener {
            Lang.toggle(this)
            refreshTopChrome()
            rebuildDashboardRows()
        }
        binding.btnOpenAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.btnOpenReport.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        binding.tvUpdateBanner.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        refreshTopChrome()
        rebuildDashboardRows()
        loadInitialFingerprintAsync()
        maybeCheckUpdateQuietly()
    }

    override fun onStart() {
        super.onStart()
        DiagnosticStore.addListener(this)
        rebuildDashboardRows()
    }

    override fun onStop() {
        DiagnosticStore.removeListener(this)
        super.onStop()
    }

    override fun onDiagnosticsUpdated() {
        runOnUiThread {
            if (!isDestroyed) rebuildDashboardRows()
        }
    }

    private fun refreshTopChrome() {
        binding.tvAppSubtitle.text = Lang.t(
            this,
            "Diagnostik Unit & Inspeksi Hardware Offline",
            "Offline Technician Unit & Hardware Inspector"
        )
        binding.btnLangToggle.text = if (Lang.isIndonesian(this)) "ID" else "EN"
        binding.btnOpenAbout.text = "ABOUT"
        binding.btnOpenReport.text = Lang.t(this, "LAPORAN", "REPORT")
    }

    private fun rebuildDashboardRows() {
        val list = ArrayList<DashboardAdapter.Row>()
        list.add(DashboardAdapter.Row.Deck)

        val items = DiagnosticStore.getItems()
        val systemGroup = items.filter { it.category == TestCategory.SYSTEM }
        val hwInteractiveGroup = items.filter {
            it.category == TestCategory.DISPLAY ||
                it.category == TestCategory.BATTERY ||
                it.category == TestCategory.AUDIO ||
                it.category == TestCategory.CAMERA
        }
        val rfSensorGroup = items.filter {
            it.category == TestCategory.SENSORS ||
                it.category == TestCategory.CONNECTIVITY ||
                it.category == TestCategory.KEYS_PORTS
        }
        val codSafetyGroup = items.filter { it.category == TestCategory.COD_SAFETY }

        list.add(DashboardAdapter.Row.Header(Lang.t(this, "SISTEM, KEAMANAN & MEMORI", "SYSTEM, SECURITY & STORAGE")))
        systemGroup.forEach { list.add(DashboardAdapter.Row.TestRow(it)) }

        list.add(DashboardAdapter.Row.Header(Lang.t(this, "LAYAR, BATERAI, AUDIO & KAMERA", "DISPLAY, BATTERY, AUDIO & CAMERA")))
        hwInteractiveGroup.forEach { list.add(DashboardAdapter.Row.TestRow(it)) }

        list.add(DashboardAdapter.Row.Header(Lang.t(this, "SENSOR, KONEKTIVITAS & TOMBOL", "SENSORS, RF & PHYSICAL KEYS")))
        rfSensorGroup.forEach { list.add(DashboardAdapter.Row.TestRow(it)) }

        if (codSafetyGroup.isNotEmpty()) {
            list.add(DashboardAdapter.Row.Header(Lang.t(this, "KEAMANAN COD & CEK IMEI", "COD SAFETY & IMEI CHECK")))
            codSafetyGroup.forEach { list.add(DashboardAdapter.Row.TestRow(it)) }
        }
        list.add(DashboardAdapter.Row.Footer)

        adapter.submitData(list)
    }

    private fun showCodWizardModeDialog() {
        val opts = arrayOf(
            Lang.t(
                this,
                "MODE KILAT COD (~90 Detik)\nAuto-Scan + Layar + Touch/LCD KW + Loopback Audio/Mic + Kamera AF + Sinyal Dual-SIM + Checklist",
                "QUICK COD EXPRESS (~90 Sec)\nAuto-Scan + Display + Touch/Panel Check + Auto Loopback + Camera AF + SIM RF + Checklist"
            ),
            Lang.t(
                this,
                "INSPEKSI LENGKAP TEKNISI (10 Modul)\nTermasuk Stress Test Baterai 45s, Sensor Barometer/Biometrik & Semua Tombol/OTG",
                "FULL TECHNICIAN INSPECTION (10 Modules)\nIncludes 45s Battery Stress, Barometer Seal/Bio & All Keys/OTG"
            )
        )
        AlertDialog.Builder(this)
            .setTitle(Lang.t(this, "Pilih Jalur Inspeksi COD", "Select COD Inspection Mode"))
            .setItems(opts) { _, which ->
                if (which == 0) {
                    Toast.makeText(
                        this,
                        Lang.t(this, "Memulai Auto-Scan + Mode Kilat COD...", "Starting Auto-Scan + Quick COD Express..."),
                        Toast.LENGTH_SHORT
                    ).show()
                    runFullAutoScan {
                        CodWizardFlow.startWizard(this@MainActivity, express = true)
                    }
                } else {
                    CodWizardFlow.startWizard(this, express = false)
                }
            }
            .setNegativeButton(Lang.t(this, "Batal", "Cancel"), null)
            .show()
    }

    private fun maybeCheckUpdateQuietly() {
        if (!AppUpdateManager.shouldRunAutoCheckNow(applicationContext)) return
        val includePre = AppUpdateManager.isIncludePreReleases(applicationContext)
        executor.execute {
            val res = AppUpdateManager.checkForUpdate(applicationContext, includePre)
            if (res is AppUpdateManager.CheckOutcome.Success && res.release.updateAvailable) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        binding.tvUpdateBanner.visibility = View.VISIBLE
                        binding.tvUpdateBanner.text = Lang.t(
                            this@MainActivity,
                            "Update tersedia: ${res.release.tagName} | Ketuk untuk Download & Install di About",
                            "Update available: ${res.release.tagName} | Tap to open About & Install"
                        )
                    }
                }
            }
        }
    }

    private fun confirmResetSession() {
        AlertDialog.Builder(this)
            .setTitle(Lang.t(this, "Reset Sesi Diagnostik?", "Reset Inspection Session?"))
            .setMessage(
                Lang.t(
                    this,
                    "Semua hasil tes unit saat ini dan catatan transaksi/IMEI akan dihapus ke awal.",
                    "All current unit results and seller/IMEI notes will be cleared."
                )
            )
            .setPositiveButton(Lang.t(this, "Ya, Reset", "Yes, Reset")) { _, _ ->
                DiagnosticStore.resetSession(applicationContext)
                Toast.makeText(
                    this,
                    Lang.t(this, "Sesi direset.", "Session cleared."),
                    Toast.LENGTH_SHORT
                ).show()
                loadInitialFingerprintAsync()
            }
            .setNegativeButton(Lang.t(this, "Batal", "Cancel"), null)
            .show()
    }

    private fun loadInitialFingerprintAsync() {
        executor.execute {
            val fp = DeviceFingerprintEngine.collect(applicationContext)
            DiagnosticStore.fingerprint = fp
            DiagnosticStore.displaySpecs = DisplayDiagnosticsHelper.readSpecs(applicationContext)
            DiagnosticStore.batteryTelemetry = BatteryHardwareReader.read(applicationContext)
            DiagnosticStore.connectivitySnapshot = SensorAndRFEngine.collectConnectivitySnapshot(applicationContext)
            DiagnosticStore.persistSnapshotMeta()
            runOnUiThread {
                if (!isDestroyed) rebuildDashboardRows()
            }
        }
    }

    private fun runFullAutoScan(onDone: (() -> Unit)?) {
        if (adapter.isScanning) return
        adapter.isScanning = true
        adapter.scanProgressPct = 15
        rebuildDashboardRows()

        executor.execute {
            val fpBase = DeviceFingerprintEngine.collect(applicationContext)
            DiagnosticStore.fingerprint = fpBase
            evaluateSystemTamper(fpBase)

            runOnUiThread {
                adapter.scanProgressPct = 40
                rebuildDashboardRows()
            }

            val bench = DeviceFingerprintEngine.runStorageBenchmark(applicationContext)
            val fpFull = fpBase.copy(
                storageReadMbps = bench.readMbps,
                storageWriteMbps = bench.writeMbps,
                storageBenchNote = bench.note
            )
            DiagnosticStore.fingerprint = fpFull
            val benchStatus = if (bench.readMbps > 0f && bench.writeMbps > 0f) TestStatus.PASS else TestStatus.WARN
            val diskStr = ReportExporter.formatStorageMarketingAndUsable(fpFull.storageChipTotalBytes, fpFull.storageTotalBytes)
            DiagnosticStore.updateItem(
                "storage_benchmark",
                benchStatus,
                "${fpFull.storageType} $diskStr: R ${bench.readMbps} | W ${bench.writeMbps} MB/s"
            )

            runOnUiThread {
                adapter.scanProgressPct = 70
                rebuildDashboardRows()
            }

            val bat = BatteryHardwareReader.read(applicationContext)
            DiagnosticStore.batteryTelemetry = bat
            DiagnosticStore.appendBatterySample(bat.currentMa)
            val prevStress = DiagnosticStore.batteryStressSummary
            // Preserve previously completed 45s Stress Test summary if Auto-Scan runs afterward.
            if (prevStress != null) {
                val cellNote = if (bat.isDualCellPack) "${prevStress.minVoltageMv / 2}mV/sel" else "${prevStress.minVoltageMv}mV"
                DiagnosticStore.updateItem(
                    "battery_charging",
                    if (prevStress.passed) TestStatus.PASS else TestStatus.FAIL,
                    "${bat.levelPct}% | ${bat.currentMa}mA | Stress45s ${if (prevStress.passed) "OK" else "FAIL"} (min $cellNote)"
                )
            } else {
                val batStatus = when {
                    bat.healthLabel == "Dead" || bat.healthLabel == "Overheat" -> TestStatus.FAIL
                    bat.estimatedHealthPct != null && bat.estimatedHealthPct < 75 -> TestStatus.WARN
                    else -> TestStatus.PASS
                }
                val healthText = bat.estimatedHealthPct?.let { "$it%" } ?: bat.healthLabel
                DiagnosticStore.updateItem(
                    "battery_charging",
                    batStatus,
                    "${bat.levelPct}% | ${bat.currentMa}mA | ${bat.voltageMv}mV | ${bat.tempCelsius}C | Health: $healthText"
                )
            }

            val prevProbe = DiagnosticStore.connectivitySnapshot?.cellularDataCheckSummary.orEmpty()
            val rf = SensorAndRFEngine.collectConnectivitySnapshot(applicationContext, existingCellDataCheck = prevProbe)
            DiagnosticStore.connectivitySnapshot = rf
            val badBaseband = rf.basebandVersion.contains("unknown", ignoreCase = true) ||
                rf.basebandVersion.contains("missing", ignoreCase = true)
            val simInserted = rf.sim1State == "SIM READY" || rf.sim2State == "SIM READY"
            val rfStatus = when {
                badBaseband -> TestStatus.FAIL
                rf.hasDualSimSplitBlock -> TestStatus.WARN
                simInserted && !rf.isCellularInService && rf.serviceStateLabel != "Perm Required" -> TestStatus.WARN
                else -> TestStatus.PASS
            }
            DiagnosticStore.updateItem(
                "connectivity_rf",
                rfStatus,
                "Baseband=${rf.basebandVersion.take(20)} | ${rf.serviceStateLabel} | SIM1=${rf.sim1State} SIM2=${rf.sim2State} | NFC=${rf.nfcStatus}"
            )

            DiagnosticStore.displaySpecs = DisplayDiagnosticsHelper.readSpecs(applicationContext)
            DiagnosticStore.cameraLenses = CameraEnumerator.enumerate(applicationContext)
            DiagnosticStore.persistSnapshotMeta()

            runOnUiThread {
                adapter.isScanning = false
                adapter.scanProgressPct = 100
                rebuildDashboardRows()
                if (!isDestroyed && !isFinishing) {
                    onDone?.invoke()
                }
            }
        }
    }

    private fun evaluateSystemTamper(fp: com.sanepo.unitcheck.model.DeviceFingerprint) {
        val knoxBad = fp.knoxGuardState.contains("LOCK", ignoreCase = true) ||
            fp.knoxGuardState.contains("PRENORMAL", ignoreCase = true)
        val financeBad = fp.financePackagesFound.isNotEmpty()
        val mdmBad = fp.mdmAdmins.isNotEmpty()
        val fakeSpec = fp.specTamperWarnings.isNotEmpty()

        val warnings = ArrayList<String>()
        if (knoxBad) warnings.add("KnoxGuard:${fp.knoxGuardState}")
        if (financeBad) warnings.add("Finance:${fp.financePackagesFound.joinToString()}")
        if (mdmBad) warnings.add("MDM:${fp.mdmAdmins.size}")
        if (fakeSpec) warnings.add("FakeSpec:${fp.specTamperWarnings.size}")
        if (fp.bootloaderLocked.contains("UNLOCKED", ignoreCase = true)) warnings.add("BL Unlocked")
        if (fp.knoxWarrantyVoid.contains("TRIPPED", ignoreCase = true)) warnings.add("Knox 0x1")
        if (fp.rootIndicators.isNotEmpty()) warnings.add("Root (${fp.rootIndicators.size})")
        if (fp.widevineLevel == "L3") warnings.add("Widevine L3")

        val status = when {
            knoxBad || financeBad || mdmBad || fakeSpec -> TestStatus.FAIL
            fp.rootIndicators.isNotEmpty() || fp.knoxWarrantyVoid.contains("TRIPPED") ||
                fp.bootloaderLocked.contains("UNLOCKED") || fp.widevineLevel == "L3" -> TestStatus.WARN
            else -> TestStatus.PASS
        }
        val summary = if (warnings.isEmpty()) {
            "BL=${fp.bootloaderLocked} | KG=${fp.knoxGuardState} | Root=Clean | MDM=None | DRM=${fp.widevineLevel}"
        } else {
            "FLAG: ${warnings.joinToString(", ")} | DRM=${fp.widevineLevel}"
        }
        DiagnosticStore.updateItem("system_tamper", status, summary)
    }

    private fun handleDiagnosticItemClick(item: DiagnosticItem) {
        when (item.id) {
            "system_tamper" -> showFullFingerprintDialog()
            "storage_benchmark" -> runSingleStorageBenchmark()
            "display_pixels" -> startActivity(Intent(this, DisplayTestActivity::class.java))
            "touch_digitizer" -> startActivity(Intent(this, TouchTestActivity::class.java))
            "battery_charging" -> startActivity(Intent(this, BatteryDetailActivity::class.java))
            "audio_haptics" -> startActivity(Intent(this, AudioTestActivity::class.java))
            "camera_lenses" -> startActivity(Intent(this, CameraTestActivity::class.java))
            "sensors_biometrics" -> startActivity(Intent(this, SensorTestActivity::class.java))
            "connectivity_rf" -> startActivity(Intent(this, ConnectivityTestActivity::class.java))
            "buttons_ports" -> startActivity(Intent(this, ButtonPortTestActivity::class.java))
            "pre_buy_safety" -> startActivity(Intent(this, CodPreBuyActivity::class.java))
        }
    }

    private fun runSingleStorageBenchmark() {
        DiagnosticStore.updateItem(
            "storage_benchmark",
            TestStatus.RUNNING,
            Lang.t(this, "Menguji baca cold & tulis sync...", "Running cold read / sync write benchmark...")
        )
        executor.execute {
            val bench = DeviceFingerprintEngine.runStorageBenchmark(applicationContext)
            val fp = (DiagnosticStore.fingerprint ?: DeviceFingerprintEngine.collect(applicationContext))
                .copy(
                    storageReadMbps = bench.readMbps,
                    storageWriteMbps = bench.writeMbps,
                    storageBenchNote = bench.note
                )
            DiagnosticStore.fingerprint = fp
            DiagnosticStore.persistSnapshotMeta()
            val diskStr = ReportExporter.formatStorageMarketingAndUsable(fp.storageChipTotalBytes, fp.storageTotalBytes)
            DiagnosticStore.updateItem(
                "storage_benchmark",
                if (bench.readMbps > 0f && bench.writeMbps > 0f) TestStatus.PASS else TestStatus.WARN,
                "${fp.storageType} $diskStr: R ${bench.readMbps} | W ${bench.writeMbps} MB/s | Free ${ReportExporter.formatBytes(fp.storageFreeBytes)}"
            )
        }
    }

    private fun showFullFingerprintDialog() {
        val fp = DiagnosticStore.fingerprint
        val text = if (fp == null) {
            Lang.t(this, "Data hardware sedang dimuat...", "Loading hardware fingerprint...")
        } else {
            val diskLabel = ReportExporter.formatStorageMarketingAndUsable(fp.storageChipTotalBytes, fp.storageTotalBytes)
            buildString {
                appendLine("=== IDENTITY ===")
                appendLine("Commercial : ${fp.commercialName}")
                appendLine("Mfg/Brand  : ${fp.manufacturer} / ${fp.brand}")
                appendLine("Model/Dev  : ${fp.model} (${fp.deviceCodename})")
                appendLine("Board/HW   : ${fp.board} / ${fp.hardware}")
                appendLine("Android/API: ${fp.androidVersion}")
                appendLine("Sec Patch  : ${fp.securityPatch}")
                appendLine("Display ID : ${fp.buildDisplay}")
                appendLine("Build Tags : ${fp.buildTags}")
                appendLine()
                appendLine("=== SOC, CPU & GPU ===")
                appendLine("SoC        : ${fp.socBrand} ${fp.socModel}")
                appendLine("Arch/Core  : ${fp.cpuArchitecture} | ${fp.cpuImplementerSummary}")
                if (fp.cpuCoreClusters.isNotEmpty()) {
                    appendLine("Clusters   : ${fp.cpuCoreClusters.joinToString("; ")}")
                }
                appendLine("ABIs       : ${fp.abiList}")
                appendLine("GPU Vendor : ${fp.gpuVendor}")
                appendLine("GPU Model  : ${fp.gpuRenderer}")
                appendLine("GPU Ver    : ${fp.gpuVersion}")
                for (c in fp.cpuCores) {
                    val minMhz = c.minKhz / 1000L
                    val maxMhz = c.maxKhz / 1000L
                    val range = if (minMhz > 0L || maxMhz > 0L) " [$minMhz-$maxMhz]" else ""
                    appendLine("CPU${c.coreIndex}: ${c.curKhz / 1000}MHz$range (${c.governor})")
                }
                appendLine()
                appendLine("=== THERMALS ===")
                if (fp.thermalZones.isEmpty()) {
                    appendLine("Thermal sysfs restricted on this ROM")
                } else {
                    for (tz in fp.thermalZones) {
                        appendLine("${tz.zoneName} (${tz.type}): ${tz.tempCelsius}C")
                    }
                }
                appendLine()
                appendLine("=== MEMORY & STORAGE ===")
                appendLine("RAM Total  : ${ReportExporter.formatBytes(fp.ramTotalBytes)}")
                appendLine("RAM Avail  : ${ReportExporter.formatBytes(fp.ramAvailBytes)}")
                appendLine("ZRAM/Swap  : ${ReportExporter.formatBytes(fp.zramBytes)} / ${ReportExporter.formatBytes(fp.swapBytes)}")
                appendLine("Storage    : ${fp.storageType} (${fp.storageDeviceName})")
                appendLine("Capacity   : $diskLabel | Free: ${ReportExporter.formatBytes(fp.storageFreeBytes)}")
                appendLine("Benchmark  : R=${fp.storageReadMbps ?: "-"} MB/s | W=${fp.storageWriteMbps ?: "-"} MB/s (${fp.storageBenchNote})")
                appendLine()
                appendLine("=== TAMPER, KNOX, FINANCE & SPEC ===")
                appendLine("Bootloader : ${fp.bootloaderLocked}")
                appendLine("Verified   : ${fp.verifiedBootState}")
                appendLine("Knox Void  : ${fp.knoxWarrantyVoid}")
                appendLine("Knox Guard : ${fp.knoxGuardState}")
                appendLine("SELinux    : ${fp.selinuxMode}")
                appendLine("Widevine   : ${fp.widevineLevel} (SysID: ${fp.widevineSystemId})")
                appendLine("Root Flags : ${if (fp.rootIndicators.isEmpty()) "None" else fp.rootIndicators.joinToString("; ")}")
                appendLine("MDM/Admins : ${if (fp.mdmAdmins.isEmpty()) "None" else fp.mdmAdmins.joinToString("; ")}")
                appendLine("FinanceLock: ${if (fp.financePackagesFound.isEmpty()) "Clean" else fp.financePackagesFound.joinToString("; ")}")
                appendLine("Fake Spec  : ${if (fp.specTamperWarnings.isEmpty()) "None" else fp.specTamperWarnings.joinToString("; ")}")
                append("Kernel     : ${fp.kernelVersion}")
            }
        }

        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(40, 32, 40, 32)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_surface))
            addView(tv)
        }

        AlertDialog.Builder(this)
            .setTitle(Lang.t(this, "Detail Hardware & Keamanan", "Hardware & Security Inspector"))
            .setView(scroll)
            .setPositiveButton(Lang.t(this, "Tutup", "Close"), null)
            .show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
