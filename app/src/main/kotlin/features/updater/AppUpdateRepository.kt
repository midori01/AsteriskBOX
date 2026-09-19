// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import app.ProjectInfo
import app.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AppUpdateRepository {
    private const val GITHUB_OWNER = "midori01"
    private const val GITHUB_REPO = "AsteriskBOX"
    private const val API_BASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO"
    private const val RELEASE_DOWNLOAD_BASE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download"
    private const val RELEASE_TAG = "ci-latest"
    private const val USER_AGENT = "${ProjectInfo.PROJECT_NAME}-Updater/v${ProjectInfo.VERSION_NAME}"

    // Multi-layer caching to prevent GitHub rate limiting (60 requests/hour limit on unauthenticated calls)
    private const val CACHE_TTL_MS = 3 * 60 * 1000L // 3 minutes memory TTL
    private var lastCheckTimestamp = 0L
    private var cachedUpdateInfo: AppUpdateInfo? = null
    private var cachedETag: String? = null
    private var hasCheckedOnce = false

    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()

            // 1. In-memory cache hit: bypass network completely within TTL unless forced
            if (!forceRefresh && hasCheckedOnce && (now - lastCheckTimestamp < CACHE_TTL_MS)) {
                return@runCatching cachedUpdateInfo
            }

            var remoteVersionCode = -1
            var commitTitle = ""
            var runHtmlUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/actions"
            var downloadUrl = ""
            var fileSize = 0L
            var releaseNotes = ""

            // 2. Release-first query with HTTP ETag (304 Not Modified does not count against GitHub rate limits!)
            val releaseEndpoint = "$API_BASE/releases/tags/$RELEASE_TAG"
            val releaseConn = openGetConnection(releaseEndpoint, etag = if (forceRefresh) null else cachedETag)
            val releaseCode = releaseConn.responseCode

            if (releaseCode == 304) {
                releaseConn.disconnect()
                // Upstream has no changes since last check; GitHub consumes 0 rate limit units
                lastCheckTimestamp = now
                hasCheckedOnce = true
                return@runCatching cachedUpdateInfo
            }

            if (releaseCode in 200..299) {
                val newEtag = releaseConn.getHeaderField("ETag")
                if (!newEtag.isNullOrBlank()) {
                    cachedETag = newEtag
                }
                val releaseResponse = try {
                    releaseConn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    releaseConn.disconnect()
                }
                val releaseJson = JSONObject(releaseResponse)
                releaseNotes = releaseJson.optString("body")
                runHtmlUrl = releaseJson.optString("html_url").ifBlank { runHtmlUrl }
                commitTitle = releaseJson.optString("name").ifBlank { releaseNotes.lines().firstOrNull().orEmpty() }

                val assets = releaseJson.optJSONArray("assets")
                if (assets != null) {
                    val apkRegex = Regex("""(?:MidoriBOX|app)[-_](\d+).*?\.apk""", RegexOption.IGNORE_CASE)
                    var highestCode = -1
                    var bestUrl = ""
                    var bestSize = 0L
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            val match = apkRegex.find(assetName)
                            val code = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
                            if (code > highestCode) {
                                highestCode = code
                                bestUrl = asset.optString("browser_download_url")
                                bestSize = asset.optLong("size")
                            } else if (bestUrl.isBlank()) {
                                bestUrl = asset.optString("browser_download_url")
                                bestSize = asset.optLong("size")
                            }
                        }
                    }
                    if (highestCode <= 0) {
                        val titleMatch = Regex("""\((\d+)\)""").find(releaseJson.optString("name"))
                        highestCode = titleMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    }
                    if (highestCode > 0) {
                        remoteVersionCode = highestCode
                        downloadUrl = bestUrl
                        fileSize = bestSize
                    }
                }
            } else if (releaseCode == 403 || releaseCode == 429) {
                try {
                    handleRateLimitError(releaseConn)
                } finally {
                    releaseConn.disconnect()
                }
            } else {
                releaseConn.disconnect()
                // 3. Fallback: if ci-latest release is not yet created, query actions runs
                val runsEndpoint = "$API_BASE/actions/runs?branch=main&status=success&per_page=1"
                val runsConnection = openGetConnection(runsEndpoint)
                val runsCode = runsConnection.responseCode
                if (runsCode == 403 || runsCode == 429) {
                    try {
                        handleRateLimitError(runsConnection)
                    } finally {
                        runsConnection.disconnect()
                    }
                }
                if (runsCode in 200..299) {
                    val runsResponse = try {
                        runsConnection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        runsConnection.disconnect()
                    }
                    val runsJson = JSONObject(runsResponse)
                    val workflowRuns = runsJson.optJSONArray("workflow_runs")
                    if (workflowRuns != null && workflowRuns.length() > 0) {
                        val latestRun = workflowRuns.getJSONObject(0)
                        commitTitle = latestRun.optString("display_title").ifBlank {
                            latestRun.optString("head_sha").take(7)
                        }
                        runHtmlUrl = latestRun.optString("html_url").ifBlank { runHtmlUrl }

                        val artifactsUrl = latestRun.optString("artifacts_url")
                        if (artifactsUrl.isNotBlank()) {
                            val artifactsConn = openGetConnection(artifactsUrl)
                            val artifactsCode = artifactsConn.responseCode
                            if (artifactsCode in 200..299) {
                                val artifactsResponse = try {
                                    artifactsConn.inputStream.bufferedReader().use { it.readText() }
                                } finally {
                                    artifactsConn.disconnect()
                                }
                                val artifactsJson = JSONObject(artifactsResponse)
                                val artifactsArray = artifactsJson.optJSONArray("artifacts")
                                if (artifactsArray != null) {
                                    val versionRegex = Regex("""MidoriBOX-(\d+)""")
                                    var highestArtCode = -1
                                    for (i in 0 until artifactsArray.length()) {
                                        val art = artifactsArray.getJSONObject(i)
                                        val name = art.optString("name")
                                        val match = versionRegex.find(name)
                                        if (match != null) {
                                            val code = match.groupValues[1].toIntOrNull() ?: -1
                                            if (code > highestArtCode) {
                                                highestArtCode = code
                                            }
                                        }
                                    }
                                    if (highestArtCode > 0) {
                                        remoteVersionCode = highestArtCode
                                    }
                                }
                            } else {
                                artifactsConn.disconnect()
                            }
                        }
                    }
                } else {
                    runsConnection.disconnect()
                }
            }

            // 4. Deterministic fallback download URL
            if (downloadUrl.isBlank() && remoteVersionCode > 0) {
                downloadUrl = "$RELEASE_DOWNLOAD_BASE/$RELEASE_TAG/MidoriBOX-$remoteVersionCode-arm64-v8a-release.apk"
            }

            val result = if (remoteVersionCode > ProjectInfo.VERSION_CODE) {
                AppUpdateInfo(
                    remoteVersionCode = remoteVersionCode,
                    remoteVersionName = ProjectInfo.VERSION_NAME,
                    commitTitle = commitTitle,
                    releaseNotes = releaseNotes,
                    htmlUrl = runHtmlUrl,
                    downloadUrl = downloadUrl,
                    fileSize = fileSize,
                )
            } else {
                null
            }

            lastCheckTimestamp = now
            cachedUpdateInfo = result
            hasCheckedOnce = true
            result
        }
    }

    private fun handleRateLimitError(connection: HttpURLConnection): Nothing {
        val remaining = connection.getHeaderField("x-ratelimit-remaining")
        val resetSec = connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull()
        if (remaining == "0" && resetSec != null) {
            val nowSec = System.currentTimeMillis() / 1000
            val waitMin = ((resetSec - nowSec) / 60).coerceAtLeast(1)
            error("GitHub API 请求过于频繁（每小时限制），请在 ${waitMin} 分钟后再试")
        } else {
            error("GitHub 请求受限 (HTTP ${connection.responseCode})，请稍后再试")
        }
    }

    suspend fun downloadUpdate(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updatesDir, "MidoriBOX-${updateInfo.remoteVersionCode}.apk")
            val tempFile = File(updatesDir, "MidoriBOX-${updateInfo.remoteVersionCode}.apk.tmp")

            // Clean up any stale or previous version APKs in updates directory
            updatesDir.listFiles()?.forEach { file ->
                if (file != targetFile && file != tempFile) {
                    file.delete()
                }
            }

            // Verify integrity of existing targetFile before reusing
            if (targetFile.exists() && targetFile.length() > 0L &&
                (updateInfo.fileSize <= 0L || targetFile.length() == updateInfo.fileSize)
            ) {
                @Suppress("DEPRECATION")
                val existingArchiveInfo = context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
                if (existingArchiveInfo != null) {
                    onProgress(1f, targetFile.length(), targetFile.length())
                    return@runCatching targetFile
                } else {
                    targetFile.delete()
                }
            }

            if (tempFile.exists()) tempFile.delete()

            var currentUrl = updateInfo.downloadUrl
            var connection: HttpURLConnection? = null
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
                connection = conn

                val responseCode = conn.responseCode
                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrBlank() && redirectCount < maxRedirects) {
                        redirectCount++
                        currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                            location
                        } else {
                            URI(currentUrl).resolve(location).toString()
                        }
                        continue
                    } else {
                        error("Too many redirects (HTTP $responseCode) from download URL")
                    }
                }

                if (responseCode !in 200..299) {
                    conn.disconnect()
                    error("HTTP $responseCode: ${conn.responseMessage}")
                }
                break
            }

            val activeConnection = connection ?: error("Failed to establish download connection")
            val totalBytes = activeConnection.contentLengthLong.takeIf { it > 0L } ?: updateInfo.fileSize
            var bytesDownloaded = 0L

            try {
                activeConnection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16384)
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
                    }
                }
            } finally {
                activeConnection.disconnect()
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }

            val finalFile = if (tempFile.renameTo(targetFile)) {
                targetFile
            } else {
                try {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    targetFile
                } catch (_: Exception) {
                    tempFile
                }
            }

            @Suppress("DEPRECATION")
            val archiveInfo = context.packageManager.getPackageArchiveInfo(finalFile.absolutePath, 0)
            if (archiveInfo == null) {
                finalFile.delete()
                error("下载的安装包损坏或解析失败")
            }

            if (archiveInfo.packageName != context.packageName) {
                finalFile.delete()
                error("安装包包名不匹配 (${archiveInfo.packageName})")
            }

            finalFile
        }
    }

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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "无法启动安装程序: ${e.localizedMessage ?: e.message}",
                Toast.LENGTH_LONG,
            ).show()
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
