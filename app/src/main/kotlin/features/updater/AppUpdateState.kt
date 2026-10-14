// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

@Composable
fun rememberAppUpdateState(): AppUpdateState {
    return remember {
        AppUpdateState()
    }
}

class AppUpdateState {
    val isChecking: Boolean
        get() = AppUpdateCoordinator.isChecking

    var showSheet: Boolean
        get() = AppUpdateCoordinator.isSheetVisible
        set(value) {
            AppUpdateCoordinator.isSheetVisible = value
        }

    val updateInfo: AppUpdateInfo?
        get() = AppUpdateCoordinator.updateInfo

    val downloadState: AppUpdateDownloadState
        get() = AppUpdateCoordinator.downloadState

    fun checkForUpdate(context: Context, manual: Boolean = true) {
        AppUpdateCoordinator.checkForUpdate(context, manual = manual)
    }

    fun startDownload(context: Context) {
        AppUpdateCoordinator.startDownload(context)
    }

    fun install(context: Context, file: File? = null) {
        AppUpdateCoordinator.install(context, file)
    }

    fun dismiss() {
        AppUpdateCoordinator.dismiss()
    }
}
