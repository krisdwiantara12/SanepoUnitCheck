package com.sanepo.unitcheck.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sanepo.unitcheck.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

object AppUpdateManager {

    const val GITHUB_OWNER = "krisdwiantara12"
    const val GITHUB_REPO = "SanepoUnitCheck"
    const val REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val RELEASES_PAGE_URL = "$REPO_URL/releases"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    private const val ALL_RELEASES_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=5"

    private const val PREF_UPDATE = "sanepo_update_prefs"
    private const val KEY_AUTO_CHECK = "auto_check_on_launch"
    private const val KEY_INCLUDE_BETA = "include_pre_releases"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_CACHED_TAG = "cached_latest_tag"
    private const val AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

    data class ReleaseInfo(
        val tagName: String,
        val releaseName: String,
        val body: String,
        val publishedAt: String,
        val htmlUrl: String,
        val isPreRelease: Boolean,
        val apkAssetName: String?,
        val apkDownloadUrl: String?,
        val apkSizeBytes: Long,
        val expectedSha256: String?,
        val updateAvailable: Boolean
    )

    sealed class CheckOutcome {
        data class Success(val release: ReleaseInfo, val checkedAtMs: Long) : CheckOutcome()
        data class NoReleasePublished(val checkedAtMs: Long, val detail: String) : CheckOutcome()
        data class Error(val message: String) : CheckOutcome()
    }

    sealed class DownloadOutcome {
        data class Success(val apkFile: File, val actualSha256: String) : DownloadOutcome()
        data class Error(val message: String) : DownloadOutcome()
    }

