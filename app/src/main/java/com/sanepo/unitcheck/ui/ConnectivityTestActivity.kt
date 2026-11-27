package com.sanepo.unitcheck.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.unitcheck.R
import com.sanepo.unitcheck.databinding.ActivityConnectivityTestBinding
import com.sanepo.unitcheck.engine.OemDeviceHelper
import com.sanepo.unitcheck.engine.SensorAndRFEngine
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import com.sanepo.unitcheck.util.Lang
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ConnectivityTestActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityConnectivityTestBinding
    private lateinit var locationManager: LocationManager
    private var nfcAdapter: NfcAdapter? = null
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private var gnssStartMs = 0L
    private var ttffSec: Float? = null
    private var gnssVisible = 0
    private var gnssUsed = 0
    private var gnssConstellations = "Waiting fix..."
    private var lastNfcTagInfo = "Tap any e-Money/NFC card to rear coil"
    private var lastCellDataProbe = ""

    private val gnssCallbackObj: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    val visible = status.satelliteCount
                    var used = 0
                    val types = LinkedHashSet<String>()
                    for (i in 0 until visible) {
                        if (status.usedInFix(i)) used++
                        types.add(
                            when (status.getConstellationType(i)) {
                                GnssStatus.CONSTELLATION_GPS -> "GPS"
                                GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                                GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                                GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                                GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                                GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                                else -> "GNSS"
                            }
                        )
                    }
                    gnssVisible = visible
                    gnssUsed = used
                    gnssConstellations = if (types.isEmpty()) "Searching" else types.joinToString("/")
                    renderSnapshot()
                }

                override fun onFirstFix(ttffMillis: Int) {
                    ttffSec = (ttffMillis / 100f).roundToInt() / 10f
                    renderSnapshot()
                }
            }
        } else null
    }

    @Suppress("DEPRECATION")
    private val legacyGpsListener = GpsStatus.Listener { event ->
        if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            readLegacyGpsSatellites()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readLegacyGpsSatellites() {
        try {
            val gps = locationManager.getGpsStatus(null)
            var vis = 0
            var used = 0
            gps?.satellites?.forEach { sat ->
                vis++
                if (sat.usedInFix()) used++
            }
            gnssVisible = vis
            gnssUsed = used
            gnssConstellations = "GPS"
            renderSnapshot()
        } catch (_: Throwable) {
        }
    }

    private val rfPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startGnssMonitor()
        renderSnapshot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectivityTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        nfcAdapter = try {
            NfcAdapter.getDefaultAdapter(this)
        } catch (_: Throwable) {
            null
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootConnectivityTest) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        if (CodWizardFlow.isWizard(this)) {
            binding.tvWizardStep.visibility = View.VISIBLE
            binding.tvWizardStep.text = CodWizardFlow.stepLabel(this)
        }

        binding.btnBackConn.text = Lang.t(this, "KEMBALI", "BACK")
        binding.tvConnTitle.text = Lang.t(this, "RF, Dual-SIM, Data & GPS", "RF, Dual-SIM, Data & GPS")
        binding.btnReqRfPerms.text = Lang.t(this, "IZINKAN & SCAN RF/GPS", "GRANT PERMS & SCAN RF/GPS")
        binding.btnRefreshRf.text = Lang.t(this, "SEGARKAN", "REFRESH")
        binding.tvConnCellHeader.text = Lang.t(this, "BASEBAND, DUAL-SIM PER-SLOT & DATA", "BASEBAND, PER-SLOT SIM & DATA")
        binding.btnTestCellData.text = Lang.t(this, "TES DATA SELULER (3s)", "TEST CELLULAR DATA (3s)")
        binding.btnTestOperatorCall.text = Lang.t(this, "PINTASAN CEK TELEPON", "DIAL CARRIER CALL")
        binding.tvConnWifiBtHeader.text = Lang.t(this, "WI-FI BAND & BLUETOOTH", "WI-FI BANDS & BLUETOOTH")
        binding.tvConnNfcGnssHeader.text = Lang.t(this, "NFC & SATELIT GPS/GNSS", "NFC & GNSS SATELLITES")
        binding.btnConnPass.text = Lang.t(this, "KONEKTIVITAS PASS", "RF / GPS PASS")
        binding.btnConnFail.text = Lang.t(this, "BASEBAND / RF FAIL", "RF / BASEBAND FAIL")

        lastCellDataProbe = DiagnosticStore.connectivitySnapshot?.cellularDataCheckSummary.orEmpty()
        if (lastCellDataProbe.isNotBlank()) {
            binding.tvCellDataProbeResult.visibility = View.VISIBLE
            binding.tvCellDataProbeResult.text = lastCellDataProbe
        }

        binding.btnBackConn.setOnClickListener { finish() }
        binding.btnReqRfPerms.setOnClickListener { requestAllRfPermissions() }
        binding.btnRefreshRf.setOnClickListener {
            triggerWifiScan()
            renderSnapshot()
        }
        binding.btnTestCellData.setOnClickListener { runCellularDataTest() }
        binding.btnTestOperatorCall.setOnClickListener { launchOperatorCallHelper() }

        binding.btnConnPass.setOnClickListener {
            val snap = SensorAndRFEngine.collectConnectivitySnapshot(
                this, gnssVisible, gnssUsed, gnssConstellations, ttffSec, lastCellDataProbe
            )
            DiagnosticStore.connectivitySnapshot = snap
            DiagnosticStore.persistSnapshotMeta()

            val badBaseband = snap.basebandVersion.contains("unknown", ignoreCase = true) ||
                snap.basebandVersion.contains("missing", ignoreCase = true)
            if (badBaseband) {
                val msg = Lang.t(
                    this,
                    "Baseband UNKNOWN/hilang! Unit bermasalah modem/IMEI, tidak bisa PASS.",
                    "Baseband UNKNOWN/missing! Modem defect detected, cannot mark PASS."
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                binding.tvConnCellDetails.text = "$msg\n\n${binding.tvConnCellDetails.text}"
                return@setOnClickListener
            }
            if (snap.hasDualSimSplitBlock) {
                val msg = Lang.t(
                    this,
                    "PERINGATAN: Salah satu slot SIM terpasang kartu tapi NO SERVICE / terblokir IMEI!",
                    "WARNING: One SIM slot is inserted with NO SERVICE (possible split IMEI block)!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                DiagnosticStore.updateItem(
                    "connectivity_rf",
                    TestStatus.WARN,
                    "WARN: Split Dual-SIM Block! SIM1=${snap.sim1Details} | SIM2=${snap.sim2Details}"
                )
                CodWizardFlow.advanceOrFinish(this)
                return@setOnClickListener
            }
            val simInserted = snap.sim1State == "SIM READY" || snap.sim2State == "SIM READY"
            if (simInserted && !snap.isCellularInService && snap.serviceStateLabel != "Perm Required") {
                val msg = Lang.t(
                    this,
                    "SIM terpasang tapi ServiceState=${snap.serviceStateLabel} (waspada IMEI terblokir / sinyal putus)!",
                    "SIM inserted but ServiceState=${snap.serviceStateLabel} (possible IMEI block / RF fault)!"
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                DiagnosticStore.updateItem(
                    "connectivity_rf",
                    TestStatus.WARN,
                    "WARN: SIM Ready but ${snap.serviceStateLabel} | Baseband=${snap.basebandVersion.take(20)}"
                )
                CodWizardFlow.advanceOrFinish(this)
                return@setOnClickListener
            }
            DiagnosticStore.updateItem(
                "connectivity_rf",
                TestStatus.PASS,
                "Baseband=${snap.basebandVersion.take(20)} | ${snap.serviceStateLabel} | GNSS=${snap.gnssUsedCount}/${snap.gnssVisibleCount}"
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        binding.btnConnFail.setOnClickListener {
            val snap = SensorAndRFEngine.collectConnectivitySnapshot(
                this, gnssVisible, gnssUsed, gnssConstellations, ttffSec, lastCellDataProbe
            )
            DiagnosticStore.connectivitySnapshot = snap
            DiagnosticStore.persistSnapshotMeta()
            DiagnosticStore.updateItem(
                "connectivity_rf",
                TestStatus.FAIL,
                "FAIL: Baseband=${snap.basebandVersion} | State=${snap.serviceStateLabel} | SIM1=${snap.sim1Details} | Wi-Fi=${snap.wifiSupported}"
            )
            CodWizardFlow.advanceOrFinish(this)
        }

        renderSnapshot()
    }

    private fun runCellularDataTest() {
        binding.btnTestCellData.isEnabled = false
        binding.tvCellDataProbeResult.visibility = View.VISIBLE
        binding.tvCellDataProbeResult.setTextColor(ContextCompat.getColor(this, R.color.accent_amber_text))
        binding.tvCellDataProbeResult.text = Lang.t(
            this,
            "Menguji koneksi paket data melalui modem SIM (bypass Wi-Fi)...",
            "Testing direct cellular modem SIM packet reachability..."
        )
        probeExecutor.execute {
            val (ok, msg) = SensorAndRFEngine.runCellularDataProbe(applicationContext)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                binding.btnTestCellData.isEnabled = true
                lastCellDataProbe = msg
                binding.tvCellDataProbeResult.text = msg
                binding.tvCellDataProbeResult.setTextColor(
                    ContextCompat.getColor(
                        this@ConnectivityTestActivity,
                        if (ok) R.color.status_pass_fg else R.color.status_warn_fg
                    )
                )
                renderSnapshot()
            }
        }
    }

    private fun launchOperatorCallHelper() {
        val snap = DiagnosticStore.connectivitySnapshot
        val (ussd, desc) = OemDeviceHelper.getOperatorCheckUssd(snap?.operatorName.orEmpty())
        try {
            val uri = if (ussd.isNotEmpty()) {
                Uri.fromParts("tel", ussd, null)
            } else {
                Uri.parse("tel:")
            }
            startActivity(Intent(Intent.ACTION_DIAL, uri))
            Toast.makeText(
                this,
                Lang.t(this, "Tekan Call di Dialer untuk tes suara telepon & proximity ($desc).", "Tap Call in Dialer to verify voice call & earpiece."),
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
        }
    }

    private fun requestAllRfPermissions() {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.READ_PHONE_STATE)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        rfPermsLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun triggerWifiScan() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wm?.startScan()
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGnssMonitor() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            gnssConstellations = Lang.t(this, "Izin lokasi dibutuhkan untuk GNSS", "Location permission needed for GNSS")
            return
        }
        try {
            gnssStartMs = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                (gnssCallbackObj as? GnssStatus.Callback)?.let {
                    locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), it)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                (gnssCallbackObj as? GnssStatus.Callback)?.let {
                    locationManager.registerGnssStatusCallback(it, Handler(Looper.getMainLooper()))
                }
            } else {
                @Suppress("DEPRECATION")
                locationManager.addGpsStatusListener(legacyGpsListener)
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            } else {
                gnssConstellations = Lang.t(this, "GPS Nonaktif di Pengaturan", "GPS disabled in Location Settings")
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopGnssMonitor() {
        try {
            locationManager.removeUpdates(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                (gnssCallbackObj as? GnssStatus.Callback)?.let {
                    locationManager.unregisterGnssStatusCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                locationManager.removeGpsStatusListener(legacyGpsListener)
            }
        } catch (_: Throwable) {
        }
    }

    private fun renderSnapshot() {
        val snap = SensorAndRFEngine.collectConnectivitySnapshot(
            this, gnssVisible, gnssUsed, gnssConstellations, ttffSec, lastCellDataProbe
        )
        DiagnosticStore.connectivitySnapshot = snap

        binding.tvConnCellDetails.text = buildString {
            appendLine("Baseband : ${snap.basebandVersion}")
            appendLine("Service  : ${snap.serviceStateLabel} (InService=${snap.isCellularInService})")
            appendLine("SIM 1    : ${snap.sim1Details.ifEmpty { snap.sim1State }}")
            appendLine("SIM 2    : ${snap.sim2Details.ifEmpty { snap.sim2State }}")
            appendLine("Carrier  : ${snap.operatorName} | Net: ${snap.networkType}")
            if (snap.hasDualSimSplitBlock) {
                appendLine("[ALERT: SALAH SATU SLOT SIM TERBLOKIR / NO SERVICE!]")
            } else if ((snap.sim1State == "SIM READY" || snap.sim2State == "SIM READY") && !snap.isCellularInService && snap.serviceStateLabel != "Perm Required") {
                appendLine("[ALERT: SIM TERDETEKSI TAPI TIDAK ADA LAYANAN - CEK IMEI KEMENPERIN / RF!]")
            }
            append("Signal   : ${snap.signalDbm?.let { "$it dBm" } ?: "N/A (Grant Location/SIM)"}")
        }

        binding.tvConnWifiBtDetails.text = buildString {
            appendLine("Wi-Fi HW : ${snap.wifiSupported} | 5GHz: ${snap.wifi5GhzSupported} | 6GHz: ${snap.wifi6GhzSupported}")
            appendLine("Wi-Fi Now: ${snap.wifiConnectedSsid} ${snap.wifiLinkSpeedMbps?.let { "($it Mbps)" } ?: ""}")
            appendLine("AP Scan  : ${snap.wifiScanCount?.let { "$it networks visible" } ?: "Grant Location/Nearby Wi-Fi"}")
            append("Bluetooth: HW=${snap.bluetoothSupported} | BLE=${snap.bleSupported} | Enabled=${snap.bluetoothEnabled}")
        }

        binding.tvConnNfcGnssDetails.text = buildString {
            appendLine("NFC State: ${snap.nfcStatus}")
            appendLine("NFC Tag  : $lastNfcTagInfo")
            appendLine("GNSS Lock: Used=${snap.gnssUsedCount} / Visible=${snap.gnssVisibleCount}")
            append("Systems  : ${snap.gnssConstellations} | TTFF: ${snap.ttffSeconds?.let { "${it}s" } ?: "Pending"}")
        }
    }

    override fun onResume() {
        super.onResume()
        startGnssMonitor()
        try {
            nfcAdapter?.let { adapter ->
                val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getActivity(this, 0, intent, flags)
                adapter.enableForegroundDispatch(this, pi, null, null)
            }
        } catch (_: Throwable) {
        }
        renderSnapshot()
    }

    override fun onPause() {
        stopGnssMonitor()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action
        ) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            val uidHex = tag?.id?.joinToString(":") { "%02X".format(it) } ?: "Detected"
            lastNfcTagInfo = "TAG READ PASS -> UID: $uidHex"
            renderSnapshot()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (ttffSec == null && gnssStartMs > 0L) {
            val dt = ((System.currentTimeMillis() - gnssStartMs) / 100f).roundToInt() / 10f
            ttffSec = dt
        }
        renderSnapshot()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
