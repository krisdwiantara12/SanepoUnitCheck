package com.sanepo.unitcheck.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.databinding.ActivityCodPreBuyBinding
import com.sanepo.unitcheck.engine.DeviceFingerprintEngine
import com.sanepo.unitcheck.engine.ReportExporter
import com.sanepo.unitcheck.engine.SensorAndRFEngine
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.PriceCalculatorState
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang

class CodPreBuyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodPreBuyBinding
    private var deviceSecureLockActive = false
    private var criticalSecurityLockFound = false

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

        binding.btnBackCod.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvCodTitle.text = Lang.t(this, "Checklist COD & Kalkulator Harga", "COD Pre-Buy Check & Calculator")
        binding.tvCodAutoHeader.text = Lang.t(this, "VERIFIKASI KEAMANAN & LEGALITAS OTOMATIS", "AUTO LEGAL & SECURITY CHECK")
        binding.btnCodDialImei.text = "*#06# IMEI"
        binding.btnCodOpenAccounts.text = Lang.t(this, "CEK AKUN / SYNC", "CHECK ACCOUNTS")
        binding.btnCodOpenSecurity.text = Lang.t(this, "KUNCI LAYAR", "SCREEN LOCK")

        binding.tvCodChecklistHeader.text = Lang.t(this, "VERIFIKASI WAJIB SEBELUM TRANSFER", "MANDATORY PRE-BUY CHECKBOXES")
        binding.cbAccountClear.text = Lang.t(
            this,
            "Akun Google, Mi Cloud, Samsung & Apple/Cloud sudah Sign Out bersih",
            "Google, Mi Cloud, Samsung & vendor accounts cleanly signed out"
        )
        binding.cbImeiMatch.text = Lang.t(
            this,
            "IMEI *#06# cocok dengan dus/nota & terdaftar Kemenperin (ada sinyal)",
            "IMEI *#06# matches box & carrier whitelist (cellular works)"
        )
        binding.cbPhysicalOk.text = Lang.t(
            this,
            "Baut, lem frame, slot SIM & fisik casing sesuai kesepakatan COD",
            "Frame seals, screws, SIM tray & housing match seller agreement"
        )

        binding.tvCalcHeader.text = Lang.t(this, "KALKULATOR POTONGAN MINUS COD", "COD DEDUCTION & MAX-BUY CALCULATOR")
        binding.lblMarketPrice.text = Lang.t(this, "Harga Pasar Bekas Mulus (Rp)", "Clean Used Market Value (Rp)")
        binding.lblTargetProfit.text = Lang.t(this, "Target Margin / Laba Minimal (Rp)", "Target Margin / Profit Buffer (Rp)")
        binding.lblDefectDeduction.text = Lang.t(this, "Total Potongan Minus / Estimasi Servis (Rp)", "Total Repair / Defect Deductions (Rp)")
        binding.etPriceNote.hint = Lang.t(this, "Catatan minus nego (opsional)", "Negotiation defect notes (optional)")
        binding.btnCodPass.text = Lang.t(this, "UNIT AMAN DIBELI", "SAFE TO BUY / PASS")
        binding.btnCodFail.text = Lang.t(this, "BATAL / MINUS BERAT", "REJECT / FAIL")

        val ps = DiagnosticStore.priceState
        if (ps.marketPriceIdr > 0L) binding.etMarketPrice.setText(ps.marketPriceIdr.toString())
        binding.etTargetProfit.setText(ps.targetProfitIdr.toString())
        if (ps.defectDeductionIdr > 0L) binding.etDefectDeduction.setText(ps.defectDeductionIdr.toString())
        if (ps.customNote.isNotEmpty()) binding.etPriceNote.setText(ps.customNote)

        binding.btnBackCod.setOnClickListener { finish() }
        binding.btnCodDialImei.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:*%2306%23")))
            } catch (_: Throwable) {
            }
        }
        binding.btnCodOpenAccounts.setOnClickListener {
            openSettingsFallback(Settings.ACTION_SYNC_SETTINGS)
        }
        binding.btnCodOpenSecurity.setOnClickListener {
            openSettingsFallback(Settings.ACTION_SECURITY_SETTINGS)
        }

        binding.btnAddBatDeduction.setOnClickListener { adjustDeduction(150_000L) }
        binding.btnAddLcdDeduction.setOnClickListener { adjustDeduction(500_000L) }
        binding.btnClearDeduction.setOnClickListener { binding.etDefectDeduction.setText("0") }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                recalculateOffer()
            }
        }
        binding.etMarketPrice.addTextChangedListener(watcher)
        binding.etTargetProfit.addTextChangedListener(watcher)
        binding.etDefectDeduction.addTextChangedListener(watcher)
        binding.etPriceNote.addTextChangedListener(watcher)

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
            if (!binding.cbAccountClear.isChecked || !binding.cbImeiMatch.isChecked) {
                val msg = Lang.t(
                    this,
                    "Centang verifikasi Akun bersih & IMEI cocok sebelum PASS!",
                    "Check clean Account & IMEI match checkboxes before PASS!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            recalculateOffer()
            val offerStr = "Rp ${ReportExporter.formatIdr(DiagnosticStore.priceState.maxSafeBuyPriceIdr)}"
            DiagnosticStore.updateItem(
                "pre_buy_safety",
                TestStatus.PASS,
                "Clean Lock/Accounts/IMEI | Max Safe Buy: $offerStr",
                binding.tvCodAutoSafety.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        binding.btnCodFail.setOnClickListener {
            recalculateOffer()
            val reason = when {
                criticalSecurityLockFound -> "Knox Guard / Finance Lock"
                deviceSecureLockActive -> "Screen Lock / PIN Active"
                else -> "Pre-Buy Check Rejected by Inspector"
            }
            DiagnosticStore.updateItem(
                "pre_buy_safety",
                TestStatus.FAIL,
                "REJECT: $reason",
                binding.tvCodAutoSafety.text.toString()
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        recalculateOffer()
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

    private fun adjustDeduction(delta: Long) {
        val current = binding.etDefectDeduction.text.toString().toLongOrNull() ?: 0L
        binding.etDefectDeduction.setText((current + delta).toString())
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
            appendLine("Cellular RF   : ${rf.serviceStateLabel} | Baseband=${rf.basebandVersion.take(20)}")
            append("Root/BL/Spec  : BL=${fp.bootloaderLocked} | Root=${fp.rootIndicators.size} | FakeSpec=${fp.specTamperWarnings.size}")
        }

        val fails = DiagnosticStore.getItems().filter { it.status == TestStatus.FAIL && it.id != "pre_buy_safety" }
        binding.tvAutoDeductionHint.text = if (fails.isEmpty()) {
            Lang.t(this, "Belum ada modul FAIL tercatat dari tes hardware.", "No hardware FAIL modules logged yet.")
        } else {
            Lang.t(
                this,
                "Terdeteksi ${fails.size} modul FAIL (${fails.joinToString { it.id }}): tambahkan potongan servis!",
                "Detected ${fails.size} FAIL module(s) (${fails.joinToString { it.id }}): factor repair cost!"
            )
        }
    }

    private fun recalculateOffer() {
        val market = binding.etMarketPrice.text.toString().toLongOrNull() ?: 0L
        val profit = binding.etTargetProfit.text.toString().toLongOrNull() ?: 0L
        val deduction = binding.etDefectDeduction.text.toString().toLongOrNull() ?: 0L
        val note = binding.etPriceNote.text.toString().trim()
        val state = PriceCalculatorState(
            marketPriceIdr = market,
            targetProfitIdr = profit,
            defectDeductionIdr = deduction,
            customNote = note
        )
        DiagnosticStore.savePriceState(state)
        val maxStr = "Rp ${ReportExporter.formatIdr(state.maxSafeBuyPriceIdr)}"
        val marketStr = "Rp ${ReportExporter.formatIdr(market)}"
        val dedStr = "Rp ${ReportExporter.formatIdr(deduction)}"
        binding.tvMaxBuyResult.text = Lang.t(
            this,
            "BATAS ANGKAT AMAN MAX: $maxStr\n(Pasar $marketStr - Laba Rp ${ReportExporter.formatIdr(profit)} - Minus $dedStr)",
            "MAX SAFE BUY OFFER: $maxStr\n(Market $marketStr - Margin Rp ${ReportExporter.formatIdr(profit)} - Defect $dedStr)"
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
