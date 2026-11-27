package com.sanepo.unitcheck.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sanepo.unitcheck.BuildConfig
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.engine.ReportExporter
import com.sanepo.unitcheck.model.DiagnosticItem
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang

class DashboardAdapter(
    private val context: Context,
    private val onRunAutoScan: () -> Unit,
    private val onShowFullSpecs: () -> Unit,
    private val onStartCodWizard: () -> Unit,
    private val onResetSession: () -> Unit,
    private val onOpenAbout: () -> Unit,
    private val onItemClick: (DiagnosticItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        object Deck : Row()
        data class Header(val title: String) : Row()
        data class TestRow(val item: DiagnosticItem) : Row()
        object Footer : Row()
    }

    private var rows: List<Row> = emptyList()
    var isScanning: Boolean = false
    var scanProgressPct: Int = 0

    fun submitData(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Deck -> 0
            is Row.Header -> 1
            is Row.TestRow -> 2
            is Row.Footer -> 3
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> DeckVH(inflater.inflate(R.layout.item_dashboard_deck, parent, false))
            1 -> HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
            2 -> TestVH(inflater.inflate(R.layout.item_diagnostic_row, parent, false))
            else -> FooterVH(inflater.inflate(R.layout.item_footer_about, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Deck -> (holder as DeckVH).bind()
            is Row.Header -> (holder as HeaderVH).bind(row.title)
            is Row.TestRow -> (holder as TestVH).bind(row.item)
            is Row.Footer -> (holder as FooterVH).bind()
        }
    }

    inner class DeckVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvAlertBanner: TextView = view.findViewById(R.id.tvSecurityAlertBanner)
        private val tvHeadline: TextView = view.findViewById(R.id.tvVerdictHeadline)
        private val tvCounter: TextView = view.findViewById(R.id.tvScanCounter)
        private val tvCounts: TextView = view.findViewById(R.id.tvVerdictCounts)
        private val progressBar: ProgressBar = view.findViewById(R.id.progressScan)
        private val btnScan: Button = view.findViewById(R.id.btnRunAutoScan)
        private val btnSpecs: Button = view.findViewById(R.id.btnFullSpecs)
        private val btnCodWizard: Button = view.findViewById(R.id.btnCodWizard)
        private val btnResetSession: Button = view.findViewById(R.id.btnResetSession)
        private val tvIdHeader: TextView = view.findViewById(R.id.tvIdentityHeader)
        private val tvIdBody: TextView = view.findViewById(R.id.tvIdentityBody)

        fun bind() {
            val items = DiagnosticStore.getItems()
            val pass = items.count { it.status == TestStatus.PASS }
            val warn = items.count { it.status == TestStatus.WARN }
            val fail = items.count { it.status == TestStatus.FAIL }
            val verified = pass + warn + fail

            val fp = DiagnosticStore.fingerprint
            val rf = DiagnosticStore.connectivitySnapshot
            val criticalAlerts = ArrayList<String>()
            if (fp != null) {
                if (fp.knoxGuardState.contains("LOCK", ignoreCase = true) ||
                    fp.knoxGuardState.contains("PRENORMAL", ignoreCase = true)
                ) {
                    criticalAlerts.add("KnoxGuard: ${fp.knoxGuardState}")
                }
                if (fp.financePackagesFound.isNotEmpty()) {
                    criticalAlerts.add("Finance Lock App: ${fp.financePackagesFound.joinToString()}")
                }
                if (fp.mdmAdmins.isNotEmpty()) {
                    criticalAlerts.add("MDM Device Admin: ${fp.mdmAdmins.joinToString()}")
                }
                if (fp.specTamperWarnings.isNotEmpty()) {
                    criticalAlerts.add("Spec Tamper: ${fp.specTamperWarnings.joinToString()}")
                }
            }
            if (rf != null && rf.hasDualSimSplitBlock) {
                criticalAlerts.add("Dual-SIM Split Block: ${rf.sim1Details} / ${rf.sim2Details}")
            }

            if (criticalAlerts.isNotEmpty()) {
                tvAlertBanner.visibility = View.VISIBLE
                tvAlertBanner.text = Lang.t(
                    context,
                    "PERINGATAN RISIKO TINGGI:\n${criticalAlerts.joinToString("\n")}",
                    "CRITICAL SECURITY / TAMPER ALERT:\n${criticalAlerts.joinToString("\n")}"
                )
            } else {
                tvAlertBanner.visibility = View.GONE
            }

            tvHeadline.text = when {
                isScanning -> Lang.t(context, "MEMINDAI HARDWARE...", "SCANNING HARDWARE...")
                fail > 0 -> Lang.t(context, "PERLU PERHATIAN ($fail FAIL)", "ATTENTION NEEDED ($fail FAIL)")
                warn > 0 -> Lang.t(context, "PERIKSA CATATAN ($warn WARN)", "REVIEW WARNINGS ($warn WARN)")
                verified == items.size -> Lang.t(context, "SEMUA MODUL LULUS", "ALL MODULES PASSED")
                verified > 0 -> Lang.t(context, "DIAGNOSTIK BERJALAN", "INSPECTION IN PROGRESS")
                else -> Lang.t(context, "SIAP PERIKSA UNIT", "READY TO INSPECT UNIT")
            }

            tvCounter.text = "$verified / ${items.size}"
            tvCounts.text = "PASS: $pass  |  WARN: $warn  |  FAIL: $fail"
            progressBar.progress = if (isScanning) scanProgressPct else ((verified * 100) / items.size.coerceAtLeast(1))

            btnScan.text = if (isScanning) {
                Lang.t(context, "MEMINDAI...", "SCANNING...")
            } else {
                Lang.t(context, "JALANKAN AUTO-SCAN", "RUN AUTO-SCAN")
            }
            btnScan.isEnabled = !isScanning
            btnScan.setOnClickListener { onRunAutoScan() }

            btnSpecs.text = Lang.t(context, "SPEK DETAIL", "FULL SPECS")
            btnSpecs.setOnClickListener { onShowFullSpecs() }

            btnCodWizard.text = Lang.t(context, "MODE KILAT COD 90s / WIZARD", "QUICK COD 90s / WIZARD")
            btnCodWizard.setOnClickListener { onStartCodWizard() }

            btnResetSession.text = Lang.t(context, "RESET SESI", "RESET SESSION")
            btnResetSession.setOnClickListener { onResetSession() }

            tvIdHeader.text = Lang.t(context, "IDENTITAS HARDWARE & KEAMANAN", "HARDWARE & SECURITY IDENTITY")
            tvIdBody.text = if (fp == null) {
                Lang.t(context, "Membaca identitas OEM, SoC, GPU, Bootloader & Widevine...", "Reading OEM identity, SoC, GPU, Bootloader & Widevine...")
            } else {
                val rootLabel = if (fp.rootIndicators.isEmpty()) "Clean" else "${fp.rootIndicators.size} flag(s)"
                val mdmLabel = if (fp.mdmAdmins.isEmpty()) "None" else "${fp.mdmAdmins.size} active"
                val diskStr = ReportExporter.formatStorageMarketingAndUsable(fp.storageChipTotalBytes, fp.storageTotalBytes)
                buildString {
                    appendLine("Unit      : ${fp.commercialName} [${fp.deviceCodename}]")
                    appendLine("OS / Patch: Android ${fp.androidVersion} | ${fp.securityPatch}")
                    appendLine("SoC / GPU : ${fp.socBrand} ${fp.socModel} | ${fp.gpuRenderer}")
                    appendLine("RAM / Disk: ${ReportExporter.formatBytes(fp.ramTotalBytes)} | ${fp.storageType} $diskStr")
                    append("Boot/DRM  : BL=${fp.bootloaderLocked} | KG=${fp.knoxGuardState} | Root=$rootLabel | MDM=$mdmLabel | DRM=${fp.widevineLevel}")
                }
            }
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        fun bind(title: String) {
            tvTitle.text = title
        }
    }

    inner class TestVH(view: View) : RecyclerView.ViewHolder(view) {
        private val root: View = view.findViewById(R.id.rowRoot)
        private val tvTitle: TextView = view.findViewById(R.id.tvRowTitle)
        private val tvSub: TextView = view.findViewById(R.id.tvRowSubtitle)
        private val tvSummary: TextView = view.findViewById(R.id.tvRowSummary)
        private val tvBadge: TextView = view.findViewById(R.id.tvRowBadge)

        fun bind(item: DiagnosticItem) {
            val autoTag = if (item.isAutomated) " [AUTO]" else ""
            tvTitle.text = Lang.t(context, item.titleId, item.titleEn) + autoTag
            tvSub.text = Lang.t(context, item.subtitleId, item.subtitleEn)

            if (item.summary.isNotBlank()) {
                tvSummary.visibility = View.VISIBLE
                tvSummary.text = item.summary
            } else {
                tvSummary.visibility = View.GONE
            }

            tvBadge.text = item.status.code
            when (item.status) {
                TestStatus.PASS -> {
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_pass)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.status_pass_fg))
                }
                TestStatus.WARN -> {
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_warn)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.status_warn_fg))
                }
                TestStatus.FAIL -> {
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_fail)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.status_fail_fg))
                }
                else -> {
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_ready)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.status_ready_fg))
                }
            }
            root.setOnClickListener { onItemClick(item) }
        }
    }

    inner class FooterVH(view: View) : RecyclerView.ViewHolder(view) {
        private val btnAbout: Button = view.findViewById(R.id.btnFooterOpenAbout)
        private val tvDevs: TextView = view.findViewById(R.id.tvFooterDevelopers)
        private val tvOrigin: TextView = view.findViewById(R.id.tvFooterOrigin)

        fun bind() {
            btnAbout.text = Lang.t(
                context,
                "ABOUT, SETTINGAN & CEK UPDATE (v${BuildConfig.VERSION_NAME})",
                "ABOUT, UPDATE SETTINGS & INFO (v${BuildConfig.VERSION_NAME})"
            )
            btnAbout.setOnClickListener { onOpenAbout() }
            tvDevs.text = "Developers: Kris Dwi Antara • Sanepo Team"
            tvOrigin.text = "Dibuat di Ponorogo, Jawa Timur Indonesia \u2764\uFE0F"
        }
    }
}
