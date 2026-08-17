// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

abstract class UpdateResourceFileAssetsTask : DefaultTask() {
    @get:Input
    abstract val singBoxVersion: Property<String>

    @get:Input
    abstract val singBoxLocalPath: Property<String>

    @get:OutputDirectory
    abstract val singBoxCoreJniLibsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceFileAssetsDir: DirectoryProperty

    init {
        group = "resources"
        description = "Download bundled resource file assets."
    }

    @TaskAction
    fun updateAssets() {
        AndroidSingBoxAssets.forEach { asset ->
            val target = File(
                singBoxCoreJniLibsDir.get().asFile,
                "${asset.androidAbi}/libsing-box.so",
            )
            downloadAndExtractSingBox(asset, target)
        }
        AndroidResourceFileAssets.forEach { asset ->
            downloadFile(
                url = asset.url,
                target = File(resourceFileAssetsDir.get().asFile, "sing-box/${asset.fileName}"),
            )
        }
    }

    private fun downloadAndExtractSingBox(asset: SingBoxAsset, target: File) {
        if (asset.androidAbi != "arm64-v8a") {
            logger.lifecycle("Skipping ${asset.androidAbi}: midori01/sing-box only provides arm64")
            target.parentFile.mkdirs()
            target.writeBytes(ByteArray(0))
            return
        }
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
        localFile.copyTo(target, overwrite = true)
        target.setExecutable(true)
        logger.lifecycle("Copied sing-box from $localPath to ${target.absolutePath} (${target.length()} bytes)")
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

private data class SingBoxAsset(
    val androidAbi: String,
    val releaseArch: String,
)

private data class ResourceFileAsset(
    val fileName: String,
    val url: String,
)

private val AndroidSingBoxAssets = listOf(
    SingBoxAsset(
        androidAbi = "arm64-v8a",
        releaseArch = "arm64",
    ),
    SingBoxAsset(
        androidAbi = "armeabi-v7a",
        releaseArch = "arm",
    ),
    SingBoxAsset(
        androidAbi = "x86",
        releaseArch = "386",
    ),
    SingBoxAsset(
        androidAbi = "x86_64",
        releaseArch = "amd64",
    ),
)

private val AndroidResourceFileAssets = listOf(
    ResourceFileAsset(
        fileName = "geosite-category-ads-all.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
    ),
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
