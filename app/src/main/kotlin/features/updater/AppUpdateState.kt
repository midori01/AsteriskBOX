// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.ProjectInfo
import app.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun rememberAppUpdateState(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
): AppUpdateState {
    return remember(coroutineScope) {
        AppUpdateState(coroutineScope)
    }
}

class AppUpdateState(
    private val scope: CoroutineScope,
) {
    var isChecking by mutableStateOf(false)
        private set

    var showSheet by mutableStateOf(false)

    var updateInfo by mutableStateOf<AppUpdateInfo?>(null)
        private set

    var downloadState by mutableStateOf<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
        private set

    fun checkForUpdate(context: Context, manual: Boolean = true) {
        if (isChecking) return
        isChecking = true
        if (manual) {
            Toast.makeText(context, context.getString(R.string.update_checking), Toast.LENGTH_SHORT).show()
        }
        scope.launch {
            val result = AppUpdateRepository.checkForUpdate(forceRefresh = manual)
            isChecking = false
            result.onSuccess { info ->
                if (info != null) {
                    updateInfo = info
                    downloadState = AppUpdateDownloadState.Idle
                    showSheet = true
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
        scope.launch {
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
        if (downloadState !is AppUpdateDownloadState.Downloading) {
            showSheet = false
        }
    }
}
