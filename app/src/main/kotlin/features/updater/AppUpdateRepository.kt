// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import app.ProjectInfo
import app.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AppUpdateRepository {
    private const val GITHUB_OWNER = "midori01"
    private const val GITHUB_REPO = "AsteriskBOX"
    private const val API_BASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO"
    private const val RELEASE_TAG = "ci-latest"
    private const val USER_AGENT = "${ProjectInfo.PROJECT_NAME}-Updater/v${ProjectInfo.VERSION_NAME}"

    private const val PREFS_NAME = "app_update_cache"
    private const val KEY_ETAG = "cached_etag"
    private const val KEY_LAST_CHECK = "last_check_timestamp"
    private const val KEY_REMOTE_CODE = "remote_version_code"
    private const val KEY_REMOTE_NAME = "remote_version_name"
    private const val KEY_COMMIT_TITLE = "commit_title"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_HTML_URL = "html_url"
    private const val KEY_DOWNLOAD_URL = "download_url"
    private const val KEY_FILE_SIZE = "file_size"
    private const val KEY_SHA256 = "sha256"

    // Exactly the asset name CI uploads (see .github/workflows/ci.yml).
    private val ReleaseApkAssetRegex = Regex("""MidoriBOX-(\d+)-arm64-v8a-release\.apk""")
    private val Sha256HexRegex = Regex("[0-9a-f]{64}")

    private data class ReleaseApkAsset(
        val versionCode: Int,
        val downloadUrl: String,
        val size: Long,
        val sha256: String,
    )

    // Multi-layer caching to prevent GitHub rate limiting (60 requests/hour limit on unauthenticated calls)
    private const val MEMORY_CACHE_TTL_MS = 3 * 60 * 1000L // 3 minutes in-memory TTL
    private var lastCheckTimestamp = 0L
    private var cachedUpdateInfo: AppUpdateInfo? = null
    private var cachedETag: String? = null
    private var hasInitializedCache = false

    suspend fun checkForUpdate(
        context: Context? = null,
        forceRefresh: Boolean = false,
        minIntervalMs: Long = 0L,
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Restore from SharedPreferences on first run after cold boot
            if (!hasInitializedCache && prefs != null) {
                cachedETag = prefs.getString(KEY_ETAG, null)
                lastCheckTimestamp = prefs.getLong(KEY_LAST_CHECK, 0L)
                val savedCode = prefs.getInt(KEY_REMOTE_CODE, -1)
                if (savedCode > ProjectInfo.VERSION_CODE) {
                    cachedUpdateInfo = AppUpdateInfo(
                        remoteVersionCode = savedCode,
                        remoteVersionName = prefs.getString(KEY_REMOTE_NAME, "") ?: "",
                        commitTitle = prefs.getString(KEY_COMMIT_TITLE, "") ?: "",
                        releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "") ?: "",
                        htmlUrl = prefs.getString(KEY_HTML_URL, "") ?: "",
                        downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, "") ?: "",
                        fileSize = prefs.getLong(KEY_FILE_SIZE, 0L),
                        sha256 = prefs.getString(KEY_SHA256, "") ?: "",
                    )
                } else {
                    // A cached ETag without a matching cached body would turn every 304 into "no update".
                    cachedETag = null
                }
                hasInitializedCache = true
            }

            // 1. Throttling and cache hit check: bypass network completely within TTL unless forced.
            // A timestamp in the future (clock moved back) is treated as expired rather than blocking checks.
            val effectiveTtl = if (minIntervalMs > 0L) minIntervalMs else MEMORY_CACHE_TTL_MS
            val elapsed = now - lastCheckTimestamp
            if (!forceRefresh && elapsed in 0 until effectiveTtl) {
                return@runCatching cachedUpdateInfo
            }

            // 2. Query release with HTTP ETag (conditional requests avoid re-downloading an unchanged body)
            val releaseEndpoint = "$API_BASE/releases/tags/$RELEASE_TAG"
            val releaseConn = openGetConnection(releaseEndpoint, etag = if (forceRefresh) null else cachedETag)
            val releaseCode = releaseConn.responseCode

            if (releaseCode == 304) {
                releaseConn.disconnect()
                lastCheckTimestamp = now
                prefs?.edit { putLong(KEY_LAST_CHECK, now) }
                return@runCatching cachedUpdateInfo
            }
            if (releaseCode == 403 || releaseCode == 429) {
                try {
                    handleRateLimitError(context, releaseConn)
                } finally {
                    releaseConn.disconnect()
                }
            }
            if (releaseCode !in 200..299) {
                releaseConn.disconnect()
                val msg = context?.getString(R.string.update_error_request_failed, releaseCode)
                    ?: "GitHub request failed (HTTP $releaseCode)"
                error(msg)
            }

            val newEtag = releaseConn.getHeaderField("ETag")
            val releaseResponse = try {
                releaseConn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                releaseConn.disconnect()
            }
            val releaseJson = JSONObject(releaseResponse)
            val releaseNotes = releaseJson.optString("body")
            val runHtmlUrl = releaseJson.optString("html_url")
                .ifBlank { "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$RELEASE_TAG" }
            val commitTitle = releaseJson.optString("name").ifBlank { releaseNotes.lines().firstOrNull().orEmpty() }

            // Only assets named exactly as CI publishes them are candidates; the version code comes from the
            // same asset whose URL is downloaded, so the advertised version always matches the file.
            var best: ReleaseApkAsset? = null
            val assets = releaseJson.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val code = ReleaseApkAssetRegex.matchEntire(asset.optString("name"))
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: continue
                    val url = asset.optString("browser_download_url")
                    if (!url.startsWith("https://")) continue
                    if (best == null || code > best.versionCode) {
                        best = ReleaseApkAsset(
                            versionCode = code,
                            downloadUrl = url,
                            size = asset.optLong("size"),
                            sha256 = asset.optString("digest").removePrefix("sha256:").lowercase()
                                .takeIf { it.matches(Sha256HexRegex) }
                                .orEmpty(),
                        )
                    }
                }
            }

            val result = best?.takeIf { it.versionCode > ProjectInfo.VERSION_CODE }?.let { asset ->
                AppUpdateInfo(
                    remoteVersionCode = asset.versionCode,
                    remoteVersionName = "",
                    commitTitle = commitTitle,
                    releaseNotes = releaseNotes,
                    htmlUrl = runHtmlUrl,
                    downloadUrl = asset.downloadUrl,
                    fileSize = asset.size,
                    sha256 = asset.sha256,
                )
            }

            // Commit the ETag only together with the body it describes.
            if (!newEtag.isNullOrBlank()) cachedETag = newEtag
            lastCheckTimestamp = now
            cachedUpdateInfo = result
            hasInitializedCache = true

            // Persist to SharedPreferences
            prefs?.edit {
                putString(KEY_ETAG, cachedETag)
                putLong(KEY_LAST_CHECK, now)
                if (result != null) {
                    putInt(KEY_REMOTE_CODE, result.remoteVersionCode)
                    putString(KEY_REMOTE_NAME, result.remoteVersionName)
                    putString(KEY_COMMIT_TITLE, result.commitTitle)
                    putString(KEY_RELEASE_NOTES, result.releaseNotes)
                    putString(KEY_HTML_URL, result.htmlUrl)
                    putString(KEY_DOWNLOAD_URL, result.downloadUrl)
                    putLong(KEY_FILE_SIZE, result.fileSize)
                    putString(KEY_SHA256, result.sha256)
                } else {
                    remove(KEY_REMOTE_CODE)
                }
            }

            result
        }
    }

    private fun handleRateLimitError(context: Context?, connection: HttpURLConnection): Nothing {
        val remaining = connection.getHeaderField("x-ratelimit-remaining")
        val resetSec = connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull()
        if (remaining == "0" && resetSec != null) {
            val nowSec = System.currentTimeMillis() / 1000
            val waitMin = ((resetSec - nowSec) / 60).coerceAtLeast(1)
            val msg = context?.getString(R.string.update_error_rate_limited, waitMin)
                ?: "GitHub API rate limit exceeded. Please try again in $waitMin minutes"
            error(msg)
        } else {
            val code = connection.responseCode
            val msg = context?.getString(R.string.update_error_request_failed, code)
                ?: "GitHub request failed (HTTP $code)"
            error(msg)
        }
    }

    suspend fun downloadUpdate(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // App-private storage: on API 26-28 other apps with storage permission can rewrite
            // Android/data, which would let them swap the APK between verification and install.
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updatesDir, "MidoriBOX-${updateInfo.remoteVersionCode}.apk")
            val tempFile = File(updatesDir, "MidoriBOX-${updateInfo.remoteVersionCode}.apk.tmp")

            // Clean up any stale or previous version APKs in updates directory
            updatesDir.listFiles()?.forEach { file ->
                if (file != targetFile && file != tempFile) {
                    file.delete()
                }
            }

            // Clean the legacy external download location used by earlier builds
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { file ->
                if (file.name.startsWith("MidoriBOX-") && file.name.contains(".apk")) file.delete()
            }

            // Reuse an existing file only if it passes the same verification as a fresh download
            if (targetFile.exists() && targetFile.length() > 0L) {
                if (runCatching { verifyDownloadedApk(context, targetFile, updateInfo) }.isSuccess) {
                    onProgress(1f, targetFile.length(), targetFile.length())
                    return@runCatching targetFile
                }
                targetFile.delete()
            }

            if (tempFile.exists()) tempFile.delete()

            val activeConnection = establishDownloadConnection(updateInfo.downloadUrl)
            val totalBytes = activeConnection.contentLengthLong.takeIf { it > 0L } ?: updateInfo.fileSize
            var bytesDownloaded = 0L

            try {
                activeConnection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            val progress = if (totalBytes > 0L) {
                                (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            onProgress(progress, bytesDownloaded, totalBytes)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            } finally {
                activeConnection.disconnect()
            }

            try {
                verifyDownloadedApk(context, tempFile, updateInfo)
            } catch (error: Throwable) {
                tempFile.delete()
                throw error
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                error(context.getString(R.string.update_error_corrupt_apk))
            }
            targetFile
        }
    }

    /** Throws with a localized message unless [apk] is the expected, same-signer build of this app. */
    private fun verifyDownloadedApk(context: Context, apk: File, updateInfo: AppUpdateInfo) {
        if (updateInfo.fileSize > 0L && apk.length() != updateInfo.fileSize) {
            error(context.getString(R.string.update_error_corrupt_apk))
        }
        if (updateInfo.sha256.isNotEmpty() && apk.sha256Hex() != updateInfo.sha256) {
            error(context.getString(R.string.update_error_checksum_mismatch))
        }
        val pm = context.packageManager
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archiveInfo = pm.getPackageArchiveInfo(apk.absolutePath, signingFlags)
            ?: error(context.getString(R.string.update_error_corrupt_apk))
        if (archiveInfo.packageName != context.packageName) {
            error(context.getString(R.string.update_error_package_mismatch, archiveInfo.packageName))
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
        if (archiveVersionCode != updateInfo.remoteVersionCode.toLong()) {
            error(
                context.getString(
                    R.string.update_error_version_mismatch,
                    archiveVersionCode.toInt(),
                    updateInfo.remoteVersionCode,
                ),
            )
        }
        @Suppress("DEPRECATION")
        val installedInfo = pm.getPackageInfo(context.packageName, signingFlags)
        // Key rotation keeps the old certificate in the new APK's history, so overlap is the right test.
        val archiveSigners = archiveInfo.signerDigests()
        if (archiveSigners.isEmpty() || archiveSigners.intersect(installedInfo.signerDigests()).isEmpty()) {
            error(context.getString(R.string.update_error_signature_mismatch))
        }
    }

    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            this.signatures
        } ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature -> digest.digest(signature.toByteArray()).toHex() }.toSet()
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_permission_install_unknown),
                        Toast.LENGTH_LONG,
                    ).show()
                    val manageIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(manageIntent)
                    } catch (_: Exception) {
                        val fallback = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallback)
                    }
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("package-archive", apkUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Grant read access to this one APK URI only to the installer(s) that resolve the intent.
            val resolvedActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            for (resolveInfo in resolvedActivities) {
                val pkg = resolveInfo.activityInfo?.packageName ?: continue
                runCatching { context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            val err = e.localizedMessage ?: e.message ?: "Unknown error"
            val msg = context.getString(R.string.update_error_cannot_install, err)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun establishDownloadConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirectCount = 0
        val maxRedirects = 5

        while (true) {
            val conn = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }

            val responseCode = conn.responseCode
            if (responseCode in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank() && redirectCount < maxRedirects) {
                    redirectCount++
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    if (!currentUrl.startsWith("https://")) error("Refusing non-HTTPS redirect for update download")
                    continue
                } else {
                    error("Too many redirects (HTTP $responseCode) from download URL")
                }
            }

            if (responseCode !in 200..299) {
                conn.disconnect()
                error("HTTP $responseCode: ${conn.responseMessage}")
            }
            return conn
        }
    }

    private fun openGetConnection(url: String, etag: String? = null): HttpURLConnection {
        return (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (!etag.isNullOrBlank()) {
                setRequestProperty("If-None-Match", etag)
            }
        }
    }
}
