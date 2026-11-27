package com.sanepo.unitcheck.engine

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaDrm
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.sanepo.unitcheck.model.CpuCoreInfo
import com.sanepo.unitcheck.model.DeviceFingerprint
import com.sanepo.unitcheck.model.ThermalReading
import com.sanepo.unitcheck.util.SystemPropReader
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

object DeviceFingerprintEngine {
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    data class StorageBenchResult(
        val readMbps: Float,
        val writeMbps: Float,
        val note: String
    )

    fun collect(
        context: Context,
        readMbps: Float? = null,
        writeMbps: Float? = null,
        benchNote: String = ""
    ): DeviceFingerprint {
        val brand = Build.BRAND.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val board = Build.BOARD.orEmpty()

        val commercialName = resolveCommercialName(brand, manufacturer, model)
        val cpuInfoText = SystemPropReader.readText("/proc/cpuinfo").orEmpty()
        val parsedCpu = parseAllCpuInfo(cpuInfoText)
        val socInfo = resolveSoc(parsedCpu.firstBlock, parsedCpu.clusters, board, hardware)
        val cpuCores = readCpuCores()
        val thermalZones = readThermalZones()
        val gpu = queryGpuOpenGl()

        val memInfo = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.getMemoryInfo(memInfo)
        val procMem = parseMemInfo(SystemPropReader.readText("/proc/meminfo").orEmpty())
        val ramTotal = if (memInfo.totalMem > 0L) memInfo.totalMem else procMem.first
        val ramAvail = memInfo.availMem
        val zramSize = SystemPropReader.readLong("/sys/block/zram0/disksize") ?: 0L
        val swapTotal = procMem.second

        val storagePair = detectStorageType()
        val dataDir = Environment.getDataDirectory()
        val statFs = StatFs(dataDir.path)
        val storageTotal = statFs.totalBytes
        val storageFree = statFs.availableBytes

        val blStatus = resolveBootloaderStatus()
        val verifiedState = SystemPropReader.firstNonBlank("ro.boot.verifiedbootstate", "ro.boot.veritymode")
            .ifEmpty { "Unknown" }
        val knoxVoid = resolveKnoxBit(manufacturer)
        val knoxGuardState = resolveKnoxGuardState(manufacturer)
        val rootIndicators = detectRootAndTamper()
        val selinux = readSelinuxMode()
        val kernel = SystemPropReader.readFirstLine("/proc/version").orEmpty()
            .ifEmpty { System.getProperty("os.version").orEmpty() }
        val widevine = queryWidevineDrm()
        val mdmAdmins = readActiveDeviceAdmins(context)
        val financePkgs = detectFinanceLockPackages(context, knoxGuardState, mdmAdmins)

        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH
        } else {
            SystemPropReader.get("ro.build.version.security_patch", "N/A")
        }

        val abis = Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty()
        val abiLower = abis.lowercase()
        val cpuArch = parsedCpu.firstBlock["CPU architecture"]?.let { "ARMv$it" }
            ?: when {
                abiLower.contains("arm64") -> "ARM64-v8a"
                abiLower.contains("x86_64") -> "x86_64"
                abiLower.contains("armeabi") -> "ARMv7"
                else -> abis.ifBlank { "Unknown" }
            }

        val specWarnings = detectSpecTampering(
            commercialName = commercialName,
            abiList = abis,
            amTotalRam = memInfo.totalMem,
            procMemTotal = procMem.first
        )

