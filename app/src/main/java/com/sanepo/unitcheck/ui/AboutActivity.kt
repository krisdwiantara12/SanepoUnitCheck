package com.sanepo.unitcheck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.BuildConfig
import com.sanepo.unitcheck.databinding.ActivityAboutBinding
import com.sanepo.unitcheck.engine.AppUpdateManager
import com.sanepo.unitcheck.engine.ReportExporter
import com.sanepo.unitcheck.util.Lang
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelDownloadFlag = AtomicBoolean(false)
    private var isBusy = false
    private var latestRelease: AppUpdateManager.ReleaseInfo? = null
    private var downloadedApk: File? = null
    private var pendingInstallAfterPerm = false

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissionAndCacheUi()
        if (pendingInstallAfterPerm) {
            pendingInstallAfterPerm = false
            val file = downloadedApk
            val rel = latestRelease
            if (file != null && file.exists()) {
                tryLaunchInstaller(file)
            } else if (rel != null && AppUpdateManager.canRequestPackageInstalls(this)) {
                startDownloadAndInstall(rel)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootAbout) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        val sigSha256 = AppUpdateManager.readApkSigningSha256(this)
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")

        binding.btnBackAbout.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvAboutTitle.text = Lang.t(this, "Tentang, Update & Info Teknis", "About, Updates & Tech Info")
        binding.tvVersionBadge.text = "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE}) | API 23..37"

        binding.tvDeveloperMeta.text = buildString {
            appendLine("Developer Utama : Kris Dwi Antara")
            appendLine("Tim Pengembang  : Sanepo Team")
            append("GitHub Resmi    : ${AppUpdateManager.REPO_URL}")
        }

        binding.tvBuildTechMeta.text = buildString {
            appendLine("Package / ABI   : ${BuildConfig.APPLICATION_ID} [$abis]")
            appendLine("Sign Cert SHA256: ${sigSha256.take(47)}...")
            append("Device Runtime  : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})")
        }

        binding.tvUpdateHeader.text = Lang.t(this, "SETTINGAN CEK UPDATE & OTA GITHUB", "UPDATE SETTINGS & DIRECT IN-APP OTA")
        binding.cbAutoCheckUpdate.text = Lang.t(
            this,
            "Otomatis cek update saat buka aplikasi (maks 1x per 12 jam)",
            "Auto-check for updates on app launch (throttled once per 12h)"
        )
        binding.cbIncludePreRelease.text = Lang.t(
            this,
            "Sertakan rilis Beta / Pre-release GitHub",
            "Include GitHub Beta / Pre-release builds"
        )
        binding.btnGrantInstallPerm.text = Lang.t(this, "IZINKAN INSTALL APK DARI SANEPO", "ALLOW INSTALL UNKNOWN APPS")
        binding.btnCheckUpdateNow.text = Lang.t(this, "CEK UPDATE SEKARANG", "CHECK FOR UPDATES NOW")
        binding.btnOpenGithubReleases.text = Lang.t(this, "BUKA HALAMAN RELEASES GITHUB", "OPEN GITHUB RELEASES IN BROWSER")

        binding.cbAutoCheckUpdate.isChecked = AppUpdateManager.isAutoCheckOnLaunch(this)
        binding.cbIncludePreRelease.isChecked = AppUpdateManager.isIncludePreReleases(this)

        binding.cbAutoCheckUpdate.setOnCheckedChangeListener { _, checked ->
            AppUpdateManager.setAutoCheckOnLaunch(this, checked)
        }
        binding.cbIncludePreRelease.setOnCheckedChangeListener { _, checked ->
            AppUpdateManager.setIncludePreReleases(this, checked)
        }

        binding.tvTechRefHeader.text = Lang.t(this, "ACUAN TEKNIS LAPANGAN & TRANSPARANSI", "FIELD REFERENCE THRESHOLDS & PRIVACY")
        binding.tvFieldThresholds.text = Lang.t(
            this,
            buildString {
                appendLine("STANDAR NILAI KRITIS INSPEKSI HP BEKAS:")
                appendLine("- Baterai Sehat   : Stress 45s min >= 3450 mV & drop <= 3%")
                appendLine("- Layar OLED/IPS  : Uji #1A1A1A (10% grey) untuk green-tint/shadow")
                appendLine("- Touchscreen     : Grid >= 95% tepi ke tepi & Ghost 5s = 0 hit")
                appendLine("- Sinyal/IMEI     : Baseband valid & SIM Ready -> Service IN_SERVICE")
                append("- Legal/Keamanan  : Knox Guard Clean, MDM kosong, PIN layar dilepas")
            },
            buildString {
                appendLine("USED-PHONE DIAGNOSTIC THRESHOLDS:")
                appendLine("- Battery Cell    : 45s stress min >= 3450 mV & <= 3% drop")
                appendLine("- OLED/IPS Panel  : Inspect 10% grey (#1A1A1A) for green tint/burn-in")
                appendLine("- Digitizer       : >= 95% edge grid coverage & 0 ghost touches in 5s")
                appendLine("- RF / Carrier    : Valid Baseband & SIM Ready -> Service IN_SERVICE")
                append("- Lock Legal      : Clean Knox Guard, no MDM/Finance apps, screen lock off")
            }
        )

        binding.tvPrivacyAudit.text = Lang.t(
            this,
            "PRIVASI & JARINGAN:\nSemua 11 tes diagnostik hardware berjalan 100% offline. Tidak ada IMEI, nomor seri, atau data pribadi yang dikirim ke server luar. Koneksi internet hanya digunakan ke api.github.com saat fitur Cek Update dijalankan.",
            "PRIVACY & NETWORK AUDIT:\nAll 11 hardware diagnostic modules run 100% offline. No IMEI, serial number, or device data is uploaded. Network calls connect strictly to api.github.com when update checks run."
        )

        binding.tvMaintHeader.text = Lang.t(this, "PEMELIHARAAN & UTILITAS CACHE", "CACHE MAINTENANCE & BUILD TOOLS")
        binding.btnClearAppCache.text = Lang.t(this, "BERSIHKAN CACHE", "CLEAR CACHE")
        binding.btnCopyBuildInfo.text = Lang.t(this, "SALIN INFO BUILD", "COPY BUILD INFO")

        binding.tvAboutFooter.text = "Dibuat di Ponorogo, Jawa Timur Indonesia \u2764\uFE0F\nKris Dwi Antara - Sanepo Team"

        binding.btnBackAbout.setOnClickListener { finish() }
        binding.btnGrantInstallPerm.setOnClickListener { openInstallPermissionSettings() }
        binding.btnCheckUpdateNow.setOnClickListener { runUpdateCheck() }
        binding.btnDownloadInstallUpdate.setOnClickListener {
            val file = downloadedApk
            val rel = latestRelease
            if (file != null && file.exists()) {
                tryLaunchInstaller(file)
            } else if (rel != null) {
                startDownloadAndInstall(rel)
            }
        }
        binding.btnOpenGithubReleases.setOnClickListener {
            openUrl(latestRelease?.htmlUrl ?: AppUpdateManager.RELEASES_PAGE_URL)
        }
        binding.btnClearAppCache.setOnClickListener {
            val count = AppUpdateManager.clearCacheDirs(this)
            downloadedApk = null
            refreshPermissionAndCacheUi()
            Toast.makeText(
                this,
                Lang.t(this, "$count item cache APK/laporan dihapus.", "Cleared $count cached APK/report item(s)."),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.btnCopyBuildInfo.setOnClickListener {
            val info = buildString {
                appendLine("Sanepo Unit Check v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                appendLine("Developers: Kris Dwi Antara & Sanepo Team (Ponorogo, Jawa Timur, Indonesia)")
                appendLine("Repo: ${AppUpdateManager.REPO_URL}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) | Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
                append("Signing SHA256: $sigSha256")
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SanepoBuildInfo", info))
            Toast.makeText(
                this,
                Lang.t(this, "Info build disalin ke clipboard.", "Build info copied to clipboard."),
                Toast.LENGTH_SHORT
            ).show()
        }

        renderInitialUpdateStatus()
        refreshPermissionAndCacheUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionAndCacheUi()
    }

    private fun refreshPermissionAndCacheUi() {
        val netReady = AppUpdateManager.hasNetworkConnection(this)
        val canInstall = AppUpdateManager.canRequestPackageInstalls(this)
        val installLabel = if (canInstall) {
            Lang.t(this, "Diizinkan", "Allowed")
        } else {
            Lang.t(this, "Belum Diizinkan (Tap tombol di bawah)", "Permission Required (Tap below)")
        }
        binding.tvInstallerPermState.text = Lang.t(
            this,
            "Jaringan: ${if (netReady) "Online" else "Offline"} | Izin Install APK: $installLabel",
            "Network: ${if (netReady) "Online" else "Offline"} | Install Unknown Apps: $installLabel"
        )
        binding.btnGrantInstallPerm.visibility = if (canInstall) View.GONE else View.VISIBLE

        val (updBytes, repBytes) = AppUpdateManager.getCacheUsageBytes(this)
        binding.tvCacheUsage.text = Lang.t(
            this,
            "Cache APK Update: ${ReportExporter.formatBytes(updBytes)} | Cache Laporan: ${ReportExporter.formatBytes(repBytes)}",
            "APK Update Cache: ${ReportExporter.formatBytes(updBytes)} | Report Cache: ${ReportExporter.formatBytes(repBytes)}"
        )
    }

    private fun renderInitialUpdateStatus() {
        val lastMs = AppUpdateManager.getLastCheckTimeMs(this)
        val cachedTag = AppUpdateManager.getCachedLatestTag(this)
        val lastStr = if (lastMs > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastMs))
        } else {
            Lang.t(this, "Belum pernah", "Never")
        }
        binding.tvUpdateStatus.text = buildString {
            appendLine(Lang.t(this@AboutActivity, "Versi Terpasang : v${BuildConfig.VERSION_NAME}", "Installed Version: v${BuildConfig.VERSION_NAME}"))
            appendLine(Lang.t(this@AboutActivity, "Terakhir Dicek  : $lastStr", "Last Checked     : $lastStr"))
            if (cachedTag.isNotEmpty()) {
                append("Tag Rilis Terakhir: $cachedTag")
            } else {
                append(Lang.t(this@AboutActivity, "Tekan CEK UPDATE SEKARANG untuk cek rilis GitHub.", "Tap CHECK FOR UPDATES NOW to query GitHub Releases."))
            }
        }
    }

    private fun runUpdateCheck() {
        if (isBusy) return
        isBusy = true
        binding.btnCheckUpdateNow.isEnabled = false
        binding.btnDownloadInstallUpdate.visibility = View.GONE
        binding.pbUpdateDownload.visibility = View.VISIBLE
        binding.pbUpdateDownload.isIndeterminate = true
        binding.tvUpdateStatus.text = Lang.t(
            this,
            "Menghubungi GitHub Releases (${AppUpdateManager.GITHUB_OWNER}/${AppUpdateManager.GITHUB_REPO})...",
            "Checking GitHub Releases (${AppUpdateManager.GITHUB_OWNER}/${AppUpdateManager.GITHUB_REPO})..."
        )

        val includePre = binding.cbIncludePreRelease.isChecked
        executor.execute {
            val outcome = AppUpdateManager.checkForUpdate(applicationContext, includePre)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isBusy = false
                binding.btnCheckUpdateNow.isEnabled = true
                binding.pbUpdateDownload.isIndeterminate = false
                binding.pbUpdateDownload.visibility = View.GONE

                when (outcome) {
                    is AppUpdateManager.CheckOutcome.Success -> {
                        val r = outcome.release
                        latestRelease = r
                        val sizeStr = if (r.apkSizeBytes > 0L) ReportExporter.formatBytes(r.apkSizeBytes) else "N/A"
                        val notes = r.body.trim().ifEmpty { "-" }.take(280)
                        if (r.updateAvailable) {
                            binding.tvUpdateStatus.text = buildString {
                                appendLine(Lang.t(this@AboutActivity, "UPDATE TERSEDIA: ${r.tagName}", "UPDATE AVAILABLE: ${r.tagName}"))
                                appendLine("APK: ${r.apkAssetName ?: "Belum dilampirkan di Release"} ($sizeStr)")
                                if (!r.expectedSha256.isNullOrBlank()) {
                                    appendLine("SHA256: ${r.expectedSha256.take(20)}...")
                                }
                                append("Catatan Rilis:\n$notes")
                            }
                            if (!r.apkDownloadUrl.isNullOrBlank()) {
                                binding.btnDownloadInstallUpdate.visibility = View.VISIBLE
                                binding.btnDownloadInstallUpdate.text = Lang.t(
                                    this@AboutActivity,
                                    "DOWNLOAD & INSTALL ${r.tagName} ($sizeStr)",
                                    "DOWNLOAD & INSTALL ${r.tagName} ($sizeStr)"
                                )
                            }
                        } else {
                            binding.tvUpdateStatus.text = buildString {
                                appendLine(Lang.t(this@AboutActivity, "APLIKASI SUDAH VERSI TERBARU", "UP TO DATE"))
                                appendLine("Installed: v${BuildConfig.VERSION_NAME} | GitHub Latest: ${r.tagName}")
                                append("Published: ${r.publishedAt.take(10)}")
                            }
                        }
                    }
                    is AppUpdateManager.CheckOutcome.NoReleasePublished -> {
                        binding.tvUpdateStatus.text = outcome.detail
                    }
                    is AppUpdateManager.CheckOutcome.Error -> {
                        binding.tvUpdateStatus.text = "ERROR: ${outcome.message}"
                    }
                }
            }
        }
    }

    private fun startDownloadAndInstall(release: AppUpdateManager.ReleaseInfo) {
        if (isBusy) return
        if (!AppUpdateManager.canRequestPackageInstalls(this)) {
            pendingInstallAfterPerm = true
            Toast.makeText(
                this,
                Lang.t(this, "Aktifkan izin install APK terlebih dahulu.", "Enable Install Unknown Apps first."),
                Toast.LENGTH_LONG
            ).show()
            openInstallPermissionSettings()
            return
        }

        isBusy = true
        cancelDownloadFlag.set(false)
        binding.btnCheckUpdateNow.isEnabled = false
        binding.btnDownloadInstallUpdate.isEnabled = false
        binding.pbUpdateDownload.visibility = View.VISIBLE
        binding.pbUpdateDownload.isIndeterminate = false
        binding.pbUpdateDownload.progress = 0

        executor.execute {
            val res = AppUpdateManager.downloadUpdateApk(
                context = applicationContext,
                release = release,
                cancelFlag = cancelDownloadFlag
            ) { pct, read, total ->
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        binding.pbUpdateDownload.progress = pct
                        val totalText = if (total > 0L) ReportExporter.formatBytes(total) else "?"
                        binding.tvUpdateStatus.text = Lang.t(
                            this@AboutActivity,
                            "Mengunduh ${release.tagName}: $pct% (${ReportExporter.formatBytes(read)} / $totalText)...",
                            "Downloading ${release.tagName}: $pct% (${ReportExporter.formatBytes(read)} / $totalText)..."
                        )
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isBusy = false
                binding.btnCheckUpdateNow.isEnabled = true
                binding.btnDownloadInstallUpdate.isEnabled = true
                refreshPermissionAndCacheUi()

                when (res) {
                    is AppUpdateManager.DownloadOutcome.Success -> {
                        downloadedApk = res.apkFile
                        binding.pbUpdateDownload.progress = 100
                        binding.tvUpdateStatus.text = buildString {
                            appendLine(Lang.t(this@AboutActivity, "Unduhan selesai & valid (SHA-256 terverifikasi).", "Download complete & verified."))
                            append("SHA256: ${res.actualSha256.take(24)}...")
                        }
                        binding.btnDownloadInstallUpdate.text = Lang.t(
                            this@AboutActivity,
                            "INSTALL SEKARANG (${res.apkFile.name})",
                            "INSTALL NOW (${res.apkFile.name})"
                        )
                        tryLaunchInstaller(res.apkFile)
                    }
                    is AppUpdateManager.DownloadOutcome.Error -> {
                        binding.pbUpdateDownload.visibility = View.GONE
                        binding.tvUpdateStatus.text = "ERROR: ${res.message}"
                    }
                }
            }
        }
    }

    private fun tryLaunchInstaller(apkFile: File) {
        val err = AppUpdateManager.launchApkInstaller(this, apkFile)
        if (err == "NEED_INSTALL_PERMISSION") {
            pendingInstallAfterPerm = true
            openInstallPermissionSettings()
        } else if (err != null) {
            binding.tvUpdateStatus.text = "ERROR: $err"
        }
    }

    private fun openInstallPermissionSettings() {
        val intent = AppUpdateManager.buildManageUnknownSourcesIntent(this)
        if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                unknownSourcesLauncher.launch(intent)
            } catch (_: Throwable) {
                Toast.makeText(this, "Settings unavailable on this ROM.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            Toast.makeText(this, "Browser tidak ditemukan.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        cancelDownloadFlag.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
