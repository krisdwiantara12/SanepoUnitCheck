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
    private const val KEY_MARKET_PRICE = "market_price"
    private const val KEY_TARGET_PROFIT = "target_profit"
    private const val KEY_DEDUCTION = "defect_deduction"
    private const val KEY_PRICE_NOTE = "price_note"

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
    var priceState: PriceCalculatorState = PriceCalculatorState()

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
        priceState = PriceCalculatorState(
            marketPriceIdr = prefs.getLong(KEY_MARKET_PRICE, 0L),
            targetProfitIdr = prefs.getLong(KEY_TARGET_PROFIT, 250_000L),
            defectDeductionIdr = prefs.getLong(KEY_DEDUCTION, 0L),
            customNote = prefs.getString(KEY_PRICE_NOTE, "").orEmpty()
        )
        val rawStress = prefs.getString(KEY_STRESS_JSON, null)
        if (!rawStress.isNullOrBlank()) {
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
                subtitleId = "Bootloader, Root/Magisk, Knox/Finance Lock, Fake Spec & Widevine L1/L3",
                subtitleEn = "Bootloader, Root/Magisk, Knox/Finance Lock, Fake Spec & Widevine L1/L3",
                isAutomated = true
            ),
            DiagnosticItem(
                id = "storage_benchmark",
                category = TestCategory.SYSTEM,
                titleId = "Uji Memori Asli & Kecepatan Flash (eMMC/UFS)",
                titleEn = "Storage Chip & Sync Speed Benchmark",
                subtitleId = "Verifikasi tipe penyimpanan, baca cold & tulis langsung (bypass RAM cache)",
                subtitleEn = "Verify chip type, cold read & direct sync write (bypassing RAM cache)",
                isAutomated = true
            ),
            DiagnosticItem(
                id = "display_pixels",
                category = TestCategory.DISPLAY,
                titleId = "Layar: Dead Pixel, Burn-In & Refresh Rate Nyata",
                titleEn = "Display: Dead Pixel, Burn-In & Real Refresh Rate",
                subtitleId = "Kecerahan 100% paksa, warna penuh + grey 10%/50% + gradien + FPS nyata",
                subtitleEn = "Forced 100% brightness, solid + 10%/50% grey + gradient + real measured FPS",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "touch_digitizer",
                category = TestCategory.DISPLAY,
                titleId = "Touchscreen: Grid Penuh, Multi-Touch & Ghost Touch",
                titleEn = "Touchscreen: Full Grid, Multi-Touch & Ghost Touch",
                subtitleId = "Panel dapat digeser/lipat agar 100% grid tersentuh + monitor ghost 5 detik",
                subtitleEn = "Collapsible toolbar for 100% edge grid coverage + 5s idle ghost monitor",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "battery_charging",
                category = TestCategory.BATTERY,
                titleId = "Baterai, Sirkuit Cas & Stress Test 45 Detik",
                titleEn = "Battery Health, Charging & 45s Stress Sag Test",
                subtitleId = "Arus mA/Watt live, protokol USB/PD, serta uji beban voltase anti-baterai suntikan",
                subtitleEn = "Live mA/Watt, USB/PD protocol & 45s voltage sag stress test",
                isAutomated = true
            ),
            DiagnosticItem(
                id = "audio_haptics",
                category = TestCategory.AUDIO,
                titleId = "Audio Stereo (Earpiece / L-R), Dual-Mic & Getar",
                titleEn = "Stereo Audio (Earpiece / L-R), Dual-Mic & Haptics",
                subtitleId = "Tes Earpiece (API 31+ device routing), Speaker Kiri/Kanan, Dual-Mic & Getar",
                subtitleEn = "Earpiece routing, Left/Right stereo channels, Dual-Mic loopback & Haptics",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "camera_lenses",
                category = TestCategory.CAMERA,
                titleId = "Kamera Multi-Lensa (Zoom 0.6x–5x) & Flash",
                titleEn = "Multi-Lens Camera (0.6x–5x Zoom) & Flash",
                subtitleId = "Buka semua ID kamera & pindah lensa Ultrawide/Tele via zoom ratio + Torch",
                subtitleEn = "Open all physical IDs + switch Ultrawide/Tele via zoom ratio & Torch",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "sensors_biometrics",
                category = TestCategory.SENSORS,
                titleId = "Sensor Hardware (2-Arah) & Biometrik",
                titleEn = "Hardware Sensors (Bidirectional) & Biometrics",
                subtitleId = "Proximity wajib FAR+NEAR, Lux, Gyro/Accel waterpass, Kompas & Sidik Jari",
                subtitleEn = "Bidirectional Proximity (FAR+NEAR), Lux, Gyro/Accel, Compass & Biometric",
                isAutomated = false
            ),
            DiagnosticItem(
                id = "connectivity_rf",
                category = TestCategory.CONNECTIVITY,
                titleId = "Baseband, ServiceState Sinyal, Wi-Fi, BT, NFC & GNSS",
                titleEn = "Baseband, Cellular ServiceState, Wi-Fi, BT, NFC & GNSS",
                subtitleId = "Cek status IN_SERVICE (anti IMEI blokir), 5GHz/6GHz, BT, NFC & satelit GPS",
                subtitleEn = "Verify IN_SERVICE (anti-IMEI block), 5GHz/6GHz, BT, NFC & GNSS fix",
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
                titleId = "Cek Wajib Sebelum Bayar & Kalkulator Harga Nego",
                titleEn = "Pre-Buy Safety Checklist & COD Price Calculator",
                subtitleId = "Cek PIN/FRP/Akun Google/Mi, IMEI Kemenperin, sinyal & hitung batas angkat aman",
                subtitleEn = "Verify screen lock/FRP/accounts, IMEI, signal & calculate max safe offer",
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
        priceState = PriceCalculatorState()
        persistSessionLocked()
        notifyListeners()
    }

    @Synchronized
    fun resetItems() {
        populateDefaultsLocked()
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
    fun savePriceState(state: PriceCalculatorState) {
        priceState = state
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
            ctx.getSharedPreferences(PREF_SESSION, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS_JSON, arr.toString())
                .putString(KEY_STRESS_JSON, stressJsonStr)
                .putString(KEY_SELLER, sellerInfo)
                .putString(KEY_IMEI, unitImeiNote)
                .putString(KEY_INSPECTOR, inspectorNote)
                .putLong(KEY_MARKET_PRICE, priceState.marketPriceIdr)
                .putLong(KEY_TARGET_PROFIT, priceState.targetProfitIdr)
                .putLong(KEY_DEDUCTION, priceState.defectDeductionIdr)
                .putString(KEY_PRICE_NOTE, priceState.customNote)
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
