// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import ui.icons.AsteriskIcons as Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import ui.components.AsteriskScaffold
import androidx.compose.material3.Text
import ui.components.AsteriskTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.LocalIsWideScreen
import app.LocalNavigator
import app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import features.updater.AppUpdateBottomSheet
import features.updater.rememberAppUpdateState
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding

@Composable
fun AboutPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val updateState = rememberAppUpdateState()

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageListPadding(
            pageContentPaddingWithCutout(
                innerPadding = innerPadding,
                outerPadding = padding,
                isWideScreen = isWideScreen,
            ),
        )
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "about_identity") { AboutIdentityHeader() }
            item(key = "about_update") {
                AboutUpdateSection(
                    onCheckUpdate = { updateState.checkForUpdate(context, manual = true) },
                )
            }
            item(key = "about_runtime") { AboutRuntimeSection() }
            item(key = "about_other") {
                AboutLinksSection(title = stringResource(R.string.about_other))
            }
        }
        AppUpdateBottomSheet(
            show = updateState.showSheet,
            updateInfo = updateState.updateInfo,
            downloadState = updateState.downloadState,
            onDismissRequest = { updateState.dismiss() },
            onStartDownload = { updateState.startDownload(context) },
            onInstall = { file -> updateState.install(context, file) },
            onOpenInBrowser = { url -> uriHandler.openUri(url) },
        )
    }
}
