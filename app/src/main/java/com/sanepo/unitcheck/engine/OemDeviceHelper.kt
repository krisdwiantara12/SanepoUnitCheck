package com.sanepo.unitcheck.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.sanepo.unitcheck.util.Lang
import com.sanepo.unitcheck.util.SystemPropReader
import java.util.Locale

object OemDeviceHelper {

    enum class OemFamily {
        SAMSUNG,
        XIAOMI_REDMI_POCO,
        OPLUS_OPPO_REALME_ONEPLUS,
        VIVO_IQOO,
        TRANSSION_INFINIX_TECNO_ITEL,
        HUAWEI_HONOR,
        SONY,
        SHARP,
        ASUS,
        MOTOROLA,
        ZTE_NUBIA,
        GOOGLE_PIXEL,
        NOTHING,
        GENERIC
    }

    data class OemProfile(
        val family: OemFamily,
        val displayBrandLabel: String,
        val osFlavorLabel: String,
        val imeiSettingsPathId: String,
        val imeiSettingsPathEn: String,
        val primaryQcCode: String,
        val primaryQcTitleId: String,
        val primaryQcTitleEn: String,
        val extraDialCodes: List<Pair<String, String>>,
        val proximityIsVirtualLikely: Boolean,
        val proximityNoteId: String,
        val proximityNoteEn: String
    )

    fun detectFamily(
        brand: String = Build.BRAND.orEmpty(),
        manufacturer: String = Build.MANUFACTURER.orEmpty()
    ): OemFamily {
        val raw = "$brand $manufacturer".lowercase(Locale.US)
        return when {
            raw.contains("samsung") -> OemFamily.SAMSUNG
            raw.contains("xiaomi") || raw.contains("redmi") || raw.contains("poco") -> OemFamily.XIAOMI_REDMI_POCO
            raw.contains("oppo") || raw.contains("realme") || raw.contains("oneplus") -> OemFamily.OPLUS_OPPO_REALME_ONEPLUS
            raw.contains("vivo") || raw.contains("iqoo") -> OemFamily.VIVO_IQOO
            raw.contains("infinix") || raw.contains("tecno") || raw.contains("itel") || raw.contains("transsion") -> OemFamily.TRANSSION_INFINIX_TECNO_ITEL
            raw.contains("huawei") || raw.contains("honor") -> OemFamily.HUAWEI_HONOR
            raw.contains("sony") -> OemFamily.SONY
            raw.contains("sharp") -> OemFamily.SHARP
            raw.contains("asus") -> OemFamily.ASUS
            raw.contains("motorola") || raw.contains("moto") -> OemFamily.MOTOROLA
            raw.contains("zte") || raw.contains("nubia") || raw.contains("redmagic") -> OemFamily.ZTE_NUBIA
            raw.contains("google") -> OemFamily.GOOGLE_PIXEL
            raw.contains("nothing") || raw.contains("cmf") -> OemFamily.NOTHING
            else -> OemFamily.GENERIC
        }
    }

