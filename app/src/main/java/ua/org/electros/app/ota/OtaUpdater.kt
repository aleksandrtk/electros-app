package ua.org.electros.app.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

object OtaUpdater {
    private const val TAG = "OtaUpdater"
    const val REPO_OWNER = "aleksandrtk"
    const val REPO_NAME = "electros-app"

    /** Hosts the APK download may be redirected to (GitHub Releases CDN). */
    private val ALLOWED_REDIRECT_HOSTS = listOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "raw.githubusercontent.com",
        "codeload.github.com"
    )

    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    enum class InstallOutcome {
        LAUNCHED_INSTALLER,
        NEEDS_UNKNOWN_SOURCES_PERMISSION,
        FAILED
    }

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val rawTagName: String,
        val parsedVersion: String,
        val releaseName: String,
        val changelog: String,
        val downloadUrl: String?,
        val releaseHtmlUrl: String?,
        val expectedSha256: String? = null,
        val expectedSizeBytes: Long = 0L
    )

    internal data class ReleaseFlags(val isDraft: Boolean, val isPrerelease: Boolean)

    internal fun selectFirstStableReleaseIndex(releases: List<ReleaseFlags>): Int? =
        releases.indexOfFirst { !it.isDraft && !it.isPrerelease }.takeIf { it >= 0 }

    internal fun doCertificatesMatch(
        expectedPackage: String,
        archivePackage: String?,
        installedDigests: List<String>,
        archiveDigests: List<String>
    ): Boolean {
        if (archivePackage != expectedPackage) return false
        if (installedDigests.isEmpty() || archiveDigests.isEmpty()) return false
        return installedDigests == archiveDigests
    }

    /** Parses GitHub asset digest format ("sha256:abcdef...") into bare hex, or null. */
    fun parseDigest(digest: String?): String? {
        if (digest.isNullOrBlank()) return null
        val hex = digest.trim().substringAfter(":", "").lowercase()
        return if (hex.length == 64 && hex.all { it.isDigit() || it in 'a'..'f' }) hex else null
    }

    fun isValidDownloadUrl(urlString: String): Boolean {
        val parsed = urlString.trim().toHttpUrlOrNull() ?: return false
        return parsed.isHttps &&
            ALLOWED_REDIRECT_HOSTS.any { parsed.host.equals(it, ignoreCase = true) }
    }

    fun computeSha256(file: File): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().toHexString()
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(Locale.US, it) }

    internal fun hasMatchingSigningCertificate(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = PackageManager.PackageInfoFlags.of(
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            )
            val installed = pm.getPackageInfo(context.packageName, flags)
            val archive = pm.getPackageArchiveInfo(apkFile.path, flags)
            val installedDigests = installed.signingInfo?.apkContentsSigners
                .orEmpty().map { sha256Hex(it.toByteArray()) }.sorted()
            val archiveDigests = archive?.signingInfo?.apkContentsSigners
                .orEmpty().map { sha256Hex(it.toByteArray()) }.sorted()
            doCertificatesMatch(
                expectedPackage = context.packageName,
                archivePackage = archive?.packageName,
                installedDigests = installedDigests,
                archiveDigests = archiveDigests
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify APK signing certificate: ${e.message}")
            false
        }
    }

    /**
     * Normalizes version strings by removing common prefixes/suffixes (v, release, -stable)
     * and parsing numeric version components.
     * Examples:
     *   "v1.0.0" -> [1, 0, 0]
     *   "release2.0-stable" -> [2, 0]
     *   "Release-v2.0.1-beta2" -> [2, 0, 1]
     *   "3.1.4.2" -> [3, 1, 4, 2]
     */
    fun parseVersionNumbers(tag: String): List<Int> {
        val prefixRegex = Regex("^(?:release|v)[\\s_\\-.]*", RegexOption.IGNORE_CASE)
        var cleanTag = tag.trim()
        while (true) {
            val match = prefixRegex.find(cleanTag) ?: break
            cleanTag = cleanTag.substring(match.value.length)
        }
        cleanTag = cleanTag.substringBefore("-")

        val regex = Regex("\\d+")
        return regex.findAll(cleanTag).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    /**
     * Compares installed version with remote release tag.
     * Returns true if latest is strictly greater than current.
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = parseVersionNumbers(current)
        val latestParts = parseVersionNumbers(latest)

        if (latestParts.isEmpty()) return false
        if (currentParts.isEmpty()) return true

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val curVal = currentParts.getOrElse(i) { 0 }
            val latVal = latestParts.getOrElse(i) { 0 }
            if (latVal > curVal) return true
            if (curVal > latVal) return false
        }
        return false
    }

    /**
     * Checks GitHub Releases API for the latest release.
     */
    suspend fun checkForUpdates(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        val latestUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        val fallbackAllReleasesUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"

        try {
            var releaseJson = fetchJson(latestUrl)
            if (releaseJson == null) {
                val releasesListJson = fetchJsonArray(fallbackAllReleasesUrl)
                if (releasesListJson != null && releasesListJson.length() > 0) {
                    val flags = (0 until releasesListJson.length()).map { i ->
                        val item = releasesListJson.getJSONObject(i)
                        ReleaseFlags(
                            isDraft = item.optBoolean("draft", false),
                            isPrerelease = item.optBoolean("prerelease", false)
                        )
                    }
                    val stableIndex = selectFirstStableReleaseIndex(flags)
                    if (stableIndex != null) {
                        releaseJson = releasesListJson.getJSONObject(stableIndex)
                    }
                }
            }

            if (releaseJson != null) {
                val rawTagName = releaseJson.optString("tag_name", "").trim()
                val releaseName = releaseJson.optString("name", rawTagName).ifEmpty { rawTagName }
                val changelog = releaseJson.optString("body", "Оновлення стабільності та виправлення помилок.")
                val releaseHtmlUrl = releaseJson.optString("html_url", "https://github.com/$REPO_OWNER/$REPO_NAME/releases")

                var downloadUrl: String? = null
                var expectedSha256: String? = null
                var expectedSizeBytes = 0L
                val assets = releaseJson.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url")
                            expectedSha256 = parseDigest(asset.optString("digest", ""))
                            expectedSizeBytes = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                val hasUpdate = isNewerVersion(currentVersion, rawTagName)
                val parsedVersion = parseVersionNumbers(rawTagName).joinToString(".")

                Log.i(TAG, "Update check result: current=$currentVersion, latest=$rawTagName, hasUpdate=$hasUpdate, apkUrl=$downloadUrl")
                return@withContext UpdateInfo(
                    hasUpdate = hasUpdate,
                    rawTagName = rawTagName,
                    parsedVersion = if (parsedVersion.isNotEmpty()) parsedVersion else rawTagName,
                    releaseName = releaseName,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    releaseHtmlUrl = releaseHtmlUrl,
                    expectedSha256 = expectedSha256,
                    expectedSizeBytes = expectedSizeBytes
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OTA update check: ${e.message}", e)
        }

        return@withContext UpdateInfo(
            hasUpdate = false,
            rawTagName = currentVersion,
            parsedVersion = currentVersion,
            releaseName = "Остання версія",
            changelog = "",
            downloadUrl = null,
            releaseHtmlUrl = null
        )
    }

    private fun fetchJson(urlString: String): JSONObject? =
        fetchText(urlString)?.let { JSONObject(it) }

    private fun fetchJsonArray(urlString: String): JSONArray? =
        fetchText(urlString)?.let { JSONArray(it) }

    private fun fetchText(urlString: String): String? {
        return try {
            val request = Request.Builder()
                .url(urlString)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Electros-App")
                .build()

            apiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Log.w(TAG, "HTTP GET $urlString failed with code: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch JSON from $urlString: ${e.message}")
            null
        }
    }

    /**
     * Downloads the APK file following validated HTTPS redirects (e.g. GitHub release CDN redirects).
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit,
        expectedSha256: String? = null,
        expectedSizeBytes: Long = 0L
    ): File? = withContext(Dispatchers.IO) {
        if (!isValidDownloadUrl(downloadUrl)) {
            Log.e(TAG, "Refusing to download APK from untrusted URL: $downloadUrl")
            return@withContext null
        }

        val appContext = context.applicationContext
        val tempFile = File(appContext.cacheDir, "electros_update.apk.part")
        val finalFile = File(appContext.cacheDir, "electros_update.apk")
        var currentUrl = downloadUrl
        val maxRedirects = 5

        try {
            for (redirectCount in 0..maxRedirects) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Electros-App")
                    .build()

                val redirectedTo: String? = downloadClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location")
                        val resolved = if (!location.isNullOrBlank()) {
                            response.request.url.resolve(location)?.toString()
                        } else null
                        if (resolved == null || !isValidDownloadUrl(resolved)) {
                            Log.e(TAG, "Blocked redirect to untrusted or invalid URL: $location")
                            return@withContext null
                        }
                        return@use resolved
                    }

                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed with HTTP status: $code")
                        return@withContext null
                    }

                    val body = response.body ?: return@withContext null
                    val fileLength = body.contentLength()
                    if (tempFile.exists()) tempFile.delete()

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesCopied = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesCopied += bytesRead
                                if (fileLength > 0L) {
                                    onProgress(totalBytesCopied.toFloat() / fileLength.toFloat())
                                }
                            }
                        }
                    }

                    val actualLength = if (tempFile.exists()) tempFile.length() else 0L
                    if (actualLength <= 0L) {
                        Log.e(TAG, "Downloaded APK is empty")
                        tempFile.delete()
                        return@withContext null
                    }

                    val expectedLen = if (expectedSizeBytes > 0L) expectedSizeBytes else fileLength
                    if (expectedLen > 0L && actualLength != expectedLen) {
                        Log.e(TAG, "APK size mismatch: expected $expectedLen bytes, got $actualLength. Aborting.")
                        tempFile.delete()
                        return@withContext null
                    }

                    if (expectedSha256 != null) {
                        val actualSha256 = computeSha256(tempFile)
                        if (actualSha256 == null || !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                            Log.e(TAG, "APK SHA-256 mismatch: expected $expectedSha256, got $actualSha256. Aborting.")
                            tempFile.delete()
                            return@withContext null
                        }
                    } else {
                        Log.w(TAG, "Release asset has no SHA-256 digest; relying on signing certificate verification")
                    }

                    if (!hasMatchingSigningCertificate(appContext, tempFile)) {
                        Log.e(TAG, "APK signing certificate does not match installed app. Aborting.")
                        tempFile.delete()
                        return@withContext null
                    }

                    Files.move(
                        tempFile.toPath(),
                        finalFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    return@withContext finalFile
                }

                if (redirectedTo != null) {
                    currentUrl = redirectedTo
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading APK: ${e.message}", e)
            runCatching { tempFile.delete() }
        }
        return@withContext null
    }

    /**
     * Checks install permission and launches system package installer.
     */
    fun installApk(context: Context, apkFile: File): InstallOutcome {
        val appContext = context.applicationContext
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Cannot install update. APK file does not exist or is empty.")
            return InstallOutcome.FAILED
        }
        if (!hasMatchingSigningCertificate(appContext, apkFile)) {
            Log.e(TAG, "Refusing to install APK with unverified or mismatched signing certificate.")
            apkFile.delete()
            return InstallOutcome.FAILED
        }

        if (!appContext.packageManager.canRequestPackageInstalls()) {
            return try {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:${appContext.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(settingsIntent)
                InstallOutcome.NEEDS_UNKNOWN_SOURCES_PERMISSION
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch unknown sources settings: ${e.message}")
                InstallOutcome.FAILED
            }
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        return try {
            appContext.startActivity(intent)
            Log.i(TAG, "Package Installer launched for $apkUri")
            InstallOutcome.LAUNCHED_INSTALLER
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            InstallOutcome.FAILED
        }
    }
}

