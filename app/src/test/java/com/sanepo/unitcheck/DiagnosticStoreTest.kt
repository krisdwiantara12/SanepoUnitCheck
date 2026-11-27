package com.sanepo.unitcheck

import com.sanepo.unitcheck.engine.AppUpdateManager
import com.sanepo.unitcheck.engine.OemDeviceHelper
import com.sanepo.unitcheck.engine.ReportExporter
import com.sanepo.unitcheck.model.DiagnosticStore
import com.sanepo.unitcheck.model.TestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val reportEn = ReportExporter.buildTextReport(useIndonesian = false)
        assertTrue(reportEn.contains("[PASS] Display: Dead Pixel, Burn-In & Real Refresh Rate"))
        assertTrue(reportEn.contains("[FAIL] Touchscreen, Sampling Rate & Replacement Panel Check"))
        assertFalse(reportEn.contains("MAX SAFE BUY"))
    }

    @Test
    fun imeiLuhnValidationAndOemProfileMapping() {
        assertTrue(OemDeviceHelper.isValidImeiLuhn("490154203237518"))
        assertTrue(OemDeviceHelper.isValidImeiLuhn("35-693803-564380-9"))
        assertFalse(OemDeviceHelper.isValidImeiLuhn("490154203237519"))
        assertFalse(OemDeviceHelper.isValidImeiLuhn("12345"))

        assertEquals(OemDeviceHelper.OemFamily.SAMSUNG, OemDeviceHelper.detectFamily("samsung", "samsung"))
        assertEquals(OemDeviceHelper.OemFamily.XIAOMI_REDMI_POCO, OemDeviceHelper.detectFamily("POCO", "Xiaomi"))
        assertEquals(OemDeviceHelper.OemFamily.OPLUS_OPPO_REALME_ONEPLUS, OemDeviceHelper.detectFamily("realme", "OPPO"))
        assertEquals(OemDeviceHelper.OemFamily.TRANSSION_INFINIX_TECNO_ITEL, OemDeviceHelper.detectFamily("Infinix", "INFINIX MOBILITY LIMITED"))
        assertEquals(OemDeviceHelper.OemFamily.VIVO_IQOO, OemDeviceHelper.detectFamily("iQOO", "vivo"))
        assertEquals("*888#", OemDeviceHelper.getOperatorCheckUssd("Telkomsel").first)
    }

    @Test
    fun storageMarketingFormattingPairsChipTierWithUsable() {
        val label = ReportExporter.formatStorageMarketingAndUsable(
            chipTotalBytes = 128_000_000_000L,
            usableTotalBytes = 109L * 1024L * 1024L * 1024L
        )
        assertEquals("128 GB (Usable: 109.00 GB)", label)
    }

    @Test
    fun semverComparisonHandlesPatchMinorAndPreReleaseTags() {
        assertTrue(AppUpdateManager.isRemoteNewer("1.2.0", "v1.3.0"))
        assertTrue(AppUpdateManager.isRemoteNewer("1.2.0", "v1.10.0"))
        assertTrue(AppUpdateManager.isRemoteNewer("1.2.0", "release-2.0.0"))
        assertFalse(AppUpdateManager.isRemoteNewer("1.3.0", "v1.3.0"))
        assertFalse(AppUpdateManager.isRemoteNewer("1.10.0", "v1.2.9"))
        assertTrue(AppUpdateManager.isRemoteNewer("1.3.0-beta1", "v1.3.0"))
        assertFalse(AppUpdateManager.isRemoteNewer("1.3.0", "v1.3.0-rc1"))
    }

    @Test
    fun rawManifestJsonFallbackParsesVersionAndSha256() {
        val sampleJson = """
            {
              "tagName": "v1.3.2",
              "releaseName": "Sanepo Unit Check v1.3.2",
              "body": "Auto OTA release",
              "publishedAt": "2026-11-27T08:00:00Z",
              "htmlUrl": "https://github.com/krisdwiantara12/SanepoUnitCheck/releases",
              "isPreRelease": false,
              "apkAssetName": "SanepoUnitCheck-v1.3.2.apk",
              "apkDownloadUrl": "https://raw.githubusercontent.com/krisdwiantara12/SanepoUnitCheck/main/releases/SanepoUnitCheck-v1.3.2.apk",
              "apkSizeBytes": 1350000,
              "expectedSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
        """.trimIndent()

        val updateFrom131 = requireNotNull(
            AppUpdateManager.parseRawManifestJson(sampleJson, "1.3.1")
        )
        assertEquals("v1.3.2", updateFrom131.tagName)
        assertEquals("SanepoUnitCheck-v1.3.2.apk", updateFrom131.apkAssetName)
        assertEquals(
            "https://raw.githubusercontent.com/krisdwiantara12/SanepoUnitCheck/main/releases/SanepoUnitCheck-v1.3.2.apk",
            updateFrom131.apkDownloadUrl
        )
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            updateFrom131.expectedSha256
        )
        assertTrue(updateFrom131.updateAvailable)

        val sameVersion = requireNotNull(
            AppUpdateManager.parseRawManifestJson(sampleJson, "1.3.2")
        )
        assertFalse(sameVersion.updateAvailable)
        assertTrue(AppUpdateManager.parseRawManifestJson("{}", "1.3.1") == null)
    }
}
