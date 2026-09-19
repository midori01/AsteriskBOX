// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import app.ProjectInfo
import app.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppUpdateCoordinator {
    var isChecking by mutableStateOf(false)
        private set

    var isSheetVisible by mutableStateOf(false)

    var updateInfo by mutableStateOf<AppUpdateInfo?>(null)
        private set

    var downloadState by mutableStateOf<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
        private set

    // Application-scoped supervisor scope ensures active downloads survive screen navigation and tab switches
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null

    fun checkForUpdate(
        context: Context,
        manual: Boolean = true,
        minIntervalMs: Long = 0L,
    ) {
        if (isChecking) return
        isChecking = true
        if (manual) {
            Toast.makeText(context, context.getString(R.string.update_checking), Toast.LENGTH_SHORT).show()
        }
        scope.launch {
            val result = AppUpdateRepository.checkForUpdate(
                context = context,
                forceRefresh = manual,
                minIntervalMs = minIntervalMs,
            )
            isChecking = false
            result.onSuccess { info ->
                if (info != null) {
                    updateInfo = info
                    if (downloadState !is AppUpdateDownloadState.Downloaded) {
                        downloadState = AppUpdateDownloadState.Idle
                    }
                    isSheetVisible = true
                } else if (manual) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.update_already_latest,
                            "v${ProjectInfo.VERSION_NAME} (${ProjectInfo.VERSION_CODE})",
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                if (manual) {
                    val msg = error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: error::class.simpleName
                        ?: "Network error"
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_check_failed, msg),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun startDownload(context: Context) {
        val info = updateInfo ?: return
        if (downloadState is AppUpdateDownloadState.Downloading) return

        downloadState = AppUpdateDownloadState.Downloading(0f, 0L, info.fileSize)
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val result = AppUpdateRepository.downloadUpdate(context, info) { progress, bytes, total ->
                downloadState = AppUpdateDownloadState.Downloading(progress, bytes, total)
            }
            result.onSuccess { file ->
                downloadState = AppUpdateDownloadState.Downloaded(file)
                install(context, file)
            }.onFailure { error ->
                downloadState = AppUpdateDownloadState.Failed(error.localizedMessage ?: "Download failed")
            }
        }
    }

    fun install(context: Context, file: File? = null) {
        val target = file ?: (downloadState as? AppUpdateDownloadState.Downloaded)?.apkFile ?: return
        AppUpdateRepository.installApk(context, target)
    }

    fun dismiss() {
        isSheetVisible = false
    }

    fun show() {
        if (updateInfo != null) {
            isSheetVisible = true
        }
    }
}

/**
 * Global update bottom sheet host mounted at app content level.
 * Automatically displays the update sheet whenever [AppUpdateCoordinator.isSheetVisible] is true.
 */
@Composable
fun AppUpdateHost() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    AppUpdateBottomSheet(
        show = AppUpdateCoordinator.isSheetVisible,
        updateInfo = AppUpdateCoordinator.updateInfo,
        downloadState = AppUpdateCoordinator.downloadState,
        onDismissRequest = { AppUpdateCoordinator.dismiss() },
        onStartDownload = { AppUpdateCoordinator.startDownload(context) },
        onInstall = { file -> AppUpdateCoordinator.install(context, file) },
        onOpenInBrowser = { url -> uriHandler.openUri(url) },
    )
}
