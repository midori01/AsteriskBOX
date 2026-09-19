// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import java.io.File

data class AppUpdateInfo(
    val remoteVersionCode: Int,
    val remoteVersionName: String,
    val commitTitle: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val fileSize: Long = 0L,
)

sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : AppUpdateDownloadState
    data class Downloaded(val apkFile: File) : AppUpdateDownloadState
    data class Failed(val error: String) : AppUpdateDownloadState
}