        return DeviceFingerprint(
            commercialName = commercialName,
            brand = brand,
            manufacturer = manufacturer,
            model = model,
            deviceCodename = device,
            hardware = hardware,
            board = board,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = securityPatch.ifBlank { "N/A" },
            buildDisplay = Build.DISPLAY.orEmpty(),
            buildTags = Build.TAGS.orEmpty(),
            abiList = abis,
            socBrand = socInfo.first,
            socModel = socInfo.second,
            cpuArchitecture = cpuArch,
            cpuImplementerSummary = socInfo.third,
            cpuCoreClusters = parsedCpu.clusters,
            cpuCores = cpuCores,
            thermalZones = thermalZones,
            gpuVendor = gpu.first,
            gpuRenderer = gpu.second,
            gpuVersion = gpu.third,
            ramTotalBytes = ramTotal,
            ramAvailBytes = ramAvail,
            zramBytes = zramSize,
            swapBytes = swapTotal,
            storageType = storagePair.first,
            storageDeviceName = storagePair.second,
            storageTotalBytes = storageTotal,
            storageFreeBytes = storageFree,
            storageReadMbps = readMbps,
            storageWriteMbps = writeMbps,
            storageBenchNote = benchNote,
            bootloaderLocked = blStatus,
            verifiedBootState = verifiedState,
            knoxWarrantyVoid = knoxVoid,
            knoxGuardState = knoxGuardState,
            rootIndicators = rootIndicators,
            selinuxMode = selinux,
            kernelVersion = kernel.take(120),
            widevineLevel = widevine.first,
            widevineSystemId = widevine.second,
            mdmAdmins = mdmAdmins,
            financePackagesFound = financePkgs,
            specTamperWarnings = specWarnings
        )
    }

    private fun resolveCommercialName(brand: String, manufacturer: String, model: String): String {
        val marketProp = SystemPropReader.firstNonBlank(
            "ro.product.marketname",
            "ro.product.vendor.marketname",
            "ro.product.odm.marketname",
            "ro.vendor.oplus.market.name",
            "ro.oppo.market.name",
            "ro.vivo.market.name",
            "ro.config.marketing_name",
            "ro.transsion.product.name",
            "ro.semc.product.name",
            "ro.product.model.for.market"
        )
        if (marketProp.isNotBlank()) {
            return if (marketProp.contains(brand, ignoreCase = true) ||
                marketProp.contains(manufacturer, ignoreCase = true)
            ) {
                marketProp
            } else {
                "$brand $marketProp".trim()
            }
        }
        val cleanBrand = brand.ifBlank { manufacturer }
        return if (model.startsWith(cleanBrand, ignoreCase = true)) model else "$cleanBrand $model".trim()
    }

    private data class ParsedCpuInfo(
        val firstBlock: Map<String, String>,
        val clusters: List<String>
    )

    private fun parseAllCpuInfo(raw: String): ParsedCpuInfo {
        val firstBlock = LinkedHashMap<String, String>()
        val coreEntries = ArrayList<MutableMap<String, String>>()
        var currentCore = LinkedHashMap<String, String>()

        for (line in raw.lineSequence()) {
            if (line.isBlank()) {
                if (currentCore.isNotEmpty()) {
                    coreEntries.add(currentCore)
                    currentCore = LinkedHashMap()
                }
                continue
            }
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val valStr = line.substring(idx + 1).trim()
            if (valStr.isNotEmpty()) {
                if (!firstBlock.containsKey(key)) firstBlock[key] = valStr
                currentCore[key] = valStr
            }
        }
        if (currentCore.isNotEmpty()) coreEntries.add(currentCore)

        val clusterCounts = LinkedHashMap<String, Int>()
        for (entry in coreEntries) {
            val imp = entry["CPU implementer"].orEmpty()
            val pt = entry["CPU part"].orEmpty()
            if (imp.isEmpty() && pt.isEmpty()) continue
            val mapped = mapArmPart(imp, pt)
            val label = if (mapped.isNotEmpty()) "$mapped ($imp/$pt)" else "$imp/$pt"
            clusterCounts[label] = (clusterCounts[label] ?: 0) + 1
        }
        val clusters = clusterCounts.map { (name, count) -> "${count}x $name" }
        return ParsedCpuInfo(firstBlock, clusters)
    }

    private fun resolveSoc(
        cpuMap: Map<String, String>,
        clusters: List<String>,
        board: String,
        hardware: String
    ): Triple<String, String, String> {
        var socManufacturer = ""
        var socModel = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            socManufacturer = Build.SOC_MANUFACTURER.takeUnless { it == Build.UNKNOWN }.orEmpty()
            socModel = Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }.orEmpty()
        }

        val platform = SystemPropReader.firstNonBlank(
            "ro.board.platform",
            "ro.mediatek.platform",
            "ro.hardware.chipname",
            "ro.chipname",
            "ro.boot.hardware"
        )
        val cpuHw = cpuMap["Hardware"].orEmpty()
        val rawCombined = listOf(socManufacturer, socModel, platform, cpuHw, board, hardware)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

        val brand = when {
            socManufacturer.isNotBlank() -> socManufacturer
            rawCombined.contains("qcom") || rawCombined.contains("qualcomm") ||
                rawCombined.contains("snapdragon") || rawCombined.matches(Regex(".*\\b(sm|sdm|msm|apq)\\d+.*")) ->
                "Qualcomm Snapdragon"
            rawCombined.contains("mt6") || rawCombined.contains("mt8") || rawCombined.contains("mediatek") ||
                rawCombined.contains("dimensity") || rawCombined.contains("helio") ->
                "MediaTek"
            rawCombined.contains("exynos") || rawCombined.contains("s5e") || rawCombined.contains("universal") ->
                "Samsung Exynos"
            rawCombined.contains("unisoc") || rawCombined.contains("ums") || rawCombined.contains("sc98") ||
                rawCombined.contains("sprd") ->
                "Unisoc / Spreadtrum"
            rawCombined.contains("kirin") || rawCombined.contains("hi3") || rawCombined.contains("hisilicon") ->
                "HiSilicon Kirin"
            rawCombined.contains("tensor") || rawCombined.contains("gs101") || rawCombined.contains("gs201") ||
                rawCombined.contains("zuma") ->
                "Google Tensor"
            else -> hardware.ifBlank { "Unknown SoC" }
        }

        val modelLabel = listOf(socModel, cpuHw, platform)
            .firstOrNull { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: board.ifBlank { hardware }

        val implSummary = if (clusters.isNotEmpty()) {
            clusters.joinToString(" + ")
        } else {
            cpuMap["Processor"].orEmpty().ifEmpty { hardware }
        }

        return Triple(brand, modelLabel, implSummary)
    }

    private fun mapArmPart(implementer: String, part: String): String {
        val imp = implementer.lowercase()
        val pt = part.lowercase()
        return when {
            imp == "0x41" && pt == "0xd03" -> "ARM Cortex-A53"
            imp == "0x41" && pt == "0xd05" -> "ARM Cortex-A55"
            imp == "0x41" && pt == "0xd07" -> "ARM Cortex-A57"
            imp == "0x41" && pt == "0xd08" -> "ARM Cortex-A72"
            imp == "0x41" && pt == "0xd09" -> "ARM Cortex-A73"
            imp == "0x41" && pt == "0xd0a" -> "ARM Cortex-A75"
            imp == "0x41" && pt == "0xd0b" -> "ARM Cortex-A76"
            imp == "0x41" && pt == "0xd0d" -> "ARM Cortex-A77"
            imp == "0x41" && pt == "0xd41" -> "ARM Cortex-A78"
            imp == "0x41" && pt == "0xd44" -> "ARM Cortex-X1"
            imp == "0x41" && pt == "0xd46" -> "ARM Cortex-A510"
            imp == "0x41" && pt == "0xd47" -> "ARM Cortex-A710"
            imp == "0x41" && pt == "0xd48" -> "ARM Cortex-X2"
            imp == "0x41" && pt == "0xd4d" -> "ARM Cortex-A715"
            imp == "0x41" && pt == "0xd4e" -> "ARM Cortex-X3"
            imp == "0x41" && pt == "0xd80" -> "ARM Cortex-A520"
            imp == "0x41" && pt == "0xd81" -> "ARM Cortex-A720"
            imp == "0x41" && pt == "0xd82" -> "ARM Cortex-X4"
            imp == "0x41" && pt == "0xd85" -> "ARM Cortex-X925"
            imp == "0x51" -> "Qualcomm Kryo"
            imp == "0x53" -> "Samsung Mongoose"
            else -> ""
        }
    }

    private fun readCpuCores(): List<CpuCoreInfo> {
        val list = ArrayList<CpuCoreInfo>()
        for (i in 0 until 16) {
            val base = "/sys/devices/system/cpu/cpu$i"
            if (!File(base).exists()) break
            val minKhz = SystemPropReader.readLong("$base/cpufreq/cpuinfo_min_freq") ?: 0L
            val curKhz = SystemPropReader.readLong("$base/cpufreq/scaling_cur_freq") ?: 0L
            val maxKhz = SystemPropReader.readLong("$base/cpufreq/cpuinfo_max_freq") ?: 0L
            val gov = SystemPropReader.readFirstLine("$base/cpufreq/scaling_governor").orEmpty()
                .ifEmpty { "offline/restricted" }
            list.add(CpuCoreInfo(i, minKhz, curKhz, maxKhz, gov))
        }
        return list
    }

    private fun readThermalZones(): List<ThermalReading> {
        val readings = ArrayList<ThermalReading>()
        for (i in 0 until 40) {
            val base = "/sys/class/thermal/thermal_zone$i"
            if (!File(base).exists()) continue
            val type = SystemPropReader.readFirstLine("$base/type").orEmpty()
            val rawTemp = SystemPropReader.readLong("$base/temp") ?: continue
            val celsius = if (rawTemp > 1000L || rawTemp < -1000L) rawTemp / 1000f else rawTemp.toFloat()
            if (celsius in 5f..115f) {
                readings.add(ThermalReading("zone$i", type.ifEmpty { "sensor$i" }, celsius))
            }
            if (readings.size >= 12) break
        }
        return readings
    }

    private fun queryGpuOpenGl(): Triple<String, String, String> {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return Triple("Unknown", "Unknown", "Unknown")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return Triple("Unknown", "Unknown", "Unknown")
            }
            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                return Triple("Unknown", "Unknown", "Unknown")
            }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return Triple("Unknown", "Unknown", "Unknown")
            }
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty().ifBlank { "Unknown" }
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty().ifBlank { "Unknown" }
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty().ifBlank { "OpenGL ES" }
            Triple(vendor, renderer, glVersion)
        } catch (_: Throwable) {
            Triple("Unknown", "Unknown", "Unknown")
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun parseMemInfo(raw: String): Pair<Long, Long> {
        var memTotalKb = 0L
        var swapTotalKb = 0L
        for (line in raw.lineSequence()) {
            if (line.startsWith("MemTotal:")) {
                memTotalKb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            } else if (line.startsWith("SwapTotal:")) {
                swapTotalKb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            }
        }
        return Pair(memTotalKb * 1024L, swapTotalKb * 1024L)
    }

    private fun detectStorageType(): Pair<String, String> {
        val bootDev = SystemPropReader.firstNonBlank("ro.boot.bootdevice", "ro.boot.boot_devices")
        val ufsInquiry = SystemPropReader.readFirstSysfsLine(
            "/sys/block/sda/device/inquiry",
            "/sys/block/sda/device/model",
            "/sys/class/block/sda/device/model"
        )
        val ufsVendor = SystemPropReader.readFirstLine("/sys/block/sda/device/vendor").orEmpty()
        val mmcName = SystemPropReader.readFirstSysfsLine(
            "/sys/class/block/mmcblk0/device/name",
            "/sys/block/mmcblk0/device/name"
        )
        val mmcType = SystemPropReader.readFirstLine("/sys/class/block/mmcblk0/device/type").orEmpty()

        val ufsPresent = File("/sys/block/sda").exists() || bootDev.contains("ufs", ignoreCase = true)
        val emmcPresent = File("/sys/block/mmcblk0").exists() || bootDev.contains("mmc", ignoreCase = true)

        return when {
            ufsPresent && (ufsInquiry.isNotEmpty() || ufsVendor.isNotEmpty() || bootDev.contains("ufs", ignoreCase = true)) -> {
                val devName = "$ufsVendor $ufsInquiry".trim().ifEmpty { bootDev.ifEmpty { "UFS Flash" } }
                Pair("UFS", devName)
            }
            emmcPresent && mmcName.isNotEmpty() -> {
                Pair(if (mmcType.isNotEmpty()) mmcType else "eMMC", mmcName)
            }
            bootDev.contains("nvme", ignoreCase = true) -> Pair("NVMe", bootDev)
            bootDev.isNotEmpty() -> Pair("Flash", bootDev)
            else -> Pair("Internal Flash", Build.HARDWARE.ifBlank { "Android Storage" })
        }
    }

    fun runStorageBenchmark(context: Context): StorageBenchResult {
        val tempFile = File(context.cacheDir, "sanepo_bench_${System.currentTimeMillis()}.bin")
        val writeBlock = ByteArray(256 * 1024) { (it and 0xFF).toByte() }
        val writeTotalBytes = 8 * 1024 * 1024

        return try {
            // Use "rwd" (O_DSYNC) so writes flush directly to flash storage without RAM write-back skew.
            val writeStart = System.nanoTime()
            RandomAccessFile(tempFile, "rwd").use { raf ->
                var written = 0
                while (written < writeTotalBytes) {
                    raf.write(writeBlock)
                    written += writeBlock.size
                }
            }
            val writeSec = ((System.nanoTime() - writeStart) / 1_000_000_000.0).coerceAtLeast(0.01)
            val writeMbps = ((writeTotalBytes / (1024.0 * 1024.0)) / writeSec).toFloat()

            // Measure cold read from untouched read-only system/framework files to avoid reading our own page cache.
            val coldRead = measureColdSystemRead(writeBlock)
            val (readMbps, note) = if (coldRead != null) {
                Pair(coldRead, "Direct Sync Write (rwd) + Cold System Read")
            } else {
                val readStart = System.nanoTime()
                var sink = 0
                FileInputStream(tempFile).use { fis ->
                    while (true) {
                        val n = fis.read(writeBlock)
                        if (n <= 0) break
                        sink = sink xor writeBlock[0].toInt()
                    }
                }
                val readSec = ((System.nanoTime() - readStart) / 1_000_000_000.0).coerceAtLeast(0.005)
                val mbps = if (sink == Int.MIN_VALUE) 0f else ((writeTotalBytes / (1024.0 * 1024.0)) / readSec).toFloat()
                Pair(mbps, "Direct Sync Write (rwd) + App Cache Read")
            }

            StorageBenchResult(
                readMbps = (readMbps * 10f).roundToInt() / 10f,
                writeMbps = (writeMbps * 10f).roundToInt() / 10f,
                note = note
            )
        } catch (_: Throwable) {
            StorageBenchResult(0f, 0f, "Benchmark failed")
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun measureColdSystemRead(buffer: ByteArray): Float? {
        val candidates = ArrayList<File>()
        val dirs = arrayOf("/system/framework", "/system/lib64", "/system/lib", "/system/priv-app")
        for (d in dirs) {
            val dirFile = File(d)
            if (!dirFile.exists() || !dirFile.canRead()) continue
            val files = dirFile.listFiles { f -> f.isFile && f.canRead() && f.length() >= 256 * 1024 } ?: continue
            for (f in files) {
                candidates.add(f)
                if (candidates.size >= 16) break
            }
            if (candidates.size >= 16) break
        }
        if (candidates.isEmpty()) return null

        var bytesRead = 0L
        var sink = 0
        val start = System.nanoTime()
        for (f in candidates) {
            try {
                FileInputStream(f).use { fis ->
                    while (bytesRead < 16L * 1024L * 1024L) {
                        val n = fis.read(buffer)
                        if (n <= 0) break
                        bytesRead += n
                        sink = sink xor buffer[0].toInt()
                    }
                }
            } catch (_: Throwable) {
            }
            if (bytesRead >= 16L * 1024L * 1024L) break
        }
        if (bytesRead < 2L * 1024L * 1024L || sink == Int.MIN_VALUE) return null
        val sec = ((System.nanoTime() - start) / 1_000_000_000.0).coerceAtLeast(0.01)
        return ((bytesRead / (1024.0 * 1024.0)) / sec).toFloat()
    }

    private fun resolveBootloaderStatus(): String {
        val flashLocked = SystemPropReader.get("ro.boot.flash.locked")
        val secureLock = SystemPropReader.get("ro.secureboot.lockstate")
        val verified = SystemPropReader.get("ro.boot.verifiedbootstate")
        return when {
            flashLocked == "1" || secureLock.equals("locked", ignoreCase = true) -> "LOCKED"
            flashLocked == "0" || secureLock.equals("unlocked", ignoreCase = true) ||
                verified.equals("orange", ignoreCase = true) -> "UNLOCKED"
            verified.equals("green", ignoreCase = true) -> "LOCKED (Verified Green)"
            verified.equals("yellow", ignoreCase = true) -> "CUSTOM KEY (Yellow)"
            verified.equals("red", ignoreCase = true) -> "CORRUPTED / RED"
            else -> "Unknown"
        }
    }

    private fun resolveKnoxBit(manufacturer: String): String {
        val bit = SystemPropReader.firstNonBlank("ro.boot.warranty_bit", "ro.boot.knox_warranty_bit", "ro.warranty_bit")
        return when {
            bit == "0" -> "0x0 (Intact)"
            bit == "1" -> "0x1 (TRIPPED)"
            manufacturer.contains("samsung", ignoreCase = true) -> "N/A (Restricted)"
            else -> "Not Applicable"
        }
    }

    private fun resolveKnoxGuardState(manufacturer: String): String {
        val kg = SystemPropReader.firstNonBlank("knox.kg.state", "ro.knox.kg.state", "ro.boot.kg.bit")
        if (kg.isNotBlank()) return kg
        return if (manufacturer.contains("samsung", ignoreCase = true)) "Not Exposed" else "Not Applicable"
    }

    private fun detectFinanceLockPackages(
        context: Context,
        knoxGuardState: String,
        mdmAdmins: List<String>
    ): List<String> {
        val found = ArrayList<String>()
        val pm = context.packageManager
        val loanPackages = listOf(
            "com.payjoy.access" to "PayJoy Finance Lock",
            "com.payjoy.status" to "PayJoy Status",
            "com.novo.knox.lock" to "Knox Finance Lock",
            "com.homecredit.finance" to "HomeCredit Lock",
            "com.kredivo.paylater" to "Kredivo Device Lock",
            "com.trustonic.telecoms.standard.dlc" to "Trustonic DLC Lock",
            "com.trustonic.telecoms.client.standard.dlc.playground" to "Trustonic DLC",
            "com.google.android.apps.work.clouddpc" to "Enterprise CloudDPC"
        )
        for ((pkg, label) in loanPackages) {
            if (isPackageInstalled(pm, pkg)) {
                found.add("$label ($pkg)")
            }
        }

        // Samsung Knox Guard client exists on stock ROMs; only flag if KG state is non-clean or active admin.
        val kgInstalled = isPackageInstalled(pm, "com.samsung.android.kgclient")
        val kgAbnormal = knoxGuardState.contains("lock", ignoreCase = true) ||
            knoxGuardState.contains("active", ignoreCase = true) ||
            knoxGuardState.contains("prenormal", ignoreCase = true) ||
            knoxGuardState.contains("checking", ignoreCase = true) ||
            mdmAdmins.any { it.contains("kgclient", ignoreCase = true) }
        if (kgInstalled && kgAbnormal) {
            found.add("Samsung Knox Guard Active ($knoxGuardState)")
        }
        return found
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun detectSpecTampering(
        commercialName: String,
        abiList: String,
        amTotalRam: Long,
        procMemTotal: Long
    ): List<String> {
        val warnings = ArrayList<String>()
        val expectedAndroidMajor = when (Build.VERSION.SDK_INT) {
            23 -> 6
            24, 25 -> 7
            26, 27 -> 8
            28 -> 9
            29 -> 10
            30 -> 11
            31, 32 -> 12
            33 -> 13
            34 -> 14
            35 -> 15
            36 -> 16
            37 -> 17
            else -> null
        }
        val claimedMajor = Build.VERSION.RELEASE.takeWhile { it.isDigit() }.toIntOrNull()
        if (expectedAndroidMajor != null && claimedMajor != null && abs(claimedMajor - expectedAndroidMajor) >= 1) {
            warnings.add("OS SPOOF: Claims Android ${Build.VERSION.RELEASE} on API ${Build.VERSION.SDK_INT} (Expected Android $expectedAndroidMajor)")
        }

        val has64Bit = abiList.contains("64", ignoreCase = true)
        if (!has64Bit && amTotalRam > 3_500_000_000L) {
            warnings.add("FAKE SPEC RISK: 32-bit ABI ($abiList) claiming >3.5GB RAM")
        }
        if (!has64Bit && (commercialName.contains("ultra", ignoreCase = true) ||
                commercialName.contains("pro max", ignoreCase = true))
        ) {
            warnings.add("SUSPECTED REPLICA: 32-bit ABI on flagship name ($commercialName)")
        }

        if (amTotalRam > 0L && procMemTotal > 0L) {
            val diffRatio = abs(amTotalRam - procMemTotal).toDouble() / procMemTotal.toDouble()
            if (diffRatio > 0.30) {
                warnings.add("RAM MISMATCH: ActivityManager vs /proc/meminfo differs ${(diffRatio * 100).toInt()}%")
            }
        }
        return warnings
    }

    private fun detectRootAndTamper(): List<String> {
        val findings = ArrayList<String>()
        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/su/bin/su", "/system/app/Superuser.apk", "/sbin/magisk"
        )
        for (p in suPaths) {
            try {
                if (File(p).exists()) findings.add("Binary found: $p")
            } catch (_: Throwable) {
            }
        }
        val tags = Build.TAGS.orEmpty()
        if (tags.contains("test-keys", ignoreCase = true)) {
            findings.add("Build tags: test-keys")
        }
        val debuggable = SystemPropReader.get("ro.debuggable")
        if (debuggable == "1") {
            findings.add("ro.debuggable=1")
        }
        val mounts = SystemPropReader.readText("/proc/mounts").orEmpty()
        if (mounts.contains("magisk", ignoreCase = true) || mounts.contains("kernelsu", ignoreCase = true)) {
            findings.add("Mount trace: Magisk/KernelSU")
        }
        return findings
    }

    private fun readSelinuxMode(): String {
        val enforce = SystemPropReader.readFirstLine("/sys/fs/selinux/enforce")
        if (enforce == "1") return "Enforcing"
        if (enforce == "0") return "Permissive"
        val cmd = SystemPropReader.execLine(arrayOf("getenforce"))
        if (cmd.equals("Enforcing", ignoreCase = true) || cmd.equals("Permissive", ignoreCase = true)) {
            return cmd
        }
        return "Restricted (Non-Root)"
    }

    private fun queryWidevineDrm(): Pair<String, String> {
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE_UUID)
            val level = drm.getPropertyString("securityLevel").ifBlank { "Unknown" }
            val sysId = try {
                drm.getPropertyString("systemId").ifBlank { "N/A" }
            } catch (_: Throwable) {
                "N/A"
            }
            Pair(level, sysId)
        } catch (_: Throwable) {
            Pair("Unavailable", "N/A")
        } finally {
            try {
                if (drm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        drm.close()
                    } else {
                        @Suppress("DEPRECATION")
                        drm.release()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun readActiveDeviceAdmins(context: Context): List<String> {
        val admins = ArrayList<String>()
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return admins
            val active = dpm.activeAdmins ?: return admins
            for (comp in active) {
                val pkg = comp.packageName
                val role = when {
                    dpm.isDeviceOwnerApp(pkg) -> " [DEVICE OWNER]"
                    dpm.isProfileOwnerApp(pkg) -> " [PROFILE OWNER]"
                    else -> " [ADMIN]"
                }
                admins.add(pkg + role)
            }
        } catch (_: Throwable) {
        }
        return admins
    }
}
