package com.sanepo.unitcheck.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.sanepo.unitcheck.model.BatteryTelemetry
import com.sanepo.unitcheck.util.SystemPropReader
import kotlin.math.abs
import kotlin.math.roundToInt

object BatteryHardwareReader {
    private val DESIGN_CAP_PATHS = arrayOf(
        "/sys/class/power_supply/battery/charge_full_design",
        "/sys/class/power_supply/bms/charge_full_design",
        "/sys/class/power_supply/battery/batt_full_capacity",
        "/sys/class/oplus_chg/battery/design_capacity"
    )
    private val FULL_CAP_PATHS = arrayOf(
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/bms/charge_full",
        "/sys/class/power_supply/battery/fg_fullcapnom",
        "/sys/class/oplus_chg/battery/batt_fcc"
    )
    private val CYCLE_PATHS = arrayOf(
        "/sys/class/power_supply/battery/cycle_count",
        "/sys/class/power_supply/bms/cycle_count",
        "/sys/class/power_supply/battery/battery_cycle"
    )

    fun read(context: Context): BatteryTelemetry {
        val intent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val levelPct = if (level >= 0) ((level * 100f) / scale).roundToInt() else 0

        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusCode == BatteryManager.BATTERY_STATUS_FULL
        val statusLabel = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN"
        }

        val pluggedCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val pluggedLabel = when (pluggedCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            8 -> "Dock"
            else -> "Unplugged"
        }

        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthLabel = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty().ifBlank { "Li-ion" }
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempTenths / 10f
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val isDualCell = voltageMv > 5500
        val perCellVoltageMv = if (isDualCell) voltageMv / 2 else voltageMv

        val rawCurrent = readRawCurrent(bm)
        val normalizedMa = normalizeCurrentMa(rawCurrent.first, statusCode)
        val wattage = ((voltageMv / 1000f) * (abs(normalizedMa) / 1000f) * 100f).roundToInt() / 100f

        val designMah = readNormalizedCapacityMah(DESIGN_CAP_PATHS)
        val fullMah = readNormalizedCapacityMah(FULL_CAP_PATHS)
        val directSoh = readDirectSohPct()
        val healthPct = when {
            designMah != null && fullMah != null && designMah > 0 && fullMah > 0 ->
                ((fullMah.toFloat() / designMah.toFloat()) * 100f).roundToInt().coerceIn(1, 105)
            directSoh != null -> directSoh
            else -> null
        }

        val cycleCount = readCycleCount(intent)
        val chargeCounterMah = readChargeCounterMah(bm)
        val protocolHint = resolveChargingProtocolHint(pluggedLabel, isCharging || pluggedCode != 0, wattage, isDualCell)

        return BatteryTelemetry(
            levelPct = levelPct,
            statusLabel = statusLabel,
            isCharging = isCharging || pluggedCode != 0,
            pluggedLabel = pluggedLabel,
            healthLabel = healthLabel,
            technology = technology,
            tempCelsius = tempCelsius,
            voltageMv = voltageMv,
            currentMa = normalizedMa,
            wattageW = wattage,
            designCapacityMah = designMah,
            fullChargeCapacityMah = fullMah,
            estimatedHealthPct = healthPct,
            cycleCount = cycleCount,
            chargeCounterMah = chargeCounterMah,
            chargingProtocolHint = protocolHint,
            sysfsSourceUsed = rawCurrent.second,
            isDualCellPack = isDualCell,
            perCellVoltageMv = perCellVoltageMv
        )
    }

    private fun readDirectSohPct(): Int? {
        val paths = arrayOf(
            "/sys/class/power_supply/battery/fg_asoc",
            "/sys/class/power_supply/battery/soh",
            "/sys/class/power_supply/bms/soh",
            "/sys/class/oplus_chg/battery/soh"
        )
        for (p in paths) {
            val v = SystemPropReader.readLong(p) ?: continue
            if (v in 1..105) return v.toInt()
        }
        return null
    }

    private fun readRawCurrent(bm: BatteryManager?): Pair<Long, String> {
        val propVal = try {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toLong() ?: Long.MIN_VALUE
        } catch (_: Throwable) {
            Long.MIN_VALUE
        }
        if (propVal != Long.MIN_VALUE && propVal != 0L) {
            return Pair(propVal, "BatteryManager")
        }
        val sysfsPaths = arrayOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/battery/BatteryAverageCurrent",
            "/sys/class/power_supply/battery/fg_battery_current_consumption"
        )
        for (p in sysfsPaths) {
            val v = SystemPropReader.readLong(p)
            if (v != null) return Pair(v, p)
        }
        return Pair(0L, "Unavailable")
    }

    private fun normalizeCurrentMa(raw: Long, statusCode: Int): Int {
        if (raw == 0L || raw == Long.MIN_VALUE) return 0
        val magMa = if (abs(raw) > 25_000L) (abs(raw) / 1000L).toInt() else abs(raw).toInt()
        return when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> magMa
            BatteryManager.BATTERY_STATUS_DISCHARGING -> -magMa
            else -> if (raw < 0) -magMa else magMa
        }
    }

    private fun readNormalizedCapacityMah(paths: Array<String>): Int? {
        for (p in paths) {
            val raw = SystemPropReader.readLong(p) ?: continue
            if (raw <= 0L) continue
            val mah = if (raw > 50_000L) (raw / 1000L).toInt() else raw.toInt()
            if (mah in 500..25_000) return mah
        }
        return null
    }

    private fun readCycleCount(intent: Intent?): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && intent != null) {
            try {
                val c = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                if (c >= 0) return c
            } catch (_: Throwable) {
            }
        }
        for (p in CYCLE_PATHS) {
            val v = SystemPropReader.readLong(p)
            if (v != null && v in 0..20_000) return v.toInt()
        }
        val samsungLevel = SystemPropReader.readLong("/efs/FactoryApp/batt_discharge_level")
        if (samsungLevel != null && samsungLevel >= 0L) {
            return (samsungLevel / 100L).toInt()
        }
        return null
    }

    private fun readChargeCounterMah(bm: BatteryManager?): Int? {
        return try {
            val raw = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: return null
            if (raw <= 0 || raw == Int.MIN_VALUE) null else raw / 1000
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveChargingProtocolHint(
        plugged: String,
        isCharging: Boolean,
        watts: Float,
        isDualCell: Boolean
    ): String {
        val sysType = SystemPropReader.readFirstSysfsLine(
            "/sys/class/power_supply/battery/charge_type",
            "/sys/class/power_supply/usb/real_type",
            "/sys/class/power_supply/usb/type"
        )
        val cellTag = if (isDualCell) " [2S Dual-Cell]" else ""
        if (!isCharging) return "Discharging$cellTag"
        val base = when {
            watts >= 25f -> "High-Wattage Fast (${watts}W)"
            watts >= 12f -> "Fast Charge (${watts}W)"
            watts > 0f -> "Standard Charge (${watts}W)"
            else -> plugged
        }
        return if (sysType.isNotBlank()) "$base [$sysType]$cellTag" else "$base ($plugged)$cellTag"
    }
}
