// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.asterisk.zcc.abox.R
import ui.components.StringListEditor
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun ebpfEndpointConnectedBypassSummary(enabled: Boolean): String {
    return if (enabled) {
        stringResource(R.string.common_enabled)
    } else {
        stringResource(R.string.common_disabled)
    }
}

@Composable
internal fun EbpfEndpointConnectedBypassBottomSheet(
    show: Boolean,
    enabled: Boolean,
    ipCidr: List<String>,
    port: List<String>,
    onEnabledChange: (Boolean) -> Unit,
    onIpCidrChange: (List<String>) -> Unit,
    onPortChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsModalBottomSheet(
        show = show,
        title = stringResource(R.string.settings_ebpf_endpoint_connected_bypass),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                onClick = onSave,
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            item {
                SwitchPreference(
                    title = stringResource(R.string.settings_ebpf_endpoint_connected_bypass_enable),
                    icon = Icons.Rounded.PowerSettingsNew,
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
            if (enabled) {
                item {
                    StringListEditor(
                        editorKey = "ebpf-endpoint-bypass-ip:$show",
                        title = stringResource(R.string.settings_ebpf_endpoint_connected_bypass_ip_cidr),
                        values = ipCidr,
                        onValuesChange = onIpCidrChange,
                        emptyText = stringResource(R.string.settings_ebpf_endpoint_connected_bypass_ip_cidr_empty),
                    )
                }
                item {
                    StringListEditor(
                        editorKey = "ebpf-endpoint-bypass-port:$show",
                        title = stringResource(R.string.settings_ebpf_endpoint_connected_bypass_port),
                        values = port,
                        onValuesChange = onPortChange,
                        emptyText = stringResource(R.string.settings_ebpf_endpoint_connected_bypass_port_empty),
                    )
                }
            }
        }
    }
}