    fun buildProfile(
        brand: String = Build.BRAND.orEmpty(),
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): OemProfile {
        val family = detectFamily(brand, manufacturer)
        val hyperOsVer = SystemPropReader.firstNonBlank("ro.mi.os.version.name", "ro.miui.ui.version.name")
        val oneUiVer = SystemPropReader.get("ro.build.version.oneui")
        val oplusVer = SystemPropReader.firstNonBlank("ro.build.version.oplusrom", "ro.build.version.opporom")

        return when (family) {
            OemFamily.SAMSUNG -> OemProfile(
                family = family,
                displayBrandLabel = "Samsung",
                osFlavorLabel = if (oneUiVer.isNotBlank()) "One UI ($oneUiVer) / API $sdkInt" else "Samsung Android API $sdkInt",
                imeiSettingsPathId = "Menu 'Tentang ponsel' langsung tampil IMEI (Slot 1), IMEI (Slot 2) & Nomor Seri di atas.",
                imeiSettingsPathEn = "'About phone' screen displays IMEI (Slot 1), IMEI (Slot 2) & Serial directly.",
                primaryQcCode = "*#0*#",
                primaryQcTitleId = "Samsung QC Menu (*#0*#)",
                primaryQcTitleEn = "Samsung Hardware QC (*#0*#)",
                extraDialCodes = listOf(
                    "*#0*#" to "Samsung Hardware Test (nonaktifkan Auto Blocker jika diblokir)",
                    "*#1234#" to "Cek AP / CP / CSC Firmware Resmi",
                    "*#0228#" to "Battery ADC / Status Tegangan",
                    "*#0011#" to "ServiceMode Sinyal / Jaringan"
                ),
                proximityIsVirtualLikely = Build.MODEL.orEmpty().uppercase(Locale.US).let {
                    it.contains("SM-A") || it.contains("SM-M")
                },
                proximityNoteId = "Beberapa Samsung seri A/M memakai proximity virtual (angkat posisi telepon jika di meja tidak merespons).",
                proximityNoteEn = "Some Samsung A/M series use virtual proximity (lift upright to ear angle if flat test does not trigger)."
            )

            OemFamily.XIAOMI_REDMI_POCO -> {
                val isModernMi = sdkInt >= 30 || hyperOsVer.isNotBlank()
                OemProfile(
                    family = family,
                    displayBrandLabel = "Xiaomi / Redmi / POCO",
                    osFlavorLabel = if (hyperOsVer.isNotBlank()) "$hyperOsVer / API $sdkInt" else "MIUI / HyperOS API $sdkInt",
                    imeiSettingsPathId = if (isModernMi) {
                        "Buka Pengaturan -> Tentang ponsel -> Info detail & spesifikasi -> Status -> Informasi IMEI."
                    } else {
                        "Buka Pengaturan -> Tentang ponsel -> Semua spesifikasi -> Status -> Informasi IMEI."
                    },
                    imeiSettingsPathEn = if (isModernMi) {
                        "Settings -> About phone -> Detailed info & specs -> Status -> IMEI information."
                    } else {
                        "Settings -> About phone -> All specs -> Status -> IMEI information."
                    },
                    primaryQcCode = "*#*#6484#*#*",
                    primaryQcTitleId = "Xiaomi CIT Hardware Test (*#*#6484#*#*)",
                    primaryQcTitleEn = "Xiaomi CIT Menu (*#*#6484#*#*)",
                    extraDialCodes = listOf(
                        "*#*#6484#*#*" to "CIT Hardware Diagnostic Xiaomi/Redmi/POCO",
                        "*#*#64663#*#*" to "CIT Alternatif (jika 6484 tidak terbuka)",
                        "*#*#4636#*#*" to "Testing Info Sinyal 4G/5G & Statistik",
                        "Ketuk Versi Kernel 5x" to "Di Tentang ponsel -> Info detail untuk buka CIT tanpa dial"
                    ),
                    proximityIsVirtualLikely = true,
                    proximityNoteId = "Xiaomi/Redmi/POCO banyak memakai Proximity Virtual/Ultrasonic (Elliptic): angkat HP tegak & arahkan ke telinga/telapak tangan!",
                    proximityNoteEn = "Many Xiaomi/Redmi/POCO units use Virtual/Ultrasonic proximity: hold upright in ear-call motion to trigger NEAR!"
                )
            }

            OemFamily.OPLUS_OPPO_REALME_ONEPLUS -> OemProfile(
                family = family,
                displayBrandLabel = brand.ifBlank { "OPPO / realme / OnePlus" },
                osFlavorLabel = if (oplusVer.isNotBlank()) "ColorOS/RealmeUI $oplusVer / API $sdkInt" else "ColorOS/OxygenOS API $sdkInt",
                imeiSettingsPathId = "Buka Tentang perangkat (About device) -> Status -> IMEI (Slot SIM 1 & 2).",
                imeiSettingsPathEn = "Open About device -> Status -> IMEI (SIM Slot 1 & 2).",
                primaryQcCode = "*#899#",
                primaryQcTitleId = "OPPO/realme Engineer Mode (*#899#)",
                primaryQcTitleEn = "OPlus Engineer Mode (*#899#)",
                extraDialCodes = listOf(
                    "*#899#" to "EngineerMode -> Manual Test (LCD/Sensor/Audio/Cam)",
                    "*#808#" to "Engineering Test ColorOS lawas",
                    "*#6776#" to "Software Version & Region Build",
                    "*#*#4636#*#*" to "Phone Info & Radio Band"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup area dekat earpiece atas layar untuk tes proximity 2-arah.",
                proximityNoteEn = "Cover top bezel near earpiece for bidirectional proximity test."
            )

            OemFamily.VIVO_IQOO -> OemProfile(
                family = family,
                displayBrandLabel = "vivo / iQOO",
                osFlavorLabel = "FuntouchOS / OriginOS API $sdkInt",
                imeiSettingsPathId = "Buka Tentang ponsel -> Status -> Informasi IMEI (atau Ketik *#06#).",
                imeiSettingsPathEn = "Open About phone -> Status -> IMEI Information.",
                primaryQcCode = "*#558#",
                primaryQcTitleId = "vivo Factory Test (*#558#)",
                primaryQcTitleEn = "vivo Factory Test (*#558#)",
                extraDialCodes = listOf(
                    "*#558#" to "Factory Test / Engineering Verification",
                    "*#*#4838#*#*" to "vivo Testing Info (Sinyal, Wi-Fi, Baterai)"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup area sensor atas secara penuh hingga status NEAR terbaca.",
                proximityNoteEn = "Fully cover top proximity window until NEAR triggers."
            )

            OemFamily.TRANSSION_INFINIX_TECNO_ITEL -> OemProfile(
                family = family,
                displayBrandLabel = brand.ifBlank { "Infinix / Tecno / itel" },
                osFlavorLabel = "XOS / HiOS API $sdkInt",
                imeiSettingsPathId = "Buka Pengaturan -> Ponsel saya (My Phone) -> gulir ke bawah ke IMEI 1 & IMEI 2.",
                imeiSettingsPathEn = "Open Settings -> My Phone -> scroll down to IMEI 1 & IMEI 2.",
                primaryQcCode = "*#*#88#*#*",
                primaryQcTitleId = "Transsion QC Menu (*#*#88#*#*)",
                primaryQcTitleEn = "Transsion QC Menu (*#*#88#*#*)",
                extraDialCodes = listOf(
                    "*#*#88#*#*" to "Factory Diagnostic Infinix/Tecno/itel",
                    "*#9646633#" to "MTK Engineer Mode (untuk chipset MediaTek)",
                    "*#85#" to "Factory Test alternatif Transsion lama"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup bagian atas earpiece dengan telapak tangan lalu buka kembali.",
                proximityNoteEn = "Cover top earpiece with palm then uncover."
            )

            OemFamily.HUAWEI_HONOR -> OemProfile(
                family = family,
                displayBrandLabel = brand.ifBlank { "Huawei / HONOR" },
                osFlavorLabel = "EMUI / MagicOS API $sdkInt",
                imeiSettingsPathId = "Buka Pengaturan -> Tentang ponsel -> gulir untuk lihat IMEI 1 & IMEI 2 (atau Status).",
                imeiSettingsPathEn = "Open Settings -> About phone -> view IMEI 1 & IMEI 2 (or Status).",
                primaryQcCode = "*#*#2846579#*#*",
                primaryQcTitleId = "Huawei/HONOR ProjectMenu (*#*#2846579#*#*)",
                primaryQcTitleEn = "ProjectMenu (*#*#2846579#*#*)",
                extraDialCodes = listOf(
                    "*#*#2846579#*#*" to "ProjectMenu (Veneer / Network Info)",
                    "*#*#1357946#*#*" to "Cek Serial & Product ID"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup area earpiece atas dengan tangan (NEAR -> FAR).",
                proximityNoteEn = "Cover top earpiece bezel (NEAR -> FAR)."
            )

            OemFamily.SONY -> OemProfile(
                family = family,
                displayBrandLabel = "Sony Xperia",
                osFlavorLabel = "Xperia Android API $sdkInt",
                imeiSettingsPathId = "Buka Pengaturan -> Tentang ponsel -> gulir ke IMEI (Slot SIM 1/2).",
                imeiSettingsPathEn = "Open Settings -> About phone -> IMEI (SIM Slot 1/2).",
                primaryQcCode = "*#*#7378423#*#*",
                primaryQcTitleId = "Sony Service Menu (*#*#7378423#*#*)",
                primaryQcTitleEn = "Sony Service Tests (*#*#7378423#*#*)",
                extraDialCodes = listOf(
                    "*#*#7378423#*#*" to "Service Tests (Barometer, Pressure, Touchscreen, Speaker)"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup area atas layar untuk tes proximity.",
                proximityNoteEn = "Cover top bezel for proximity check."
            )

            OemFamily.SHARP -> OemProfile(
                family = family,
                displayBrandLabel = "Sharp AQUOS",
                osFlavorLabel = "AQUOS Android API $sdkInt",
                imeiSettingsPathId = "Buka Pengaturan -> Tentang ponsel -> IMEI / Status perangkat.",
                imeiSettingsPathEn = "Open Settings -> About phone -> IMEI / Device Status.",
                primaryQcCode = "*#06#",
                primaryQcTitleId = "Cek IMEI *#06# & Pengaturan",
                primaryQcTitleEn = "IMEI *#06# & Settings",
                extraDialCodes = listOf(
                    "*#*#3424#*#*" to "Hardware Diagnostic (jika tersedia di firmware)",
                    "*#*#4636#*#*" to "Phone Info & Radio Network"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup bagian bezel atas hingga NEAR terdeteksi.",
                proximityNoteEn = "Cover top bezel until NEAR triggers."
            )

            OemFamily.ASUS -> OemProfile(
                family = family,
                displayBrandLabel = "ASUS Zenfone / ROG",
                osFlavorLabel = "ZenUI / ROG UI API $sdkInt",
                imeiSettingsPathId = "Buka Pengaturan -> Sistem -> Tentang ponsel -> Status -> Informasi IMEI.",
                imeiSettingsPathEn = "Settings -> System -> About phone -> Status -> IMEI.",
                primaryQcCode = ".12345+=",
                primaryQcTitleId = "ASUS SMMI Test (ketik .12345+= di Kalkulator)",
                primaryQcTitleEn = "ASUS SMMI Test (.12345+= in Calculator)",
                extraDialCodes = listOf(
                    ".12345+=" to "Ketik di aplikasi Kalkulator bawaan ASUS -> SMMI Hardware Test"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup bagian atas layar untuk tes proximity.",
                proximityNoteEn = "Cover top screen edge for proximity test."
            )

            OemFamily.MOTOROLA -> OemProfile(
                family = family,
                displayBrandLabel = "Motorola",
                osFlavorLabel = "MyUX / Hello UI API $sdkInt",
                imeiSettingsPathId = "Pengaturan -> Tentang ponsel -> gulir ke IMEI (Slot SIM).",
                imeiSettingsPathEn = "Settings -> About phone -> scroll to IMEI.",
                primaryQcCode = "*#*#2486#*#*",
                primaryQcTitleId = "Moto CQA Test (*#*#2486#*#*)",
                primaryQcTitleEn = "Moto CQA Mode (*#*#2486#*#*)",
                extraDialCodes = listOf(
                    "*#*#2486#*#*" to "CQA Hardware Diagnostic Menu",
                    "*#*#4636#*#*" to "Testing Radio & Signal"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup sensor dekat earpiece atas.",
                proximityNoteEn = "Cover top earpiece sensor."
            )

            else -> OemProfile(
                family = family,
                displayBrandLabel = brand.ifBlank { manufacturer }.ifBlank { "Android" },
                osFlavorLabel = "Android ${Build.VERSION.RELEASE} (API $sdkInt)",
                imeiSettingsPathId = "Buka Pengaturan -> Tentang ponsel -> gulir ke bagian IMEI / Status.",
                imeiSettingsPathEn = "Open Settings -> About phone -> scroll to IMEI / Status.",
                primaryQcCode = "*#*#4636#*#*",
                primaryQcTitleId = "Info Sinyal & Jaringan (*#*#4636#*#*)",
                primaryQcTitleEn = "Testing Info (*#*#4636#*#*)",
                extraDialCodes = listOf(
                    "*#*#4636#*#*" to "Android Testing / Radio & Data Info"
                ),
                proximityIsVirtualLikely = false,
                proximityNoteId = "Tutup bagian atas layar dengan tangan (NEAR) lalu buka kembali (FAR).",
                proximityNoteEn = "Cover top bezel (NEAR) then uncover (FAR)."
            )
        }
    }

    fun openDeviceInfoSettings(context: Context): Boolean {
        val candidates = arrayOf(
            Settings.ACTION_DEVICE_INFO_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (action in candidates) {
            try {
                context.startActivity(Intent(action))
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /**
     * Most stock dialers (Samsung OneUI, Google Dialer, Xiaomi, ColorOS) strip or reject full `*#06#`
     * via ACTION_DIAL because `SpecialCharSequenceMgr` requires interactive typing of the final `#`.
     * Pre-filling `*#06` (without final `#`) preserves `#` via `%23` encoding so the user only taps `#` 1x.
     */
    fun openSmartDialerWithCode(context: Context, fullCode: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("SanepoCode", fullCode))

        val prefill = if (fullCode.endsWith("#") && fullCode.length > 2) {
            fullCode.dropLast(1)
        } else {
            fullCode
        }
        val uri = Uri.fromParts("tel", prefill, null)
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, uri))
            val msg = if (fullCode.endsWith("#")) {
                Lang.t(
                    context,
                    "Dialer dibuka dengan '$prefill' (kode disalin). Tinggal KETIK '#' 1x di ujung!",
                    "Dialer opened with '$prefill' (copied). Just TAP '#' once at the end!"
                )
            } else {
                Lang.t(
                    context,
                    "Kode '$fullCode' disalin ke clipboard.",
                    "Code '$fullCode' copied to clipboard."
                )
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            Toast.makeText(
                context,
                Lang.t(context, "Kode '$fullCode' disalin. Buka Telepon lalu tempel.", "Code '$fullCode' copied. Paste in Phone app."),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun isValidImeiLuhn(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != 15) return false
        var sum = 0
        for (i in 0 until 15) {
            var d = digits[i] - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    fun getOperatorCheckUssd(operatorName: String): Pair<String, String> {
        val op = operatorName.lowercase(Locale.US)
        return when {
            op.contains("telkomsel") || op.contains("simpati") || op.contains("by.u") || op.contains("halo") ->
                "*888#" to "Telkomsel (*888#)"
            op.contains("indosat") || op.contains("im3") ->
                "*123#" to "Indosat/IM3 (*123#)"
            op.contains("tri") || op.contains("3 ") || op == "3" ->
                "*123#" to "Tri 3 (*123#)"
            op.contains("xl") || op.contains("axis") ->
                "*808#" to "XL / AXIS (*808#)"
            op.contains("smartfren") ->
                "*995#" to "Smartfren (*995#)"
            else -> "" to "Buka Dialer (Tes Call)"
        }
    }

    fun showOemImeiAndQcDialog(
        activity: Activity,
        onLaunchScanner: (() -> Unit)? = null
    ) {
        val profile = buildProfile()
        val message = buildString {
            appendLine("Merk / UI : ${profile.displayBrandLabel} (${profile.osFlavorLabel})")
            appendLine()
            appendLine(Lang.t(activity, "JALUR IMEI PALING CEPAT:", "FASTEST IMEI PATHS:"))
            appendLine("1. " + Lang.t(activity, profile.imeiSettingsPathId, profile.imeiSettingsPathEn))
            appendLine("2. " + Lang.t(
                activity,
                "Dialer *#06#: ketik '#' 1x di ujung setelah Dialer terbuka (di Samsung/Xiaomi kode langsung muncul barcode IMEI).",
                "Dialer *#06#: tap '#' once at the end when Dialer opens to trigger IMEI barcode popup."
            ))
            appendLine()
            appendLine(Lang.t(activity, "KODE MENU QC PABRIK (${profile.displayBrandLabel}):", "OEM QC CODES (${profile.displayBrandLabel}):"))
            for ((code, desc) in profile.extraDialCodes) {
                appendLine("- $code : $desc")
            }
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(Lang.t(activity, "Panduan Cek IMEI & Menu QC (${profile.displayBrandLabel})", "OEM IMEI & QC Helper (${profile.displayBrandLabel})"))
            .setMessage(message)
            .setPositiveButton(Lang.t(activity, "PENGATURAN IMEI", "IMEI IN SETTINGS")) { _, _ ->
                if (!openDeviceInfoSettings(activity)) {
                    Toast.makeText(activity, "Settings tidak tersedia.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("DIAL *#06 (+#)") { _, _ ->
                openSmartDialerWithCode(activity, "*#06#")
            }

        if (onLaunchScanner != null) {
            builder.setNeutralButton(Lang.t(activity, "SCAN BARCODE DUS", "SCAN BOX BARCODE")) { _, _ ->
                onLaunchScanner()
            }
        } else if (profile.primaryQcCode.startsWith("*")) {
            builder.setNeutralButton("QC ${profile.primaryQcCode}") { _, _ ->
                openSmartDialerWithCode(activity, profile.primaryQcCode)
            }
        }
        builder.show()
    }
}
