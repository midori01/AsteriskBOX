// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

abstract class UpdateResourceFileAssetsTask : DefaultTask() {
    @get:Input
    abstract val singBoxVersion: Property<String>

    @get:Input
    abstract val singBoxLocalPath: Property<String>

    @get:OutputDirectory
    abstract val resourceFileAssetsDir: DirectoryProperty

    init {
        group = "resources"
        description = "Download bundled resource file assets."
    }

    @TaskAction
    fun updateAssets() {
        val singBoxTarget = File(resourceFileAssetsDir.get().asFile, "sing-box/sing-box.xz")
        prepareSingBoxCoreAsset(singBoxTarget)

        AndroidResourceFileAssets.forEach { asset ->
            downloadFile(
                url = asset.url,
                target = File(resourceFileAssetsDir.get().asFile, "sing-box/${asset.fileName}"),
            )
        }
    }

    private fun prepareSingBoxCoreAsset(target: File) {
        val localPath = singBoxLocalPath.get().takeIf { it.isNotBlank() }
            ?: throw GradleException("Missing singbox.local property. Build sing-box first or pass -Psingbox.local=<path>")
        val localFile = File(localPath)
        if (!localFile.isFile) {
            throw GradleException("Local sing-box binary not found: $localPath")
        }
        if (target.exists() && !target.delete()) {
            throw GradleException("Unable to replace ${target.absolutePath}")
        }
        target.parentFile.mkdirs()
        if (localFile.name.endsWith(".xz")) {
            localFile.copyTo(target, overwrite = true)
            logger.lifecycle("Copied pre-compressed sing-box from $localPath to ${target.absolutePath} (${target.length()} bytes)")
            return
        }

        logger.lifecycle("Compressing sing-box binary from $localPath (${localFile.length()} bytes) with XZ LZMA2...")
        val temporary = target.resolveSibling("${target.name}.tmp")
        temporary.delete()
        try {
            val options = LZMA2Options(LZMA2Options.PRESET_MAX)
            localFile.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { rawOut ->
                    XZOutputStream(rawOut, options).use { xzOut ->
                        input.copyTo(xzOut)
                    }
                }
            }
            if (!temporary.renameTo(target)) {
                throw GradleException("Unable to move ${temporary.absolutePath} to ${target.absolutePath}")
            }
            logger.lifecycle("Compressed sing-box with XZ from ${localFile.length()} bytes to ${target.length()} bytes (${target.absolutePath})")
        } finally {
            temporary.delete()
        }
    }

    private fun downloadFile(url: String, target: File) {
        if (useExistingFile(target)) return
        target.parentFile.mkdirs()
        val temporary = target.resolveSibling("${target.name}.tmp")
        temporary.delete()
        try {
            downloadToFile(url, temporary)
            if (target.exists() && !target.delete()) {
                throw GradleException("Unable to replace ${target.absolutePath}")
            }
            if (!temporary.renameTo(target)) {
                throw GradleException("Unable to move ${temporary.absolutePath} to ${target.absolutePath}")
            }
            logger.lifecycle("Updated ${target.absolutePath} (${target.length()} bytes)")
        } finally {
            temporary.delete()
        }
    }

    private fun useExistingFile(target: File): Boolean {
        if (!target.isFile) return false
        logger.lifecycle("Using existing ${target.absolutePath} (${target.length()} bytes)")
        return true
    }

    private fun downloadToFile(url: String, target: File) {
        logger.lifecycle("Downloading $url")
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MidoriBOX-Gradle")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw GradleException("Failed to download $url: HTTP $code")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (target.length() <= 0L) {
            throw GradleException("Downloaded file is empty: $url")
        }
    }
}

private data class ResourceFileAsset(
    val fileName: String,
    val url: String,
)

private val AndroidResourceFileAssets = listOf(
    ResourceFileAsset(
        fileName = "geosite-google.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
    ),
    ResourceFileAsset(
        fileName = "geosite-cn.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
    ),
    ResourceFileAsset(
        fileName = "geoip-cn.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
    ),
    ResourceFileAsset(
        fileName = "direct-cidr-v4.txt",
        url = "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute.txt",
    ),
    ResourceFileAsset(
        fileName = "direct-cidr-v6.txt",
        url = "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute_v6.txt",
    ),
)
