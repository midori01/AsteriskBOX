// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import data.AndroidAppStateStore
import features.updater.AppUpdateCoordinator

@Composable
internal fun AppUpdateSynchronizer(
    stateStore: AndroidAppStateStore,
) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(stateStore) {
        val state = stateStore.state.value
        if (state.enableAppAutoUpdateCheck) {
            // Auto check on app startup: 4-hour silent throttling to avoid redundant GitHub API requests
            AppUpdateCoordinator.checkForUpdate(
                context = context,
                manual = false,
                minIntervalMs = AutoUpdateCheckIntervalMs,
            )
        }
    }
}

private const val AutoUpdateCheckIntervalMs = 4 * 60 * 60 * 1000L // 4 hours
