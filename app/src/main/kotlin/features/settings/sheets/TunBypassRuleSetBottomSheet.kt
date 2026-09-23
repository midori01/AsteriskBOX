// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import engine.network.isCidrAddress
import engine.network.isIpAddress
import ui.components.ReferenceSelectionCard
import ui.components.StringListEditor
import ui.icons.AsteriskIcons as Icons
import ui.text.formatTemplate

internal fun sanitizeTunBypassRuleSetTags(tags: List<String>): List<String> =
    tags.map(String::trim).filter(String::isNotEmpty).distinct()

internal fun toggleTunBypassRuleSetTag(
    tags: List<String>,
    tag: String,
): List<String> =
    if (tag in tags) tags.filterNot { value -> value == tag } else tags + tag

internal fun sanitizeIpCidrList(values: List<String>): List<String> =
    values.map(String::trim).filter { it.isNotEmpty() && (isCidrAddress(it) || isIpAddress(it)) }.distinct()

internal fun sanitizePortList(values: List<String>): List<String> =
    values.mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.distinct().map { it.toString() }

@Composable
internal fun tunBypassRuleSetSummary(
    selectedTags: List<String>,
    choices: List<Pair<String, String>>,
    bypassPrivateAddress: Boolean = true,
    ipCidr: List<String> = emptyList(),
    port: List<String> = emptyList(),
): String {
    val selected = sanitizeTunBypassRuleSetTags(selectedTags)
    val labels = choices.toMap()
    val unavailable = stringResource(R.string.common_unavailable)
    val items = mutableListOf<String>()
    if (selected.isNotEmpty()) {
        val selectedLabels = selected.map { tag -> labels[tag] ?: unavailable }
        items.add(selectedLabels.joinToString())
    }
    if (bypassPrivateAddress) {
        items.add(stringResource(R.string.settings_ebpf_local_bypass_private_address_short))
    }
    if (ipCidr.isNotEmpty()) {
        items.add("${ipCidr.size} CIDR")
    }
    if (port.isNotEmpty()) {
        items.add("${port.size} Port")
    }
    if (items.isEmpty()) {
        return stringResource(R.string.settings_tun_bypass_rule_sets_summary_none)
    }
    return items.joinToString(" · ")
}

@Composable
internal fun TunBypassRuleSetBottomSheet(
    show: Boolean,
    saving: Boolean,
    choices: List<Pair<String, String>>,
    selectedTags: List<String>,
    bypassPrivateAddress: Boolean,
    ipCidr: List<String>,
    port: List<String>,
    onSelectedTagsChange: (List<String>) -> Unit,
    onBypassPrivateAddressChange: (Boolean) -> Unit,
    onIpCidrChange: (List<String>) -> Unit,
    onPortChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    val selected = sanitizeTunBypassRuleSetTags(selectedTags)
    val invalidIpMessage = stringResource(R.string.settings_ebpf_local_bypass_ip_cidr_invalid)
    val invalidPortMessage = stringResource(R.string.settings_ebpf_local_bypass_port_invalid)

    SettingsModalBottomSheet(
        show = show,
        dismissEnabled = !saving,
        title = stringResource(R.string.settings_root_ebpf_bypass_direct_cidrs),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
                enabled = !saving,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                onClick = onSave,
                enabled = !saving,
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
                ReferenceSelectionCard(
                    title = stringResource(R.string.settings_tun_bypass_rule_sets_picker_title),
                    emptyText = stringResource(R.string.routing_rule_sets_empty),
                    choices = choices,
                    selected = selected.toSet(),
                    onToggle = { tag ->
                        onSelectedTagsChange(toggleTunBypassRuleSetTag(selected, tag))
                    },
                    enabled = !saving,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.settings_tun_bypass_rule_sets_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                SwitchPreference(
                    title = stringResource(R.string.settings_ebpf_local_bypass_private_address),
                    icon = Icons.Rounded.HomeWork,
                    summary = stringResource(R.string.settings_ebpf_local_bypass_private_address_summary),
                    checked = bypassPrivateAddress,
                    onCheckedChange = onBypassPrivateAddressChange,
                )
            }
            item {
                StringListEditor(
                    editorKey = "tun-bypass-direct-ip:$show",
                    title = stringResource(R.string.settings_ebpf_local_bypass_ip_cidr),
                    description = stringResource(R.string.settings_ebpf_local_bypass_ip_cidr_summary),
                    values = ipCidr,
                    onValuesChange = onIpCidrChange,
                    emptyText = stringResource(R.string.settings_ebpf_local_bypass_ip_cidr_empty),
                    validateInput = {
                        val trimmed = it.trim()
                        if (isCidrAddress(trimmed) || isIpAddress(trimmed)) null else invalidIpMessage
                    },
                )
            }
            item {
                StringListEditor(
                    editorKey = "tun-bypass-direct-port:$show",
                    title = stringResource(R.string.settings_ebpf_local_bypass_port),
                    description = stringResource(R.string.settings_ebpf_local_bypass_port_summary),
                    values = port,
                    onValuesChange = onPortChange,
                    emptyText = stringResource(R.string.settings_ebpf_local_bypass_port_empty),
                    validateInput = {
                        val p = it.trim().toIntOrNull()
                        if (p != null && p in 1..65535) null else invalidPortMessage
                    },
                )
            }
        }
    }
}
