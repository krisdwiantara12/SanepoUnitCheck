package com.sanepo.unitcheck.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    private fun reportDir(context: Context): File {
        val dir = File(context.cacheDir, "reports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun safeElapsedRealtimeMs(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            0L
        }
    }

    fun computeVerifyHash(dateStr: String): String {
        val fp = DiagnosticStore.fingerprint
        val items = DiagnosticStore.getItems()
        val uptimeSec = safeElapsedRealtimeMs() / 1000L
        val payload = buildString {
            append(dateStr).append('|')
            append(uptimeSec).append('|')
            append(fp?.model.orEmpty()).append('|')
            append(fp?.deviceCodename.orEmpty()).append('|')
            append(DiagnosticStore.unitImeiNote).append('|')
            for (item in items) {
                append(item.id).append(':').append(item.status.code).append(';')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02X".format(it) }
    }

    fun buildCompactQrPayload(): String {
        val fp = DiagnosticStore.fingerprint
        val bat = DiagnosticStore.batteryTelemetry
        val rf = DiagnosticStore.connectivitySnapshot
        val items = DiagnosticStore.getItems()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val hash = computeVerifyHash(dateStr)

        return buildString {
            appendLine("SANEPO UNIT CHECK #$hash")
            appendLine("Time: $dateStr")
            if (fp != null) {
                appendLine("Unit: ${fp.commercialName} (${fp.deviceCodename})")
                appendLine("OS: ${fp.androidVersion} | BL=${fp.bootloaderLocked} | DRM=${fp.widevineLevel}")
                appendLine("Root=${fp.rootIndicators.size} | MDM=${fp.mdmAdmins.size} | KG=${fp.knoxGuardState}")
            }
            if (bat != null) {
                val h = bat.estimatedHealthPct?.let { "${it}%" } ?: bat.healthLabel
                appendLine("Bat: ${bat.levelPct}% ${bat.currentMa}mA ${bat.voltageMv}mV H=$h")
            }
            if (rf != null) {
                appendLine("RF: ${rf.serviceStateLabel} | SIM1=${rf.sim1State} | BB=${rf.basebandVersion.take(16)}")
            }
            val p = DiagnosticStore.priceState
            if (p.marketPriceIdr > 0L) {
                appendLine("MaxBuy: Rp${formatIdr(p.maxSafeBuyPriceIdr)}")
            }
            append(items.joinToString(" ") { "${it.id.take(5)}=${it.status.code}" })
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    fun buildTextReport(): String {
        val fp = DiagnosticStore.fingerprint
        val disp = DiagnosticStore.displaySpecs
        val bat = DiagnosticStore.batteryTelemetry
        val stress = DiagnosticStore.batteryStressSummary
        val rf = DiagnosticStore.connectivitySnapshot
        val items = DiagnosticStore.getItems()

        val passCount = items.count { it.status == TestStatus.PASS }
        val warnCount = items.count { it.status == TestStatus.WARN }
        val failCount = items.count { it.status == TestStatus.FAIL }
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val uptimeMin = safeElapsedRealtimeMs() / 60000L
        val verifyHash = computeVerifyHash(dateStr)

        val sb = StringBuilder()
        sb.appendLine("SANEPO UNIT CHECK - DIAGNOSTIC REPORT")
        sb.appendLine("Generated: $dateStr | Uptime: ${uptimeMin}m | Verify: #$verifyHash")
        if (DiagnosticStore.sellerInfo.isNotBlank()) {
            sb.appendLine("Seller/Unit Ref: ${DiagnosticStore.sellerInfo}")
        }
        if (DiagnosticStore.unitImeiNote.isNotBlank()) {
            sb.appendLine("IMEI / SN: ${DiagnosticStore.unitImeiNote}")
        }
        sb.appendLine("Summary: PASS=$passCount | WARN=$warnCount | FAIL=$failCount | TOTAL=${items.size}")
        sb.appendLine("----------------------------------------")

        if (fp != null) {
            sb.appendLine("DEVICE IDENTITY")
            sb.appendLine("Commercial: ${fp.commercialName}")
            sb.appendLine("Model/Codename: ${fp.model} (${fp.deviceCodename})")
            sb.appendLine("OS / Patch: Android ${fp.androidVersion} | ${fp.securityPatch}")
            sb.appendLine("SoC / GPU: ${fp.socBrand} ${fp.socModel} | ${fp.gpuRenderer}")
            if (fp.cpuCoreClusters.isNotEmpty()) {
                sb.appendLine("Clusters: ${fp.cpuCoreClusters.joinToString(" + ")}")
            }
            sb.appendLine("RAM: ${formatBytes(fp.ramTotalBytes)} | Storage (${fp.storageType}): ${formatBytes(fp.storageTotalBytes)}")
            if (fp.storageReadMbps != null || fp.storageWriteMbps != null) {
                sb.appendLine("Flash Bench: Read=${fp.storageReadMbps ?: 0f} MB/s | Write=${fp.storageWriteMbps ?: 0f} MB/s (${fp.storageBenchNote})")
            }
            sb.appendLine("Bootloader: ${fp.bootloaderLocked} | Verified: ${fp.verifiedBootState}")
            sb.appendLine("Knox: ${fp.knoxWarrantyVoid} | KnoxGuard: ${fp.knoxGuardState}")
            sb.appendLine("SELinux: ${fp.selinuxMode} | Widevine: ${fp.widevineLevel}")
            sb.appendLine("Root Traces: ${if (fp.rootIndicators.isEmpty()) "Clean" else fp.rootIndicators.joinToString("; ")}")
            sb.appendLine("MDM/Finance: ${if (fp.mdmAdmins.isEmpty() && fp.financePackagesFound.isEmpty()) "None" else (fp.mdmAdmins + fp.financePackagesFound).joinToString("; ")}")
            if (fp.specTamperWarnings.isNotEmpty()) {
                sb.appendLine("Spec Warnings: ${fp.specTamperWarnings.joinToString("; ")}")
            }
            sb.appendLine("----------------------------------------")
        }

        if (disp != null) {
            sb.appendLine("DISPLAY: ${disp.widthPx}x${disp.heightPx} (${disp.densityDpi} dpi) @ ${disp.activeRefreshHz}Hz")
        }
        if (bat != null) {
            val cap = bat.fullChargeCapacityMah?.let { "${it}mAh" } ?: "N/A"
            val health = bat.estimatedHealthPct?.let { "${it}%" } ?: bat.healthLabel
            sb.appendLine("BATTERY: ${bat.levelPct}% | ${bat.voltageMv}mV | ${bat.currentMa}mA | ${bat.tempCelsius}C | Health=$health ($cap)")
        }
        if (stress != null) {
            sb.appendLine("BATTERY STRESS (${stress.durationSec}s): Drop=${stress.voltageDropMv}mV (Min=${stress.minVoltageMv}mV), Pct ${stress.startLevelPct}%->${stress.endLevelPct}%, MaxTemp=${stress.maxTempCelsius}C [${if (stress.passed) "PASS" else "FAIL"}]")
        }
        if (rf != null) {
            sb.appendLine("RF: Baseband=${rf.basebandVersion} | State=${rf.serviceStateLabel} | SIM1=${rf.sim1State} | SIM2=${rf.sim2State} | NFC=${rf.nfcStatus}")
        }
        val p = DiagnosticStore.priceState
        if (p.marketPriceIdr > 0L) {
            sb.appendLine("COD PRICING: Market=Rp${formatIdr(p.marketPriceIdr)} | TargetProfit=Rp${formatIdr(p.targetProfitIdr)} | Deductions=Rp${formatIdr(p.defectDeductionIdr)} => MAX SAFE BUY=Rp${formatIdr(p.maxSafeBuyPriceIdr)}")
        }
        sb.appendLine("----------------------------------------")
        sb.appendLine("MODULE VERDICTS")
        for (item in items) {
            sb.appendLine("[${item.status.code}] ${item.titleEn}")
            if (item.summary.isNotBlank()) {
                sb.appendLine("   -> ${item.summary}")
            }
        }
        if (DiagnosticStore.inspectorNote.isNotBlank()) {
            sb.appendLine("----------------------------------------")
            sb.appendLine("Technician Note: ${DiagnosticStore.inspectorNote}")
        }
        return sb.toString()
    }

    fun exportJsonFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val file = File(reportDir(context), "sanepo_report_$stamp.json")
        val root = JSONObject()
        root.put("app", "Sanepo Unit Check")
        root.put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        root.put("verifyHash", computeVerifyHash(dateStr))
        root.put("uptimeMinutes", safeElapsedRealtimeMs() / 60000L)
        root.put("sellerRef", DiagnosticStore.sellerInfo)
        root.put("imeiNote", DiagnosticStore.unitImeiNote)
        root.put("inspectorNote", DiagnosticStore.inspectorNote)

        DiagnosticStore.fingerprint?.let { fp ->
            val fpJson = JSONObject()
            fpJson.put("commercialName", fp.commercialName)
            fpJson.put("brand", fp.brand)
            fpJson.put("model", fp.model)
            fpJson.put("device", fp.deviceCodename)
            fpJson.put("android", fp.androidVersion)
            fpJson.put("socBrand", fp.socBrand)
            fpJson.put("socModel", fp.socModel)
            fpJson.put("cpuClusters", JSONArray(fp.cpuCoreClusters))
            fpJson.put("gpuRenderer", fp.gpuRenderer)
            fpJson.put("ramBytes", fp.ramTotalBytes)
            fpJson.put("storageType", fp.storageType)
            fpJson.put("storageBytes", fp.storageTotalBytes)
            fpJson.put("storageReadMbps", fp.storageReadMbps)
            fpJson.put("storageWriteMbps", fp.storageWriteMbps)
            fpJson.put("storageBenchNote", fp.storageBenchNote)
            fpJson.put("bootloader", fp.bootloaderLocked)
            fpJson.put("verifiedBoot", fp.verifiedBootState)
            fpJson.put("knox", fp.knoxWarrantyVoid)
            fpJson.put("knoxGuard", fp.knoxGuardState)
            fpJson.put("widevine", fp.widevineLevel)
            fpJson.put("rootIndicators", JSONArray(fp.rootIndicators))
            fpJson.put("mdmAdmins", JSONArray(fp.mdmAdmins))
            fpJson.put("financePackages", JSONArray(fp.financePackagesFound))
            fpJson.put("specTamperWarnings", JSONArray(fp.specTamperWarnings))
            root.put("fingerprint", fpJson)
        }

        DiagnosticStore.displaySpecs?.let { d ->
            root.put("display", JSONObject().apply {
                put("widthPx", d.widthPx)
                put("heightPx", d.heightPx)
                put("dpi", d.densityDpi)
                put("activeRefreshHz", d.activeRefreshHz)
                put("supportedHz", JSONArray(d.supportedRefreshRatesHz))
                put("hdr", JSONArray(d.hdrCapabilities))
                put("wideColorGamut", d.wideColorGamut)
            })
        }

        DiagnosticStore.batteryTelemetry?.let { b ->
            root.put("battery", JSONObject().apply {
                put("levelPct", b.levelPct)
                put("status", b.statusLabel)
                put("plugged", b.pluggedLabel)
                put("voltageMv", b.voltageMv)
                put("currentMa", b.currentMa)
                put("wattageW", b.wattageW)
                put("tempCelsius", b.tempCelsius)
                put("healthLabel", b.healthLabel)
                put("estimatedHealthPct", b.estimatedHealthPct)
                put("designCapacityMah", b.designCapacityMah)
                put("fullChargeCapacityMah", b.fullChargeCapacityMah)
                put("cycleCount", b.cycleCount)
                put("protocolHint", b.chargingProtocolHint)
            })
        }

        DiagnosticStore.batteryStressSummary?.let { s ->
            root.put("batteryStress", JSONObject().apply {
                put("durationSec", s.durationSec)
                put("startVoltageMv", s.startVoltageMv)
                put("minVoltageMv", s.minVoltageMv)
                put("voltageDropMv", s.voltageDropMv)
                put("startLevelPct", s.startLevelPct)
                put("endLevelPct", s.endLevelPct)
                put("maxTempCelsius", s.maxTempCelsius)
                put("passed", s.passed)
                put("verdictNote", s.verdictNote)
            })
        }

        if (DiagnosticStore.cameraLenses.isNotEmpty()) {
            val camArr = JSONArray()
            for (c in DiagnosticStore.cameraLenses) {
                camArr.put(JSONObject().apply {
                    put("id", c.cameraId)
                    put("facing", c.facingLabel)
                    put("role", c.roleLabel)
                    put("maxResolution", c.maxResolution)
                    put("ois", c.oisSupported)
                    put("flash", c.flashSupported)
                    put("minZoom", c.minZoomRatio)
                    put("maxZoom", c.maxZoomRatio)
                })
            }
            root.put("cameras", camArr)
        }

        DiagnosticStore.connectivitySnapshot?.let { r ->
            root.put("connectivity", JSONObject().apply {
                put("baseband", r.basebandVersion)
                put("simSlots", r.simSlotCount)
                put("sim1State", r.sim1State)
                put("sim2State", r.sim2State)
                put("serviceState", r.serviceStateLabel)
                put("inService", r.isCellularInService)
                put("operator", r.operatorName)
                put("networkType", r.networkType)
                put("signalDbm", r.signalDbm)
                put("wifi5Ghz", r.wifi5GhzSupported)
                put("wifi6Ghz", r.wifi6GhzSupported)
                put("nfcStatus", r.nfcStatus)
                put("gnssVisible", r.gnssVisibleCount)
                put("gnssUsed", r.gnssUsedCount)
            })
        }

        val p = DiagnosticStore.priceState
        root.put("codPricing", JSONObject().apply {
            put("marketPriceIdr", p.marketPriceIdr)
            put("targetProfitIdr", p.targetProfitIdr)
            put("defectDeductionIdr", p.defectDeductionIdr)
            put("maxSafeBuyPriceIdr", p.maxSafeBuyPriceIdr)
            put("note", p.customNote)
        })

        val testsArray = JSONArray()
        for (item in DiagnosticStore.getItems()) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.titleEn)
            obj.put("status", item.status.code)
            obj.put("summary", item.summary)
            obj.put("details", item.details)
            obj.put("timestampMs", item.timestampMs)
            testsArray.put(obj)
        }
        root.put("tests", testsArray)
        file.writeText(root.toString(2))
        return file
    }

    fun exportPdfFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(reportDir(context), "sanepo_report_$stamp.pdf")

        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 15f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val bodyPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        var y = 42f
        val lines = buildTextReport().lines()
        canvas.drawText("SANEPO UNIT CHECK - VERIFIED HARDWARE REPORT", 36f, y, titlePaint)
        y += 22f

        for (line in lines.drop(1)) {
            val wrapped = wrapLine(line, 86)
            for (part in wrapped) {
                if (y > pageHeight - 40f) {
                    doc.finishPage(page)
                    pageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                    page = doc.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }
                canvas.drawText(part, 36f, y, bodyPaint)
                y += 14f
            }
        }
        doc.finishPage(page)

        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun exportCardPngFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(reportDir(context), "sanepo_card_$stamp.png")

        val width = 1080
        val height = 1620
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#0A0C0E"))

        val cardPaint = Paint().apply { color = Color.parseColor("#14181D") }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#323A45")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4F6F8")
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF8C38")
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C2CCD6")
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val passPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4ADE80")
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FBBF24")
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F87171")
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        c.drawRect(36f, 36f, width - 36f, height - 36f, cardPaint)
        c.drawRect(36f, 36f, width - 36f, height - 36f, borderPaint)

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val hash = computeVerifyHash(dateStr)
        var y = 104f
        c.drawText("SANEPO UNIT CHECK", 68f, y, titlePaint)
        y += 38f
        c.drawText("TIMESTAMP: $dateStr | VERIFY: #$hash", 68f, y, accentPaint)
        y += 44f

        val fp = DiagnosticStore.fingerprint
        if (fp != null) {
            c.drawText("UNIT : ${fp.commercialName} [${fp.deviceCodename}]", 68f, y, monoPaint); y += 34f
            c.drawText("OS   : Android ${fp.androidVersion} | ${fp.socBrand} ${fp.socModel}", 68f, y, monoPaint); y += 34f
            c.drawText("MEM  : RAM ${formatBytes(fp.ramTotalBytes)} | ${fp.storageType} ${formatBytes(fp.storageTotalBytes)}", 68f, y, monoPaint); y += 34f
            c.drawText("SEC  : BL=${fp.bootloaderLocked} | DRM=${fp.widevineLevel} | KG=${fp.knoxGuardState}", 68f, y, monoPaint); y += 34f
        }
        if (DiagnosticStore.unitImeiNote.isNotBlank()) {
            c.drawText("IMEI : ${DiagnosticStore.unitImeiNote}", 68f, y, monoPaint); y += 34f
        }
        val p = DiagnosticStore.priceState
        if (p.marketPriceIdr > 0L) {
            c.drawText("MAX SAFE BUY: Rp ${formatIdr(p.maxSafeBuyPriceIdr)}", 68f, y, accentPaint); y += 38f
        }

        y += 14f
        c.drawLine(68f, y, width - 68f, y, borderPaint)
        y += 42f

        for (item in DiagnosticStore.getItems()) {
            val stPaint = when (item.status) {
                TestStatus.PASS -> passPaint
                TestStatus.WARN -> warnPaint
                TestStatus.FAIL -> failPaint
                else -> monoPaint
            }
            c.drawText("[${item.status.code}]", 68f, y, stPaint)
            c.drawText(item.titleEn.take(44), 192f, y, monoPaint)
            y += 34f
            if (item.summary.isNotBlank()) {
                c.drawText("  ${item.summary.take(64)}", 192f, y, monoPaint)
                y += 30f
            }
            if (y > height - 340f) break
        }

        val qrBmp = generateQrBitmap(buildCompactQrPayload(), 260)
        if (qrBmp != null) {
            c.drawBitmap(qrBmp, width - 310f, height - 310f, null)
            c.drawText("SCAN QR VERIFY", width - 310f, height - 324f, accentPaint)
        }

        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        bmp.recycle()
        return file
    }

    private fun wrapLine(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        var idx = 0
        while (idx < text.length) {
            val end = (idx + maxChars).coerceAtMost(text.length)
            out.add(text.substring(idx, end))
            idx = end
        }
        return out
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.0f MB", mb)
    }

    fun formatIdr(amount: Long): String {
        val nf = NumberFormat.getIntegerInstance(Locale("in", "ID"))
        return nf.format(amount)
    }
}
