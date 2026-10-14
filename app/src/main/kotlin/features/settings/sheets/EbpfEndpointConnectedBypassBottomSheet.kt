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
import app.R
import engine.network.isCidrAddress
import engine.network.isIpAddress
import engine.network.toPortOrNull
import ui.components.StringListEditor
import ui.icons.AsteriskIcons as Icons

internal fun sanitizeEbpfEndpointConnectedBypassIpCidr(values: List<String>): List<String> =
    values.map(String::trim).filter { it.isNotEmpty() && (isCidrAddress(it) || isIpAddress(it)) }.distinct()

internal fun sanitizeEbpfEndpointConnectedBypassPort(values: List<String>): List<String> =
    values.mapNotNull(String::toPortOrNull).distinct().map(Int::toString)

// sing-box rejects an enabled endpoint_connected_bypass whose ip_cidr or port list is empty.
internal fun canSaveEbpfEndpointConnectedBypass(
    enabled: Boolean,
    ipCidr: List<String>,
    port: List<String>,
): Boolean = !enabled ||
    (sanitizeEbpfEndpointConnectedBypassIpCidr(ipCidr).isNotEmpty() &&
        sanitizeEbpfEndpointConnectedBypassPort(port).isNotEmpty())

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
    val invalidIpMessage = stringResource(R.string.settings_ebpf_local_bypass_ip_cidr_invalid)
    val invalidPortMessage = stringResource(R.string.settings_ebpf_local_bypass_port_invalid)
    val canSave = canSaveEbpfEndpointConnectedBypass(enabled, ipCidr, port)

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
                enabled = canSave,
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
                        modifier = Modifier.padding(top = 12.dp),
                        validateInput = { value ->
                            if (isCidrAddress(value) || isIpAddress(value)) null else invalidIpMessage
                        },
                    )
                }
                item {
                    StringListEditor(
                        editorKey = "ebpf-endpoint-bypass-port:$show",
                        title = stringResource(R.string.settings_ebpf_endpoint_connected_bypass_port),
                        values = port,
                        onValuesChange = onPortChange,
                        emptyText = stringResource(R.string.settings_ebpf_endpoint_connected_bypass_port_empty),
                        modifier = Modifier.padding(top = 12.dp),
                        validateInput = { value ->
                            if (value.toPortOrNull() != null) null else invalidPortMessage
                        },
                    )
                }
            }
        }
    }
}
