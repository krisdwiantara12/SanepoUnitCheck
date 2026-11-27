package com.sanepo.unitcheck.model

enum class TestStatus(val code: String) {
    READY("READY"),
    RUNNING("RUNNING"),
    PASS("PASS"),
    WARN("WARN"),
    FAIL("FAIL"),
    UNSUPPORTED("N/A")
}

enum class TestCategory {
    SYSTEM,
    DISPLAY,
    BATTERY,
    AUDIO,
    CAMERA,
    SENSORS,
    CONNECTIVITY,
    KEYS_PORTS,
    COD_SAFETY
}

data class DiagnosticItem(
    val id: String,
    val category: TestCategory,
    val titleId: String,
    val titleEn: String,
    val subtitleId: String,
    val subtitleEn: String,
    val status: TestStatus = TestStatus.READY,
    val summary: String = "",
    val details: String = "",
    val timestampMs: Long = 0L,
    val isAutomated: Boolean = false
)

data class CpuCoreInfo(
    val coreIndex: Int,
    val minKhz: Long,
    val curKhz: Long,
    val maxKhz: Long,
    val governor: String
)

data class ThermalReading(
    val zoneName: String,
    val type: String,
    val tempCelsius: Float
)

data class DeviceFingerprint(
    val commercialName: String,
    val brand: String,
    val manufacturer: String,
    val model: String,
    val deviceCodename: String,
    val hardware: String,
    val board: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildDisplay: String,
    val buildTags: String,
    val abiList: String,
    val socBrand: String,
    val socModel: String,
    val cpuArchitecture: String,
    val cpuImplementerSummary: String,
    val cpuCoreClusters: List<String>,
    val cpuCores: List<CpuCoreInfo>,
    val thermalZones: List<ThermalReading>,
    val gpuVendor: String,
    val gpuRenderer: String,
    val gpuVersion: String,
    val ramTotalBytes: Long,
    val ramAvailBytes: Long,
    val zramBytes: Long,
    val swapBytes: Long,
    val storageType: String,
    val storageDeviceName: String,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
    val storageReadMbps: Float?,
    val storageWriteMbps: Float?,
    val storageBenchNote: String,
    val bootloaderLocked: String,
    val verifiedBootState: String,
    val knoxWarrantyVoid: String,
    val knoxGuardState: String,
    val rootIndicators: List<String>,
    val selinuxMode: String,
    val kernelVersion: String,
    val widevineLevel: String,
    val widevineSystemId: String,
    val mdmAdmins: List<String>,
    val financePackagesFound: List<String>,
    val specTamperWarnings: List<String>
)

data class DisplaySpecSummary(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val approxInches: Float,
    val activeRefreshHz: Float,
    val supportedRefreshRatesHz: List<Float>,
    val hdrCapabilities: List<String>,
    val wideColorGamut: Boolean
)

data class BatteryTelemetry(
    val levelPct: Int,
    val statusLabel: String,
    val isCharging: Boolean,
    val pluggedLabel: String,
    val healthLabel: String,
    val technology: String,
    val tempCelsius: Float,
    val voltageMv: Int,
    val currentMa: Int,
    val wattageW: Float,
    val designCapacityMah: Int?,
    val fullChargeCapacityMah: Int?,
    val estimatedHealthPct: Int?,
    val cycleCount: Int?,
    val chargeCounterMah: Int?,
    val chargingProtocolHint: String,
    val sysfsSourceUsed: String
)

data class BatteryStressSummary(
    val durationSec: Int,
    val startVoltageMv: Int,
    val minVoltageMv: Int,
    val voltageDropMv: Int,
    val startLevelPct: Int,
    val endLevelPct: Int,
    val maxTempCelsius: Float,
    val passed: Boolean,
    val verdictNote: String
)

data class CameraLensInfo(
    val cameraId: String,
    val facingLabel: String,
    val roleLabel: String,
    val physicalIds: List<String>,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val oisSupported: Boolean,
    val flashSupported: Boolean,
    val maxResolution: String,
    val sensorSizeMm: String,
    val hardwareLevel: String,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f
)

data class SensorInfoItem(
    val type: Int,
    val name: String,
    val vendor: String,
    val powerMa: Float,
    val maxRange: Float,
    val resolution: Float
)

data class ConnectivitySnapshot(
    val basebandVersion: String,
    val simSlotCount: Int,
    val sim1State: String,
    val sim2State: String,
    val operatorName: String,
    val networkType: String,
    val serviceStateLabel: String,
    val isCellularInService: Boolean,
    val signalDbm: Int?,
    val wifiSupported: Boolean,
    val wifi5GhzSupported: Boolean,
    val wifi6GhzSupported: Boolean,
    val wifiConnectedSsid: String,
    val wifiLinkSpeedMbps: Int?,
    val wifiScanCount: Int?,
    val bluetoothSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val bleSupported: Boolean,
    val nfcStatus: String,
    val gnssVisibleCount: Int,
    val gnssUsedCount: Int,
    val gnssConstellations: String,
    val ttffSeconds: Float?
)

data class PriceCalculatorState(
    val marketPriceIdr: Long = 0L,
    val targetProfitIdr: Long = 250_000L,
    val defectDeductionIdr: Long = 0L,
    val customNote: String = ""
) {
    val maxSafeBuyPriceIdr: Long
        get() = (marketPriceIdr - targetProfitIdr - defectDeductionIdr).coerceAtLeast(0L)
}
