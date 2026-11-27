package com.sanepo.unitcheck.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object DiagnosticStore {
    interface Listener {
        fun onDiagnosticsUpdated()
    }

    private const val PREF_SESSION = "sanepo_session_v1"
    private const val KEY_ITEMS_JSON = "items_json"
    private const val KEY_STRESS_JSON = "battery_stress_json"
    private const val KEY_SELLER = "seller_info"
    private const val KEY_IMEI = "imei_note"
    private const val KEY_INSPECTOR = "inspector_note"
    private const val KEY_DISPLAY_JSON = "display_specs_json"
    private const val KEY_CONNECTIVITY_JSON = "connectivity_json"
    private const val KEY_FP_META_JSON = "fingerprint_meta_json"

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val itemsMap = LinkedHashMap<String, DiagnosticItem>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loadedFromDisk = false

    @Volatile
    var fingerprint: DeviceFingerprint? = null

    @Volatile
    var displaySpecs: DisplaySpecSummary? = null

    @Volatile
    var batteryTelemetry: BatteryTelemetry? = null

    @Volatile
    var batteryStressSummary: BatteryStressSummary? = null

    @Volatile
    var cameraLenses: List<CameraLensInfo> = emptyList()

    @Volatile
    var connectivitySnapshot: ConnectivitySnapshot? = null

    @Volatile
    var batteryHistoryMa: List<Int> = emptyList()

    @Volatile
    var inspectorNote: String = ""

    @Volatile
    var sellerInfo: String = ""

    @Volatile
    var unitImeiNote: String = ""

    @Volatile
    var touchSamplingHzPeak: Float = 0f

    @Volatile
    var barometerSealResult: String = ""

    init {
        populateDefaultsLocked()
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (loadedFromDisk) return
        loadedFromDisk = true
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREF_SESSION, Context.MODE_PRIVATE)
        sellerInfo = prefs.getString(KEY_SELLER, "").orEmpty()
        unitImeiNote = prefs.getString(KEY_IMEI, "").orEmpty()
        inspectorNote = prefs.getString(KEY_INSPECTOR, "").orEmpty()

        prefs.getString(KEY_STRESS_JSON, null)?.takeIf { it.isNotBlank() }?.let { rawStress ->
            try {
                val sObj = JSONObject(rawStress)
                batteryStressSummary = BatteryStressSummary(
                    durationSec = sObj.optInt("durationSec", 45),
                    startVoltageMv = sObj.optInt("startVoltageMv", 0),
                    minVoltageMv = sObj.optInt("minVoltageMv", 0),
                    voltageDropMv = sObj.optInt("voltageDropMv", 0),
                    startLevelPct = sObj.optInt("startLevelPct", 0),
                    endLevelPct = sObj.optInt("endLevelPct", 0),
                    maxTempCelsius = sObj.optDouble("maxTempCelsius", 0.0).toFloat(),
                    passed = sObj.optBoolean("passed", false),
                    verdictNote = sObj.optString("verdictNote", "")
                )
            } catch (_: Throwable) {
            }
        }

        prefs.getString(KEY_DISPLAY_JSON, null)?.takeIf { it.isNotBlank() }?.let { rawDisp ->
            try {
                val d = JSONObject(rawDisp)
                displaySpecs = DisplaySpecSummary(
                    widthPx = d.optInt("widthPx", 0),
                    heightPx = d.optInt("heightPx", 0),
                    densityDpi = d.optInt("densityDpi", 0),
                    approxInches = d.optDouble("approxInches", 0.0).toFloat(),
                    activeRefreshHz = d.optDouble("activeRefreshHz", 60.0).toFloat(),
                    supportedRefreshRatesHz = jsonArrayToFloatList(d.optJSONArray("supportedHz")),
                    hdrCapabilities = jsonArrayToStringList(d.optJSONArray("hdr")),
                    wideColorGamut = d.optBoolean("wideGamut", false)
                )
            } catch (_: Throwable) {
            }
        }

        prefs.getString(KEY_CONNECTIVITY_JSON, null)?.takeIf { it.isNotBlank() }?.let { rawRf ->
            try {
                val r = JSONObject(rawRf)
                connectivitySnapshot = ConnectivitySnapshot(
                    basebandVersion = r.optString("baseband", "Unknown"),
                    simSlotCount = r.optInt("simSlots", 0),
                    sim1State = r.optString("sim1State", "UNKNOWN"),
                    sim2State = r.optString("sim2State", "UNKNOWN"),
                    operatorName = r.optString("operator", "Unknown"),
                    networkType = r.optString("networkType", "Unknown"),
                    serviceStateLabel = r.optString("serviceState", "Unknown"),
                    isCellularInService = r.optBoolean("inService", false),
                    signalDbm = if (r.has("signalDbm") && !r.isNull("signalDbm")) r.optInt("signalDbm") else null,
                    wifiSupported = r.optBoolean("wifiSupported", false),
                    wifi5GhzSupported = r.optBoolean("wifi5Ghz", false),
                    wifi6GhzSupported = r.optBoolean("wifi6Ghz", false),
                    wifiConnectedSsid = r.optString("wifiSsid", ""),
                    wifiLinkSpeedMbps = if (r.has("wifiSpeed") && !r.isNull("wifiSpeed")) r.optInt("wifiSpeed") else null,
                    wifiScanCount = if (r.has("wifiScanCount") && !r.isNull("wifiScanCount")) r.optInt("wifiScanCount") else null,
                    bluetoothSupported = r.optBoolean("btSupported", false),
                    bluetoothEnabled = r.optBoolean("btEnabled", false),
                    bleSupported = r.optBoolean("bleSupported", false),
                    nfcStatus = r.optString("nfcStatus", "N/A"),
                    gnssVisibleCount = r.optInt("gnssVis", 0),
                    gnssUsedCount = r.optInt("gnssUsed", 0),
                    gnssConstellations = r.optString("gnssSys", "Idle"),
                    ttffSeconds = if (r.has("ttffSec") && !r.isNull("ttffSec")) r.optDouble("ttffSec").toFloat() else null,
                    sim1Details = r.optString("sim1Details", ""),
                    sim2Details = r.optString("sim2Details", ""),
                    hasDualSimSplitBlock = r.optBoolean("splitBlock", false),
                    cellularDataCheckSummary = r.optString("cellDataCheck", "")
                )
            } catch (_: Throwable) {
            }
        }

        prefs.getString(KEY_FP_META_JSON, null)?.takeIf { it.isNotBlank() }?.let { rawFp ->
            try {
                val f = JSONObject(rawFp)
                fingerprint = DeviceFingerprint(
                    commercialName = f.optString("commercialName"),
                    brand = f.optString("brand"),
                    manufacturer = f.optString("manufacturer"),
                    model = f.optString("model"),
                    deviceCodename = f.optString("deviceCodename"),
                    hardware = f.optString("hardware"),
                    board = f.optString("board"),
                    androidVersion = f.optString("androidVersion"),
                    sdkInt = f.optInt("sdkInt", 0),
                    securityPatch = f.optString("securityPatch"),
                    buildDisplay = f.optString("buildDisplay"),
                    buildTags = f.optString("buildTags"),
                    abiList = f.optString("abiList"),
                    socBrand = f.optString("socBrand"),
                    socModel = f.optString("socModel"),
                    cpuArchitecture = f.optString("cpuArch"),
                    cpuImplementerSummary = f.optString("cpuImpl"),
                    cpuCoreClusters = jsonArrayToStringList(f.optJSONArray("clusters")),
                    cpuCores = emptyList(),
                    thermalZones = emptyList(),
                    gpuVendor = f.optString("gpuVendor"),
                    gpuRenderer = f.optString("gpuRenderer"),
                    gpuVersion = f.optString("gpuVersion"),
                    ramTotalBytes = f.optLong("ramTotalBytes"),
                    ramAvailBytes = f.optLong("ramAvailBytes"),
                    zramBytes = f.optLong("zramBytes"),
                    swapBytes = f.optLong("swapBytes"),
                    storageType = f.optString("storageType"),
                    storageDeviceName = f.optString("storageDeviceName"),
                    storageTotalBytes = f.optLong("storageTotalBytes"),
                    storageFreeBytes = f.optLong("storageFreeBytes"),
                    storageChipTotalBytes = f.optLong("storageChipTotalBytes"),
                    storageReadMbps = if (f.has("readMbps") && !f.isNull("readMbps")) f.optDouble("readMbps").toFloat() else null,
                    storageWriteMbps = if (f.has("writeMbps") && !f.isNull("writeMbps")) f.optDouble("writeMbps").toFloat() else null,
                    storageBenchNote = f.optString("benchNote"),
                    bootloaderLocked = f.optString("bootloader"),
                    verifiedBootState = f.optString("verifiedBoot"),
                    knoxWarrantyVoid = f.optString("knoxVoid"),
                    knoxGuardState = f.optString("knoxGuard"),
                    rootIndicators = jsonArrayToStringList(f.optJSONArray("root")),
                    selinuxMode = f.optString("selinux"),
                    kernelVersion = f.optString("kernel"),
                    widevineLevel = f.optString("widevine"),
                    widevineSystemId = f.optString("widevineId"),
                    mdmAdmins = jsonArrayToStringList(f.optJSONArray("mdm")),
                    financePackagesFound = jsonArrayToStringList(f.optJSONArray("finance")),
                    specTamperWarnings = jsonArrayToStringList(f.optJSONArray("tamper"))
                )
            } catch (_: Throwable) {
            }
        }

        val rawJson = prefs.getString(KEY_ITEMS_JSON, null) ?: return
        try {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val base = itemsMap[id] ?: continue
                val statusName = obj.optString("status", TestStatus.READY.name)
                val status = try {
                    TestStatus.valueOf(statusName)
                } catch (_: Throwable) {
                    TestStatus.READY
                }
                itemsMap[id] = base.copy(
                    status = status,
                    summary = obj.optString("summary", ""),
                    details = obj.optString("details", ""),
                    timestampMs = obj.optLong("timestampMs", 0L)
                )
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun populateDefaultsLocked() {
        itemsMap.clear()
        val defaults = listOf(
            DiagnosticItem(
                id = "system_tamper",
                category = TestCategory.SYSTEM,
                titleId = "Keamanan, Knox Guard, MDM & Widevine",
                titleEn = "Security, Knox Guard, MDM & Widevine",
                subtitleId = "Bootloader, Root/Magisk, Knox/Finance Lock (Kredivo/PayJoy/SF+), Fake Spec & Widevine",
                subtitleEn = "Bootloader, Root/Magisk, Knox/Finance Lock, Fake Spec & Widevine L1/L3",
                isAutomated = true
            ),
            DiagnosticItem(
                id = "storage_benchmark",
                category = TestCategory.SYSTEM,
                titleId = "Uji Memori Asli & Kecepatan Flash (eMMC/UFS)",
                titleEn = "Storage Chip & Sync Speed Benchmark",
                subtitleId = "Kapasitas chip asli vs usable, baca cold & tulis langsung (bypass RAM cache)",
                subtitleEn = "Verify real flash capacity tier, cold read & direct sync write",
                isAutomated = true
            ),
            DiagnosticItem(
                id = "display_pixels",
                category = TestCategory.DISPLAY,
                titleId = "Layar: Dead Pixel, Burn-In & Refresh Rate Nyata",
                titleEn = "Display: Dead Pixel, Burn-In & Real Refresh Rate",
                subtitleId = "Warna penuh + grey 10%/50% + gradien + pengukur FPS nyata (anti drop LTPO)",
                subtitleEn = "Solid + 10%/50% grey + gradient + active LTPO-safe FPS measurement",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "touch_digitizer",
                category = TestCategory.DISPLAY,
                titleId = "Touchscreen, Sampling Rate & Deteksi LCD KW",
                titleEn = "Touchscreen, Sampling Rate & Replacement Panel Check",
                subtitleId = "Grid penuh + ukur Hz digitizer + batas jari multi-touch (anti LCD KW) + ghost monitor",
                subtitleEn = "Edge grid + hardware touch Hz probe + multi-touch cap + ghost monitor",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "battery_charging",
                category = TestCategory.BATTERY,
                titleId = "Baterai, Sirkuit Cas & Stress Test 45 Detik",
                titleEn = "Battery Health, Charging & 45s Stress Sag Test",
                subtitleId = "Arus mA/Watt live (support 1S & 2S Dual-Cell), uji beban voltase anti-baterai drop",
                subtitleEn = "Live mA/Watt (1S & 2S Dual-Cell aware) & 45s voltage sag stress test",
                isAutomated = true
            ),
            DiagnosticItem(
                id = "audio_haptics",
                category = TestCategory.AUDIO,
                titleId = "Audio Stereo, Dual-Mic Loopback & Getar",
                titleEn = "Stereo Audio, Dual-Mic Loopback & Haptics",
                subtitleId = "Earpiece, Speaker L/R, rekam Mic Bawah/Atas (auto-gain) & loopback kilat COD",
                subtitleEn = "Earpiece, L/R speakers, Bottom/Top mic with auto-gain & instant COD loopback",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "camera_lenses",
                category = TestCategory.CAMERA,
                titleId = "Kamera Multi-Lensa, Cek Jamur & Fokus AF",
                titleEn = "Multi-Lens Camera, Sensor Spot Check & Tap AF",
                subtitleId = "Validasi frame nyata, Zoom UW/Tele, Tap-to-Focus AF lock, Mode Spot Jamur & Torch",
                subtitleEn = "Real frame verification, UW/Tele zoom, AF lock probe, Spot Check & Torch",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "sensors_biometrics",
                category = TestCategory.SENSORS,
                titleId = "Sensor, Proximity (IR/Virtual), Segel Barometer & Biometrik",
                titleEn = "Sensors, Proximity, Barometer Seal & Biometrics",
                subtitleId = "Proximity 2-arah (IR & Virtual Xiaomi/Samsung), Gyro, Segel IP68 (jika support) & Sidik Jari",
                subtitleEn = "Bidirectional Proximity (IR & Virtual), Gyro, Barometer seal (if supported) & Bio",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "connectivity_rf",
                category = TestCategory.CONNECTIVITY,
                titleId = "Baseband, Dual-SIM Per-Slot, Tes Data & GNSS",
                titleEn = "Baseband, Per-Slot Dual-SIM, Data Ping & GNSS",
                subtitleId = "Cek sinyal SIM 1 & SIM 2 terpisah (anti IMEI blokir sebelah), Tes Data 3s, Wi-Fi & GPS",
                subtitleEn = "Separate SIM 1 & SIM 2 state (anti split IMEI block), 3s cellular ping, Wi-Fi & GPS",
                isAutomated = true
            ),
            DiagnosticItem(
                id = "buttons_ports",
                category = TestCategory.KEYS_PORTS,
                titleId = "Tombol Fisik, Trigger, USB OTG & Jack 3.5mm",
                titleEn = "Physical Buttons, Triggers, USB OTG & 3.5mm Jack",
                subtitleId = "Vol +/-, Power/Wake, Back/Recents, Trigger gaming, deteksi OTG & headset",
                subtitleEn = "Vol +/-, Power/Wake, Back/Recents, gaming triggers, USB OTG & headset plug",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "pre_buy_safety",
                category = TestCategory.COD_SAFETY,
                titleId = "Checklist Kilat COD, Cek IMEI Per-Merk & Anti-Skam",
                titleEn = "Rapid COD Safety Checklist & OEM IMEI Check",
                subtitleId = "Cek IMEI *#06#/Pengaturan/Scan Dus sesuai merk, akun kosong, segel fisik & rangkuman minus",
                subtitleEn = "OEM IMEI guide + box scanner, account/lock sign-out check & auto defect summary",
                isAutomated = false
            )
        )
        for (item in defaults) {
            itemsMap[item.id] = item
        }
    }

    @Synchronized
    fun resetSession(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        populateDefaultsLocked()
        batteryHistoryMa = emptyList()
        batteryStressSummary = null
        inspectorNote = ""
        sellerInfo = ""
        unitImeiNote = ""
        touchSamplingHzPeak = 0f
        barometerSealResult = ""
        persistSessionLocked()
        notifyListeners()
    }

    @Synchronized
    fun resetItems() {
        populateDefaultsLocked()
        persistSessionLocked()
        notifyListeners()
    }

    @Synchronized
    fun getItems(): List<DiagnosticItem> = itemsMap.values.toList()

    @Synchronized
    fun getItem(id: String): DiagnosticItem? = itemsMap[id]

    @Synchronized
    fun updateItem(
        id: String,
        status: TestStatus,
        summary: String,
        details: String = ""
    ) {
        val current = itemsMap[id] ?: return
        itemsMap[id] = current.copy(
            status = status,
            summary = summary,
            details = details.ifEmpty { current.details },
            timestampMs = System.currentTimeMillis()
        )
        persistSessionLocked()
        notifyListeners()
    }

    @Synchronized
    fun saveSessionMetadata(seller: String, imei: String, note: String) {
        sellerInfo = seller
        unitImeiNote = imei
        inspectorNote = note
        persistSessionLocked()
    }

    @Synchronized
    fun persistSnapshotMeta() {
        persistSessionLocked()
    }

    @Synchronized
    fun appendBatterySample(currentMa: Int) {
        val updated = batteryHistoryMa.toMutableList()
        updated.add(currentMa)
        if (updated.size > 30) {
            updated.removeAt(0)
        }
        batteryHistoryMa = updated
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(arr.optString(i))
        }
        return out
    }

    private fun jsonArrayToFloatList(arr: JSONArray?): List<Float> {
        if (arr == null) return emptyList()
        val out = ArrayList<Float>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(arr.optDouble(i, 60.0).toFloat())
        }
        return out
    }

    private fun persistSessionLocked() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (item in itemsMap.values) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("status", item.status.name)
                obj.put("summary", item.summary)
                obj.put("details", item.details)
                obj.put("timestampMs", item.timestampMs)
                arr.put(obj)
            }
            val stressJsonStr = batteryStressSummary?.let { s ->
                JSONObject().apply {
                    put("durationSec", s.durationSec)
                    put("startVoltageMv", s.startVoltageMv)
                    put("minVoltageMv", s.minVoltageMv)
                    put("voltageDropMv", s.voltageDropMv)
                    put("startLevelPct", s.startLevelPct)
                    put("endLevelPct", s.endLevelPct)
                    put("maxTempCelsius", s.maxTempCelsius.toDouble())
                    put("passed", s.passed)
                    put("verdictNote", s.verdictNote)
                }.toString()
            }
            val dispJsonStr = displaySpecs?.let { d ->
                JSONObject().apply {
                    put("widthPx", d.widthPx)
                    put("heightPx", d.heightPx)
                    put("densityDpi", d.densityDpi)
                    put("approxInches", d.approxInches.toDouble())
                    put("activeRefreshHz", d.activeRefreshHz.toDouble())
                    put("supportedHz", JSONArray(d.supportedRefreshRatesHz.map { it.toDouble() }))
                    put("hdr", JSONArray(d.hdrCapabilities))
                    put("wideGamut", d.wideColorGamut)
                }.toString()
            }
            val rfJsonStr = connectivitySnapshot?.let { r ->
                JSONObject().apply {
                    put("baseband", r.basebandVersion)
                    put("simSlots", r.simSlotCount)
                    put("sim1State", r.sim1State)
                    put("sim2State", r.sim2State)
                    put("operator", r.operatorName)
                    put("networkType", r.networkType)
                    put("serviceState", r.serviceStateLabel)
                    put("inService", r.isCellularInService)
                    put("signalDbm", r.signalDbm)
                    put("wifiSupported", r.wifiSupported)
                    put("wifi5Ghz", r.wifi5GhzSupported)
                    put("wifi6Ghz", r.wifi6GhzSupported)
                    put("wifiSsid", r.wifiConnectedSsid)
                    put("wifiSpeed", r.wifiLinkSpeedMbps)
                    put("wifiScanCount", r.wifiScanCount)
                    put("btSupported", r.bluetoothSupported)
                    put("btEnabled", r.bluetoothEnabled)
                    put("bleSupported", r.bleSupported)
                    put("nfcStatus", r.nfcStatus)
                    put("gnssVis", r.gnssVisibleCount)
                    put("gnssUsed", r.gnssUsedCount)
                    put("gnssSys", r.gnssConstellations)
                    put("ttffSec", r.ttffSeconds?.toDouble())
                    put("sim1Details", r.sim1Details)
                    put("sim2Details", r.sim2Details)
                    put("splitBlock", r.hasDualSimSplitBlock)
                    put("cellDataCheck", r.cellularDataCheckSummary)
                }.toString()
            }
            val fpJsonStr = fingerprint?.let { f ->
                JSONObject().apply {
                    put("commercialName", f.commercialName)
                    put("brand", f.brand)
                    put("manufacturer", f.manufacturer)
                    put("model", f.model)
                    put("deviceCodename", f.deviceCodename)
                    put("hardware", f.hardware)
                    put("board", f.board)
                    put("androidVersion", f.androidVersion)
                    put("sdkInt", f.sdkInt)
                    put("securityPatch", f.securityPatch)
                    put("buildDisplay", f.buildDisplay)
                    put("buildTags", f.buildTags)
                    put("abiList", f.abiList)
                    put("socBrand", f.socBrand)
                    put("socModel", f.socModel)
                    put("cpuArch", f.cpuArchitecture)
                    put("cpuImpl", f.cpuImplementerSummary)
                    put("clusters", JSONArray(f.cpuCoreClusters))
                    put("gpuVendor", f.gpuVendor)
                    put("gpuRenderer", f.gpuRenderer)
                    put("gpuVersion", f.gpuVersion)
                    put("ramTotalBytes", f.ramTotalBytes)
                    put("ramAvailBytes", f.ramAvailBytes)
                    put("zramBytes", f.zramBytes)
                    put("swapBytes", f.swapBytes)
                    put("storageType", f.storageType)
                    put("storageDeviceName", f.storageDeviceName)
                    put("storageTotalBytes", f.storageTotalBytes)
                    put("storageFreeBytes", f.storageFreeBytes)
                    put("storageChipTotalBytes", f.storageChipTotalBytes)
                    put("readMbps", f.storageReadMbps?.toDouble())
                    put("writeMbps", f.storageWriteMbps?.toDouble())
                    put("benchNote", f.storageBenchNote)
                    put("bootloader", f.bootloaderLocked)
                    put("verifiedBoot", f.verifiedBootState)
                    put("knoxVoid", f.knoxWarrantyVoid)
                    put("knoxGuard", f.knoxGuardState)
                    put("root", JSONArray(f.rootIndicators))
                    put("selinux", f.selinuxMode)
                    put("kernel", f.kernelVersion)
                    put("widevine", f.widevineLevel)
                    put("widevineId", f.widevineSystemId)
                    put("mdm", JSONArray(f.mdmAdmins))
                    put("finance", JSONArray(f.financePackagesFound))
                    put("tamper", JSONArray(f.specTamperWarnings))
                }.toString()
            }

            ctx.getSharedPreferences(PREF_SESSION, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS_JSON, arr.toString())
                .putString(KEY_STRESS_JSON, stressJsonStr)
                .putString(KEY_DISPLAY_JSON, dispJsonStr)
                .putString(KEY_CONNECTIVITY_JSON, rfJsonStr)
                .putString(KEY_FP_META_JSON, fpJsonStr)
                .putString(KEY_SELLER, sellerInfo)
                .putString(KEY_IMEI, unitImeiNote)
                .putString(KEY_INSPECTOR, inspectorNote)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.onDiagnosticsUpdated()
        }
    }
}