    fun isAutoCheckOnLaunch(context: Context): Boolean {
        return context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK, false)
    }

    fun setAutoCheckOnLaunch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CHECK, enabled)
            .apply()
    }

    fun isIncludePreReleases(context: Context): Boolean {
        return context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_INCLUDE_BETA, false)
    }

    fun setIncludePreReleases(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INCLUDE_BETA, enabled)
            .apply()
    }

    fun getLastCheckTimeMs(context: Context): Long {
        return context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_MS, 0L)
    }

    fun getCachedLatestTag(context: Context): String {
        return context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_TAG, "").orEmpty()
    }

    fun shouldRunAutoCheckNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isAutoCheckOnLaunch(context)) return false
        val last = getLastCheckTimeMs(context)
        return last <= 0L || (nowMs - last) >= AUTO_CHECK_INTERVAL_MS
    }

    fun hasNetworkConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildManageUnknownSourcesIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * Parses a version string such as "v1.2.10-beta" into integer segments ([1, 2, 10])
     * paired with a pre-release flag so numerical version comparisons avoid string-order bugs.
     */
    fun parseVersionComponents(raw: String): Pair<List<Int>, Boolean> {
        val cleaned = raw.trim()
            .removePrefix("release-")
            .removePrefix("Release-")
            .removePrefix("v")
            .removePrefix("V")
        val isPreRelease = cleaned.contains('-')
        val corePart = cleaned.substringBefore('-')
        val numbers = corePart.split('.')
            .mapNotNull { token ->
                val digits = token.takeWhile { it.isDigit() }
                digits.toIntOrNull()
            }
        return Pair(if (numbers.isEmpty()) listOf(0) else numbers, isPreRelease)
    }

    fun isRemoteNewer(currentVersion: String, remoteTag: String): Boolean {
        val (curNums, curPre) = parseVersionComponents(currentVersion)
        val (remNums, remPre) = parseVersionComponents(remoteTag)
        val maxLen = maxOf(curNums.size, remNums.size)
        for (i in 0 until maxLen) {
            val c = curNums.getOrElse(i) { 0 }
            val r = remNums.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return curPre && !remPre
    }

    fun parseReleaseJson(
        rawJson: String,
        isArrayPayload: Boolean,
        currentVersion: String
    ): ReleaseInfo? {
        val releaseObj: JSONObject = if (isArrayPayload) {
            val arr = JSONArray(rawJson)
            var found: JSONObject? = null
            for (i in 0 until arr.length()) {
                val candidate = arr.optJSONObject(i) ?: continue
                if (!candidate.optBoolean("draft", false)) {
                    found = candidate
                    break
                }
            }
            found ?: return null
        } else {
            JSONObject(rawJson)
        }

        val tagName = releaseObj.optString("tag_name").trim()
        if (tagName.isEmpty()) return null
        val releaseName = releaseObj.optString("name").ifBlank { tagName }
        val body = releaseObj.optString("body").orEmpty()
        val publishedAt = releaseObj.optString("published_at").orEmpty()
        val htmlUrl = releaseObj.optString("html_url").ifBlank { RELEASES_PAGE_URL }
        val isPre = releaseObj.optBoolean("prerelease", false)

        val assets = releaseObj.optJSONArray("assets")
        var bestApk: JSONObject? = null
        if (assets != null) {
            val apkCandidates = ArrayList<JSONObject>()
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkCandidates.add(a)
                }
            }
            bestApk = apkCandidates.firstOrNull {
                val n = it.optString("name")
                !n.contains("unsigned", ignoreCase = true) && !n.contains("debug", ignoreCase = true)
            } ?: apkCandidates.firstOrNull {
                !it.optString("name").contains("unsigned", ignoreCase = true)
            } ?: apkCandidates.firstOrNull()
        }

        val assetName = bestApk?.optString("name")
        val assetUrl = bestApk?.optString("browser_download_url")
        val assetSize = bestApk?.optLong("size", 0L) ?: 0L

        val apiDigestRaw = bestApk?.optString("digest").orEmpty()
        val digestFromApi = if (apiDigestRaw.startsWith("sha256:", ignoreCase = true)) {
            apiDigestRaw.substringAfter(':').trim().takeIf { it.length == 64 }
        } else {
            null
        }
        val sha256FromBody = extractSha256FromBody(body)

        return ReleaseInfo(
            tagName = tagName,
            releaseName = releaseName,
            body = body,
            publishedAt = publishedAt,
            htmlUrl = htmlUrl,
            isPreRelease = isPre,
            apkAssetName = assetName,
            apkDownloadUrl = assetUrl,
            apkSizeBytes = assetSize,
            expectedSha256 = digestFromApi ?: sha256FromBody,
            updateAvailable = isRemoteNewer(currentVersion, tagName)
        )
    }

    private fun extractSha256FromBody(body: String): String? {
        if (body.isBlank()) return null
        val m = Pattern.compile("(?i)SHA-?256\\s*[:=]\\s*([0-9a-f]{64})").matcher(body)
        return if (m.find()) m.group(1) else null
    }

    fun checkForUpdate(context: Context, includePreReleases: Boolean): CheckOutcome {
        if (!hasNetworkConnection(context)) {
            return CheckOutcome.Error("Tidak ada koneksi internet aktif. Cek Wi-Fi atau data seluler.")
        }
        val urlStr = if (includePreReleases) ALL_RELEASES_API else LATEST_RELEASE_API
        var conn: HttpURLConnection? = null
        return try {
            conn = openHttpConnection(urlStr, isJsonApi = true)
            val code = conn.responseCode
            val nowMs = System.currentTimeMillis()
            context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CHECK_MS, nowMs)
                .apply()

            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val payload = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val parsed = parseReleaseJson(
                        rawJson = payload,
                        isArrayPayload = includePreReleases,
                        currentVersion = BuildConfig.VERSION_NAME
                    )
                    if (parsed == null) {
                        CheckOutcome.NoReleasePublished(
                            nowMs,
                            "Belum ada GitHub Release publik pada $GITHUB_OWNER/$GITHUB_REPO."
                        )
                    } else {
                        context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_CACHED_TAG, parsed.tagName)
                            .apply()
                        CheckOutcome.Success(parsed, nowMs)
                    }
                }
                HttpURLConnection.HTTP_NOT_FOUND -> CheckOutcome.NoReleasePublished(
                    nowMs,
                    "Belum ada rilis APK di GitHub ($GITHUB_OWNER/$GITHUB_REPO/releases)."
                )
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> CheckOutcome.Error(
                    "GitHub API rate limit (HTTP $code). Coba beberapa menit lagi atau buka halaman Releases."
                )
                else -> CheckOutcome.Error("GitHub API merespons HTTP $code.")
            }
        } catch (t: Throwable) {
            CheckOutcome.Error("Gagal menghubungi GitHub: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    fun downloadUpdateApk(
        context: Context,
        release: ReleaseInfo,
        cancelFlag: AtomicBoolean,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): DownloadOutcome {
        val downloadUrl = release.apkDownloadUrl
        if (downloadUrl.isNullOrBlank()) {
            return DownloadOutcome.Error("Rilis ${release.tagName} tidak menyertakan file asset .apk.")
        }
        val updatesDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
        val safeName = (release.apkAssetName ?: "SanepoUnitCheck-${release.tagName}.apk")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val partFile = File(updatesDir, "$safeName.part")
        val targetFile = File(updatesDir, safeName)
        if (partFile.exists()) partFile.delete()

        var conn: HttpURLConnection? = null
        return try {
            conn = openHttpConnectionWithRedirects(downloadUrl, maxRedirects = 6)
            val code = conn.responseCode
            if (code !in 200..299) {
                return DownloadOutcome.Error("Server unduhan mengembalikan HTTP $code.")
            }
            val contentLen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
            } else {
                conn.contentLength.toLong().takeIf { it > 0L } ?: release.apkSizeBytes
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalRead = 0L
            val magic = ByteArray(4)
            var magicRead = 0
            val buf = ByteArray(16 * 1024)
            var lastReportedPct = -1

            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    while (!cancelFlag.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (magicRead < 4) {
                            val copyLen = minOf(4 - magicRead, n)
                            System.arraycopy(buf, 0, magic, magicRead, copyLen)
                            magicRead += copyLen
                        }
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        totalRead += n
                        val pct = if (contentLen > 0L) {
                            ((totalRead * 100L) / contentLen).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            onProgress(pct, totalRead, contentLen)
                        }
                    }
                    output.flush()
                }
            }

            if (cancelFlag.get()) {
                partFile.delete()
                return DownloadOutcome.Error("Unduhan dibatalkan.")
            }
            if (magicRead < 2 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte() || totalRead < 100_000L) {
                partFile.delete()
                return DownloadOutcome.Error("File unduhan bukan APK valid (korup atau terblokir portal jaringan).")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = release.expectedSha256
            if (!expected.isNullOrBlank() && !actualSha.equals(expected, ignoreCase = true)) {
                partFile.delete()
                return DownloadOutcome.Error("Verifikasi SHA-256 gagal! Unduhan ditolak demi keamanan.")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            DownloadOutcome.Success(targetFile, actualSha)
        } catch (t: Throwable) {
            if (partFile.exists()) partFile.delete()
            DownloadOutcome.Error("Gagal mengunduh APK: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun openHttpConnection(urlStr: String, isJsonApi: Boolean): HttpURLConnection {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = if (isJsonApi) 15_000 else 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SanepoUnitCheck/${BuildConfig.VERSION_NAME}")
            if (isJsonApi) {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
        }
        conn.connect()
        return conn
    }

    private fun openHttpConnectionWithRedirects(initialUrl: String, maxRedirects: Int): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SanepoUnitCheck/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/octet-stream, application/vnd.android.package-archive")
            }
            conn.connect()
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank() || redirects >= maxRedirects) {
                    throw IllegalStateException("Terlalu banyak redirect ($code)")
                }
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
                continue
            }
            return conn
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): String? {
        if (!apkFile.exists() || apkFile.length() < 100_000L) {
            return "File APK tidak ditemukan atau rusak."
        }
        if (!canRequestPackageInstalls(context)) {
            return "NEED_INSTALL_PERMISSION"
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        } catch (t: Throwable) {
            "Gagal membuka installer APK: ${t.message}"
        }
    }

    @Suppress("DEPRECATION")
    fun readApkSigningSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val info = pi.signingInfo
                val sigs = if (info != null) {
                    if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
                } else {
                    null
                }
                sigs?.firstOrNull()?.toByteArray()
            } else {
                val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                pi.signatures?.firstOrNull()?.toByteArray()
            }
            if (bytes == null) return "Unavailable"
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
        } catch (_: Throwable) {
            "Unavailable"
        }
    }

    fun getCacheUsageBytes(context: Context): Pair<Long, Long> {
        fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val updates = dirSize(File(context.cacheDir, "updates"))
        val reports = dirSize(File(context.cacheDir, "reports"))
        return Pair(updates, reports)
    }

    fun clearCacheDirs(context: Context): Int {
        var deleted = 0
        for (sub in listOf("updates", "reports")) {
            val dir = File(context.cacheDir, sub)
            if (dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    if (f.deleteRecursively()) deleted++
                }
            }
        }
        return deleted
    }
}
