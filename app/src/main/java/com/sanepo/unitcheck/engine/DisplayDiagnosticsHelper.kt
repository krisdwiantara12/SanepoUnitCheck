package com.sanepo.unitcheck.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.sanepo.unitcheck.model.DisplaySpecSummary
import kotlin.math.hypot
import kotlin.math.roundToInt

object DisplayDiagnosticsHelper {

    fun readSpecs(context: Context): DisplaySpecSummary {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)

        var widthPx = metrics.widthPixels
        var heightPx = metrics.heightPixels
        var activeHz = display?.refreshRate ?: 60f
        val supportedRates = ArrayList<Float>()

        if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = display.mode
            if (mode != null) {
                widthPx = mode.physicalWidth
                heightPx = mode.physicalHeight
                activeHz = mode.refreshRate
            }
            val modes = display.supportedModes
            for (m in modes) {
                val rounded = (m.refreshRate * 10f).roundToInt() / 10f
                if (!supportedRates.contains(rounded)) {
                    supportedRates.add(rounded)
                }
            }
            supportedRates.sort()
        } else {
            supportedRates.add(activeHz)
        }

        val xInches = if (metrics.xdpi > 0f) widthPx / metrics.xdpi else 0f
        val yInches = if (metrics.ydpi > 0f) heightPx / metrics.ydpi else 0f
        val diag = ((hypot(xInches.toDouble(), yInches.toDouble())) * 100.0).roundToInt() / 100f

        val hdrList = ArrayList<String>()
        if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                @Suppress("DEPRECATION")
                val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
                for (t in hdrTypes) {
                    val label = when (t) {
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                        else -> "HDR($t)"
                    }
                    if (!hdrList.contains(label)) hdrList.add(label)
                }
            } catch (_: Throwable) {
            }
        }

        val wideGamut = if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                display.isWideColorGamut
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        return DisplaySpecSummary(
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = metrics.densityDpi,
            approxInches = diag,
            activeRefreshHz = (activeHz * 10f).roundToInt() / 10f,
            supportedRefreshRatesHz = supportedRates,
            hdrCapabilities = hdrList,
            wideColorGamut = wideGamut
        )
    }
}
