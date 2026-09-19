// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ProjectInfo
import app.R
import features.settings.sheets.SettingsModalBottomSheet
import features.settings.sheets.TextButton
import java.io.File
import java.util.Locale
import ui.icons.AsteriskIcons as Icons

@Composable
fun AppUpdateBottomSheet(
    show: Boolean,
    updateInfo: AppUpdateInfo?,
    downloadState: AppUpdateDownloadState,
    onDismissRequest: () -> Unit,
    onStartDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onOpenInBrowser: (String) -> Unit,
) {
    if (updateInfo == null) return

    val isDownloading = downloadState is AppUpdateDownloadState.Downloading

    SettingsModalBottomSheet(
        show = show,
        dismissEnabled = !isDownloading,
        title = stringResource(R.string.update_dialog_title),
        startAction = {
            TextButton(
                text = stringResource(R.string.update_btn_view_action),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                enabled = !isDownloading,
                onClick = { onOpenInBrowser(updateInfo.htmlUrl) },
            )
        },
        endAction = {
            when (downloadState) {
                is AppUpdateDownloadState.Downloaded -> {
                    TextButton(
                        text = stringResource(R.string.update_install),
                        icon = Icons.Rounded.CheckCircle,
                        onClick = { onInstall(downloadState.apkFile) },
                    )
                }
                is AppUpdateDownloadState.Downloading -> {
                    val progressText = if (downloadState.totalBytes > 0L) {
                        stringResource(
                            R.string.update_downloading,
                            (downloadState.progress * 100).toInt(),
                        )
                    } else {
                        stringResource(R.string.update_downloading_indeterminate)
                    }
                    TextButton(
                        text = progressText,
                        icon = Icons.Rounded.Download,
                        enabled = false,
                        onClick = {},
                    )
                }
                is AppUpdateDownloadState.Failed -> {
                    TextButton(
                        text = stringResource(R.string.common_retry),
                        icon = Icons.Rounded.Refresh,
                        onClick = onStartDownload,
                    )
                }
                else -> {
                    TextButton(
                        text = stringResource(R.string.update_btn_now),
                        icon = Icons.Rounded.Download,
                        onClick = onStartDownload,
                    )
                }
            }
        },
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Version Info Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.update_new_version,
                            "v${updateInfo.remoteVersionName} (${updateInfo.remoteVersionCode})",
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Text(
                    text = stringResource(
                        R.string.update_current_version,
                        "v${ProjectInfo.VERSION_NAME} (${ProjectInfo.VERSION_CODE})",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Commit/Build Details & Release Notes
            val detailText = when {
                updateInfo.releaseNotes.isNotBlank() && updateInfo.commitTitle.isNotBlank() &&
                    !updateInfo.releaseNotes.contains(updateInfo.commitTitle) ->
                    "${updateInfo.commitTitle}\n\n${updateInfo.releaseNotes}".trim()
                updateInfo.releaseNotes.isNotBlank() -> updateInfo.releaseNotes.trim()
                else -> updateInfo.commitTitle.trim()
            }
            if (detailText.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(14.dp),
                ) {
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Download Progress / States
            when (downloadState) {
                is AppUpdateDownloadState.Downloading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (downloadState.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { downloadState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            if (downloadState.totalBytes > 0L) {
                                val percent = (downloadState.progress * 100).toInt()
                                Text(
                                    text = stringResource(R.string.update_downloading, percent),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val currentMb = downloadState.bytesDownloaded / (1024f * 1024f)
                                val totalMb = downloadState.totalBytes / (1024f * 1024f)
                                Text(
                                    text = String.format(Locale.US, "%.1f / %.1f MB", currentMb, totalMb),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.update_downloading_indeterminate),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val currentMb = downloadState.bytesDownloaded / (1024f * 1024f)
                                Text(
                                    text = String.format(Locale.US, "%.1f MB", currentMb),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                is AppUpdateDownloadState.Downloaded -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.update_install),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is AppUpdateDownloadState.Failed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.update_download_failed, downloadState.error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                AppUpdateDownloadState.Idle -> {
                    // Nothing extra
                }
            }
        }
    }
}
