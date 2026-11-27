package com.sanepo.unitcheck.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivityCodPreBuyBinding
import com.sanepo.unitcheck.engine.DeviceFingerprintEngine
import com.sanepo.unitcheck.engine.OemDeviceHelper
import com.sanepo.unitcheck.engine.SensorAndRFEngine
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang

class CodPreBuyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodPreBuyBinding
    private var deviceSecureLockActive = false
    private var criticalSecurityLockFound = false

    private val barcodeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val raw = res.data?.getStringExtra(ImeiScannerActivity.EXTRA_RAW_CODE).orEmpty()
            val digits = res.data?.getStringExtra(ImeiScannerActivity.EXTRA_DIGITS).orEmpty()
            val luhnValid = res.data?.getBooleanExtra(ImeiScannerActivity.EXTRA_LUHN_VALID, false) ?: false

            val valueToSave = digits.ifEmpty { raw }
            if (valueToSave.isNotEmpty()) {
                DiagnosticStore.saveSessionMetadata(
                    seller = DiagnosticStore.sellerInfo,
                    imei = valueToSave,
                    note = DiagnosticStore.inspectorNote
                )
            }
            binding.tvScannedImeiResult.visibility = View.VISIBLE
            val luhnBadge = if (digits.length == 15) {
                if (luhnValid) "Luhn Digit-15: VALID" else "PERINGATAN: Luhn Digit-15 TIDAK VALID (Waspada stiker dus palsu!)"
            } else {
                "Kode/SN: $raw"
            }
            binding.tvScannedImeiResult.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (digits.length == 15 && !luhnValid) R.color.status_fail_fg else R.color.status_pass_fg
                )
            )
            binding.tvScannedImeiResult.text = "Hasil Scan Dus/Barcode: $valueToSave\n$luhnBadge"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodPreBuyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCodPreBuy) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        val oemProfile = OemDeviceHelper.buildProfile()

        binding.btnBackCod.contentDescription = Lang.t(this, "Kembali", "Back")
        binding.tvCodTitle.text = Lang.t(this, "Checklist Kilat COD & Cek IMEI", "Rapid COD Check & OEM IMEI")
        binding.tvCodAutoHeader.text = Lang.t(this, "VERIFIKASI KEAMANAN & LEGALITAS OTOMATIS", "AUTO LEGAL & SECURITY CHECK")
        binding.tvOemImeiHeader.text = Lang.t(
            this,
            "CEK IMEI SESUAI MERK (${oemProfile.displayBrandLabel.uppercase()})",
            "OEM IMEI & QC HELPER (${oemProfile.displayBrandLabel.uppercase()})"
        )
        binding.tvOemImeiGuide.text = buildString {
            appendLine("${oemProfile.displayBrandLabel} | ${oemProfile.osFlavorLabel}")
            append(Lang.t(this@CodPreBuyActivity, oemProfile.imeiSettingsPathId, oemProfile.imeiSettingsPathEn))
        }
        binding.btnCodImeiSettings.text = Lang.t(this, "PENGATURAN IMEI", "IMEI SETTINGS")
        binding.btnCodDialImei.text = "DIAL *#06"
        binding.btnCodScanBarcode.text = Lang.t(this, "SCAN DUS", "SCAN BOX")
        binding.btnCodOemQcCode.text = "QC ${oemProfile.primaryQcCode}"

        binding.btnCodOpenAccounts.text = Lang.t(this, "CEK AKUN / SYNC", "CHECK ACCOUNTS")
        binding.btnCodOpenSecurity.text = Lang.t(this, "KUNCI LAYAR", "SCREEN LOCK")

        binding.tvCodChecklistHeader.text = Lang.t(this, "CHECKLIST 1-TAP ANTI-BONCOS SEBELUM TRANSFER", "1-TAP PRE-PAYMENT CHECKLIST")
        binding.cbAccountClear.text = Lang.t(
            this,
            "Akun Google, Mi Cloud, Samsung & Family Link sudah Sign Out bersih",
            "Google, Mi Cloud, Samsung & vendor accounts cleanly signed out"
        )
        binding.cbResetReady.text = Lang.t(
            this,
            "Kunci layar (PIN/Pola/Face) sudah dihapus & siap Reset Pabrik",
            "Screen lock (PIN/Pattern) removed & unit ready for factory reset"
        )
        binding.cbImeiMatch.text = Lang.t(
            this,
            "IMEI Mesin cocok dengan Dus/Nota & SIM 1/SIM 2 muncul sinyal normal",
            "Device IMEI matches box/receipt & SIM slots get normal service"
        )
        binding.cbPhysicalOk.text = Lang.t(
            this,
            "Fisik aman (baut utuh, lem frame/LCD rapat, tidak ada bercak air di slot SIM)",
            "Screws, frame seals, backdoor/LCD glue & SIM liquid indicator look clean"
        )

        binding.tvDefectSummaryHeader.text = Lang.t(this, "RANGKUMAN TEMUAN UNIT (BAHAN NEGO OBJEKTIF)", "AUTO UNIT DEFECT SUMMARY")
        binding.btnCodPass.text = Lang.t(this, "UNIT AMAN DIBELI", "SAFE TO BUY / PASS")
        binding.btnCodFail.text = Lang.t(this, "BATAL / TOLAK UNIT", "REJECT / FAIL")

        if (DiagnosticStore.unitImeiNote.isNotBlank()) {
            binding.tvScannedImeiResult.visibility = View.VISIBLE
            binding.tvScannedImeiResult.text = "IMEI/SN Tercatat: ${DiagnosticStore.unitImeiNote}"
        }

        binding.btnBackCod.setOnClickListener { finish() }
        binding.btnCodImeiSettings.setOnClickListener {
            if (!OemDeviceHelper.openDeviceInfoSettings(this)) {
                openSettingsFallback(Settings.ACTION_SETTINGS)
            }
        }
        binding.btnCodDialImei.setOnClickListener {
            OemDeviceHelper.openSmartDialerWithCode(this, "*#06#")
        }
        binding.btnCodScanBarcode.setOnClickListener {
            barcodeLauncher.launch(Intent(this, ImeiScannerActivity::class.java))
        }
        binding.btnCodOemQcCode.setOnClickListener {
            OemDeviceHelper.showOemImeiAndQcDialog(this) {
                barcodeLauncher.launch(Intent(this, ImeiScannerActivity::class.java))
            }
        }
        binding.btnCodOpenAccounts.setOnClickListener {
            openSettingsFallback(Settings.ACTION_SYNC_SETTINGS)
        }
        binding.btnCodOpenSecurity.setOnClickListener {
            openSettingsFallback(Settings.ACTION_SECURITY_SETTINGS)
        }

        binding.btnCodPass.setOnClickListener {
            refreshAutoSafety()
            if (criticalSecurityLockFound) {
                val msg = Lang.t(
                    this,
                    "TERDETEKSI KNOX GUARD / FINANCE LOCK / MDM! Jangan beli unit cicilan/lock.",
                    "KNOX GUARD / FINANCE LOCK / MDM DETECTED! Do not buy locked unit."
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (deviceSecureLockActive) {
                val msg = Lang.t(
                    this,
                    "Kunci layar (PIN/Pola/Sandi) masih aktif! Minta penjual hapus di KUNCI LAYAR.",
                    "Screen lock (PIN/Pattern/Password) still active! Seller must remove it first."
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!binding.cbAccountClear.isChecked || !binding.cbResetReady.isChecked || !binding.cbImeiMatch.isChecked) {
                val msg = Lang.t(
                    this,
                    "Centang Akun bersih, Siap Reset & IMEI cocok sebelum PASS!",
                    "Check clean Account, Reset-Ready & IMEI match checkboxes before PASS!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val issueCount = DiagnosticStore.getItems().count {
                (it.status == TestStatus.FAIL || it.status == TestStatus.WARN) && it.id != "pre_buy_safety"
            }
            val imeiTag = if (DiagnosticStore.unitImeiNote.isNotBlank()) " | IMEI:${DiagnosticStore.unitImeiNote.take(16)}" else ""
            DiagnosticStore.updateItem(
                "pre_buy_safety",
                if (issueCount > 0) TestStatus.WARN else TestStatus.PASS,
                "Clean Lock/Accounts/IMEI ($issueCount note(s))$imeiTag",
                binding.tvCodAutoSafety.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        binding.btnCodFail.setOnClickListener {
            val reason = when {
                criticalSecurityLockFound -> "Knox Guard / Finance Lock"
                deviceSecureLockActive -> "Screen Lock / PIN Active"
                else -> "Pre-Buy COD Check Rejected"
            }
            DiagnosticStore.updateItem(
                "pre_buy_safety",
                TestStatus.FAIL,
                "REJECT: $reason",
                binding.tvCodAutoSafety.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        refreshAutoSafety()
    }

    override fun onResume() {
        super.onResume()
        refreshAutoSafety()
    }

    private fun openSettingsFallback(primaryAction: String) {
        try {
            startActivity(Intent(primaryAction))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    private fun refreshAutoSafety() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        deviceSecureLockActive = km?.isDeviceSecure == true

        val fp = DiagnosticStore.fingerprint ?: DeviceFingerprintEngine.collect(applicationContext).also {
            DiagnosticStore.fingerprint = it
        }
        val rf = DiagnosticStore.connectivitySnapshot ?: SensorAndRFEngine.collectConnectivitySnapshot(applicationContext).also {
            DiagnosticStore.connectivitySnapshot = it
        }

        val knoxBad = fp.knoxGuardState.contains("LOCK", ignoreCase = true) ||
            fp.knoxGuardState.contains("PRENORMAL", ignoreCase = true)
        val financeBad = fp.financePackagesFound.isNotEmpty()
        val mdmBad = fp.mdmAdmins.isNotEmpty()
        criticalSecurityLockFound = knoxBad || financeBad || mdmBad

        val lockLabel = if (deviceSecureLockActive) {
            Lang.t(this, "TERKUNCI (PIN/Pola Aktif - WAJIB HAPUS)", "LOCKED (PIN/Pattern Active - REMOVE FIRST)")
        } else {
            Lang.t(this, "BERSIH (Tidak ada kunci layar)", "CLEAN (No screen lock set)")
        }

        binding.tvCodAutoSafety.text = buildString {
            appendLine("Screen Lock   : $lockLabel")
            appendLine("Knox Guard    : ${fp.knoxGuardState}")
            appendLine("Finance/PayJoy: ${if (financeBad) fp.financePackagesFound.joinToString() else "Clean"}")
            appendLine("MDM Admin     : ${if (mdmBad) fp.mdmAdmins.joinToString() else "None"}")
            appendLine("Cellular RF   : ${rf.serviceStateLabel} | SIM1=${rf.sim1State} SIM2=${rf.sim2State}")
            append("Root/BL/Spec  : BL=${fp.bootloaderLocked} | Root=${fp.rootIndicators.size} | FakeSpec=${fp.specTamperWarnings.size}")
        }

        val fails = DiagnosticStore.getItems().filter { it.status == TestStatus.FAIL && it.id != "pre_buy_safety" }
        val warns = DiagnosticStore.getItems().filter { it.status == TestStatus.WARN && it.id != "pre_buy_safety" }

        when {
            criticalSecurityLockFound -> {
                binding.tvAutoDefectVerdict.setBackgroundResource(R.drawable.bg_badge_fail)
                binding.tvAutoDefectVerdict.setTextColor(ContextCompat.getColor(this, R.color.status_fail_fg))
                binding.tvAutoDefectVerdict.text = Lang.t(
                    this,
                    "STATUS: BAHAYA TINGGI (TOLAK UNIT) - Terdeteksi Leasing/MDM/KnoxGuard!",
                    "VERDICT: DO NOT BUY - Leasing / MDM / Knox Guard active!"
                )
            }
            fails.isNotEmpty() || rf.hasDualSimSplitBlock -> {
                binding.tvAutoDefectVerdict.setBackgroundResource(R.drawable.bg_badge_fail)
                binding.tvAutoDefectVerdict.setTextColor(ContextCompat.getColor(this, R.color.status_fail_fg))
                binding.tvAutoDefectVerdict.text = buildString {
                    appendLine(Lang.t(this@CodPreBuyActivity, "TEMUAN MINUS KERAS (${fails.size} FAIL) - BAHAN NEGO / BATALKAN:", "CRITICAL DEFECTS (${fails.size} FAIL):"))
                    if (rf.hasDualSimSplitBlock) {
                        appendLine("- Slot SIM terblokir sebelah (Waspada IMEI Inter!)")
                    }
                    fails.forEach { appendLine("- [${it.titleId.take(26)}]: ${it.summary}") }
                    warns.forEach { appendLine("- [CATATAN]: ${it.summary}") }
                }
            }
            warns.isNotEmpty() -> {
                binding.tvAutoDefectVerdict.setBackgroundResource(R.drawable.bg_badge_warn)
                binding.tvAutoDefectVerdict.setTextColor(ContextCompat.getColor(this, R.color.status_warn_fg))
                binding.tvAutoDefectVerdict.text = buildString {
                    appendLine(Lang.t(this@CodPreBuyActivity, "ADA ${warns.size} CATATAN INSPEKSI (CEK SEBELUM DEAL):", "${warns.size} INSPECTION WARNING(S):"))
                    warns.forEach { appendLine("- ${it.summary}") }
                }
            }
            else -> {
                binding.tvAutoDefectVerdict.setBackgroundResource(R.drawable.bg_badge_pass)
                binding.tvAutoDefectVerdict.setTextColor(ContextCompat.getColor(this, R.color.status_pass_fg))
                binding.tvAutoDefectVerdict.text = Lang.t(
                    this,
                    "STATUS: BERSIH - Belum ada minus hardware atau keamanan tercatat.",
                    "VERDICT: CLEAN - No hardware defects or lock alerts detected."
                )
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
}
