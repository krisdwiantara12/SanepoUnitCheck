package com.sanepo.unitcheck

import com.sanepo.unitcheck.engine.ReportExporter
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.PriceCalculatorState
import com.sanepo.unitcheck.model.TestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticStoreTest {

    @Test
    fun resetAndUpdateItemMaintainsCountsAndReportText() {
        DiagnosticStore.resetItems()
        val initial = DiagnosticStore.getItems()
        assertEquals(11, initial.size)
        assertTrue(initial.all { it.status == TestStatus.READY })

        DiagnosticStore.updateItem("display_pixels", TestStatus.PASS, "1080x2400 @ 120Hz")
        DiagnosticStore.updateItem("touch_digitizer", TestStatus.FAIL, "Ghost touch detected")

        val updated = DiagnosticStore.getItems()
        assertEquals(1, updated.count { it.status == TestStatus.PASS })
        assertEquals(1, updated.count { it.status == TestStatus.FAIL })

        val report = ReportExporter.buildTextReport()
        assertTrue(report.contains("[PASS] Display: Dead Pixel, Burn-In & Real Refresh Rate"))
        assertTrue(report.contains("[FAIL] Touchscreen: Full Grid, Multi-Touch & Ghost Touch"))
    }

    @Test
    fun codPriceCalculatorComputesAndClampsAtZero() {
        val good = PriceCalculatorState(
            marketPriceIdr = 3_000_000L,
            targetProfitIdr = 250_000L,
            defectDeductionIdr = 450_000L
        )
        assertEquals(2_300_000L, good.maxSafeBuyPriceIdr)

        val underwater = PriceCalculatorState(
            marketPriceIdr = 500_000L,
            targetProfitIdr = 250_000L,
            defectDeductionIdr = 500_000L
        )
        assertEquals(0L, underwater.maxSafeBuyPriceIdr)
        assertEquals("2.300.000", ReportExporter.formatIdr(good.maxSafeBuyPriceIdr))
    }
}
