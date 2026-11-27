package com.sanepo.unitcheck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.BuildConfig
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivityReportBinding
import com.sanepo.unitcheck.engine.ReportExporter
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.util.Lang
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private var suppressWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticStore.ensureLoaded(applicationContext)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootReport) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = "${CodWizardFlow.stepLabel(this)} - ${Lang.t(this, "SELESAI! TINJAU & EKSPOR", "COMPLETE! REVIEW & EXPORT")}"
        }

        binding.btnBackReport.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvReportTitle.text = Lang.t(this, "Laporan Teknisi & Ekspor", "Technician Report & Export")
        binding.tvMetaHeader.text = Lang.t(this, "CATATAN TRANSAKSI / IMEI (OPSIONAL)", "TRANSACTION & IMEI NOTES (OPTIONAL)")
        binding.btnCopyTextReport.text = Lang.t(this, "SALIN TEKS", "COPY TEXT")
        binding.btnExportJson.text = "SHARE JSON"
        binding.btnExportPdf.text = "EXPORT PDF"
        binding.btnShowQr.text = Lang.t(this, "QR VERIFIKASI", "VERIFY QR")
        binding.btnShareCardPng.text = Lang.t(this, "SHARE KARTU PNG", "SHARE CARD PNG")

        suppressWatcher = true
        binding.etSellerInfo.setText(DiagnosticStore.sellerInfo)
        binding.etImeiNote.setText(DiagnosticStore.unitImeiNote)
        binding.etInspectorNote.setText(DiagnosticStore.inspectorNote)
        suppressWatcher = false

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                DiagnosticStore.saveSessionMetadata(
                    seller = binding.etSellerInfo.text.toString().trim(),
                    imei = binding.etImeiNote.text.toString().trim(),
                    note = binding.etInspectorNote.text.toString().trim()
                )
                refreshPreview()
            }
        }
        binding.etSellerInfo.addTextChangedListener(watcher)
        binding.etImeiNote.addTextChangedListener(watcher)
        binding.etInspectorNote.addTextChangedListener(watcher)

        binding.btnBackReport.setOnClickListener { finish() }
        binding.btnDialImei.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:*%2306%23")))
            } catch (_: Throwable) {
            }
        }
        binding.btnCopyTextReport.setOnClickListener {
            val txt = ReportExporter.buildTextReport()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SanepoUnitReport", txt))
            showFeedback(Lang.t(this, "Teks laporan disalin ke clipboard.", "Report text copied to clipboard."))
        }
        binding.btnExportJson.setOnClickListener {
            val file = ReportExporter.exportJsonFile(this)
            shareFile(file, "application/json")
        }
        binding.btnExportPdf.setOnClickListener {
            val file = ReportExporter.exportPdfFile(this)
            shareFile(file, "application/pdf")
        }
        binding.btnShowQr.setOnClickListener { showVerifyQrDialog() }
        binding.btnShareCardPng.setOnClickListener {
            val file = ReportExporter.exportCardPngFile(this)
            shareFile(file, "image/png")
        }

        refreshPreview()
    }

    private fun refreshPreview() {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val hash = ReportExporter.computeVerifyHash(dateStr)
        binding.tvVerifyHash.text = "VERIFY SHA-256: #$hash | Offline Signed Audit"
        binding.tvReportPreview.text = ReportExporter.buildTextReport()
    }

    private fun showVerifyQrDialog() {
        val payload = ReportExporter.buildCompactQrPayload()
        val bmp = ReportExporter.generateQrBitmap(payload, 560)
        if (bmp == null) {
            showFeedback("QR generation error.")
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_surface))
        }
        val qrView = ImageView(this).apply {
            setImageBitmap(bmp)
            setBackgroundColor(Color.WHITE)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            adjustViewBounds = true
        }
        val caption = TextView(this).apply {
            text = payload
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
        }
        container.addView(qrView)
        container.addView(caption)

        AlertDialog.Builder(this)
            .setTitle(Lang.t(this, "QR Verifikasi Offline (Anti-Edit)", "Offline Verification QR"))
            .setView(container)
            .setPositiveButton(Lang.t(this, "Tutup", "Close"), null)
            .show()
    }

    private fun shareFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, file.name))
            showFeedback("${file.name} ready (${file.length()} bytes)")
        } catch (t: Throwable) {
            showFeedback("Share failed: ${t.message}")
        }
    }

    private fun showFeedback(msg: String) {
        binding.tvExportFeedback.visibility = View.VISIBLE
        binding.tvExportFeedback.text = msg
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
