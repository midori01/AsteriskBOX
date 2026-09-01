// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.AppState
import app.LocalAppServices
import app.modes.RunModeTproxy
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.settings.sheets.EbpfSharedNetworkBottomSheet
import features.settings.sheets.EbpfEndpointConnectedBypassBottomSheet
import features.settings.sheets.EbpfBypassRuleSetBottomSheet
import features.settings.sheets.ExternalInterfacesBottomSheet
import features.settings.sheets.IgnoredInterfacesBottomSheet
import features.settings.sheets.LocalProxySettingsBottomSheet
import features.settings.sheets.PrivateAddressBottomSheet
import features.settings.sheets.SnifferSettingsBottomSheet
import features.settings.sheets.ServiceControlBottomSheet
import features.settings.sheets.sanitizeEbpfSharedNetworkInterfaces
import features.settings.sheets.sanitizeEbpfBypassRuleSetTags
import features.settings.sheets.sanitizeExternalInterfaces
import features.settings.sheets.sanitizeIgnoredInterfaceSelectors
import features.settings.sheets.sanitizePrivateAddressCidrs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R

@Composable
internal fun SettingsBottomSheetsHost(
    appState: AppState,
    sheetState: SettingsSheetState,
    ebpfBypassRuleSetChoices: List<Pair<String, String>>,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    val context = LocalContext.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val validationFailedMessage = stringResource(R.string.settings_sing_box_validation_failed)
    var validating by remember { mutableStateOf(false) }
    var serviceControlSaving by remember { mutableStateOf(false) }
    var serviceControlError by remember { mutableStateOf<String?>(null) }
    val applyServiceControl = LocalAppServices.current.applyServiceControlUseCase
    val serviceControlFailedMessage = stringResource(R.string.settings_service_control_save_failed)

    fun validateAndCommit(
        operation: String,
        transform: (AppState) -> AppState,
        close: () -> Unit,
    ) {
        if (validating) return
        val baseState = appState
        val candidateState = transform(baseState)
        validating = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    validateSingBoxRuntimeConfiguration(context, candidateState)
                }
                var committed = false
                updateAppState { current ->
                    if (current === baseState) {
                        committed = true
                        candidateState
                    } else {
                        current
                    }
                }
                if (committed) {
                    close()
                } else {
                    tipNotifier.show(validationFailedMessage)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure(
                    context = FailureLogContext(
                        operation = operation,
                        stage = "validate",
                    ),
                    error = error,
                )
                tipNotifier.show(validationFailedMessage)
            } finally {
                validating = false
            }
        }
    }

    LocalProxySettingsBottomSheet(
        show = sheetState.showLocalProxySettings,
        saving = validating,
        showInboundProxyPort = appState.runMode == RunModeTproxy,
        lockInboundProxyPort = appState.runMode == RunModeTproxy && appState.proxyRunning,
        inboundProxyPort = sheetState.localProxySettingsDraft.transparentProxyPort,
        port = sheetState.localProxySettingsDraft.port,
        enableDynamicPort = sheetState.localProxySettingsDraft.enableDynamicPort,
        listenAllInterfaces = sheetState.localProxySettingsDraft.listenAllInterfaces,
        username = sheetState.localProxySettingsDraft.username,
        password = sheetState.localProxySettingsDraft.password,
        onInboundProxyPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(transparentProxyPort = it)
        },
        onPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(
                port = it,
            )
        },
        onEnableDynamicPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(enableDynamicPort = it)
        },
        onListenAllInterfacesChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(listenAllInterfaces = it)
        },
        onUsernameChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(username = it)
        },
        onPasswordChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(password = it)
        },
        onDismissRequest = { sheetState.showLocalProxySettings = false },
        onSave = { inboundProxyPort, port, enableDynamicPort, listenAllInterfaces, username, password ->
            validateAndCommit(
                operation = "save_local_proxy_settings",
                transform = { state ->
                    val lockInboundProxyPort = state.runMode == RunModeTproxy && state.proxyRunning
                    state.copy(
                        transparentProxyPort = when {
                            lockInboundProxyPort -> state.transparentProxyPort
                            state.runMode == RunModeTproxy -> inboundProxyPort
                            else -> state.transparentProxyPort
                        },
                        localProxyPort = port,
                        enableDynamicLocalProxyPort = enableDynamicPort,
                        localProxyListenAllInterfaces = listenAllInterfaces,
                        localProxyUsername = username,
                        localProxyPassword = password,
                    )
                },
                close = { sheetState.showLocalProxySettings = false },
            )
        },
    )
    SnifferSettingsBottomSheet(
        show = sheetState.showSnifferSettings,
        saving = validating,
        draft = sheetState.snifferSettingsDraft,
        onDraftChange = { sheetState.snifferSettingsDraft = it },
        onDismissRequest = { sheetState.showSnifferSettings = false },
        onSave = { draft ->
            validateAndCommit(
                operation = "save_sniffer_settings",
                transform = { state ->
                    state.copy(
                        enableSniffer = draft.enableSniffer,
                        snifferProtocols = draft.snifferProtocols,
                        snifferTimeout = draft.snifferTimeout,
                    )
                },
                close = { sheetState.showSnifferSettings = false },
            )
        },
    )
    ExternalInterfacesBottomSheet(
        show = sheetState.showExternalInterfaces,
        selectedInterfaces = sheetState.externalInterfacesDraft,
        onSelectedInterfacesChange = { sheetState.externalInterfacesDraft = it.sanitizeExternalInterfaces() },
        onDismissRequest = { sheetState.showExternalInterfaces = false },
        onSave = { interfaces ->
            updateAppState { state -> state.copy(externalInterfaces = interfaces.sanitizeExternalInterfaces()) }
            sheetState.showExternalInterfaces = false
        },
    )
    ServiceControlBottomSheet(
        show = sheetState.showServiceControl,
        saving = serviceControlSaving,
        draft = sheetState.serviceControlDraft,
        runtimeError = serviceControlError,
        onDraftChange = {
            serviceControlError = null
            sheetState.serviceControlDraft = it
        },
        onDismissRequest = {
            if (!serviceControlSaving) sheetState.showServiceControl = false
        },
        onSave = { draft ->
            if (!serviceControlSaving) {
                val baseState = appState
                serviceControlSaving = true
                serviceControlError = null
                scope.launch {
                    try {
                        val applied = applyServiceControl.apply(baseState, draft)
                        updateAppState { current ->
                            current.copy(
                                serviceControl = applied.serviceControl,
                                proxyRunning = applied.proxyRunning,
                                localProxyPort = applied.localProxyPort,
                            )
                        }
                        sheetState.showServiceControl = false
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        reportFailure(
                            context = FailureLogContext(
                                operation = "save_service_control",
                                stage = "restart_asteriskd",
                            ),
                            error = error,
                        )
                        serviceControlError = error.message?.takeIf(String::isNotBlank)
                            ?: serviceControlFailedMessage
                    } finally {
                        serviceControlSaving = false
                    }
                }
            }
        },
    )
    IgnoredInterfacesBottomSheet(
        show = sheetState.showIgnoredInterfaces,
        selectedInterfaces = sheetState.ignoredInterfacesDraft,
        onSelectedInterfacesChange = {
            sheetState.ignoredInterfacesDraft = it.sanitizeIgnoredInterfaceSelectors()
        },
        onDismissRequest = { sheetState.closeIgnoredInterfaces() },
        onSave = { interfaces ->
            updateAppState { state ->
                state.copy(ignoredInterfaces = interfaces.sanitizeIgnoredInterfaceSelectors())
            }
            sheetState.closeIgnoredInterfaces()
        },
    )
    PrivateAddressBottomSheet(
        show = sheetState.showPrivateAddresses,
        selectedCidrs = sheetState.privateAddressCidrsDraft,
        onSelectedCidrsChange = { sheetState.privateAddressCidrsDraft = it.sanitizePrivateAddressCidrs() },
        onDismissRequest = { sheetState.showPrivateAddresses = false },
        onSave = { cidrs ->
            updateAppState { state -> state.copy(privateAddressCidrs = cidrs.sanitizePrivateAddressCidrs()) }
            sheetState.showPrivateAddresses = false
        },
    )
    EbpfBypassRuleSetBottomSheet(
        show = sheetState.showEbpfBypassRuleSets,
        saving = validating,
        choices = ebpfBypassRuleSetChoices,
        selectedTags = sheetState.ebpfBypassRuleSetTagsDraft,
        onSelectedTagsChange = { tags ->
            sheetState.ebpfBypassRuleSetTagsDraft = sanitizeEbpfBypassRuleSetTags(tags)
        },
        onDismissRequest = { sheetState.showEbpfBypassRuleSets = false },
        onSave = { tags ->
            validateAndCommit(
                operation = "save_ebpf_bypass_rule_sets",
                transform = { state ->
                    state.copy(ebpfBypassRuleSetTags = sanitizeEbpfBypassRuleSetTags(tags))
                },
                close = { sheetState.showEbpfBypassRuleSets = false },
            )
        },
    )
    EbpfSharedNetworkBottomSheet(
        show = sheetState.showEbpfSharedNetwork,
        interfaces = sheetState.ebpfSharedNetworkInterfacesDraft,
        onInterfacesChange = { interfaces ->
            sheetState.ebpfSharedNetworkInterfacesDraft =
                interfaces.sanitizeEbpfSharedNetworkInterfaces()
        },
        onDismissRequest = { sheetState.showEbpfSharedNetwork = false },
        onSave = { interfaces ->
            updateAppState { state ->
                state.copy(
                    ebpfSharedNetworkInterfaces = interfaces.sanitizeEbpfSharedNetworkInterfaces(),
                )
            }
            sheetState.showEbpfSharedNetwork = false
        },
    )
    EbpfEndpointConnectedBypassBottomSheet(
        show = sheetState.showEbpfEndpointConnectedBypass,
        enabled = sheetState.ebpfEndpointConnectedBypassEnabledDraft,
        ipCidr = sheetState.ebpfEndpointConnectedBypassIpCidrDraft,
        port = sheetState.ebpfEndpointConnectedBypassPortDraft,
        onEnabledChange = { sheetState.ebpfEndpointConnectedBypassEnabledDraft = it },
        onIpCidrChange = { sheetState.ebpfEndpointConnectedBypassIpCidrDraft = it },
        onPortChange = { sheetState.ebpfEndpointConnectedBypassPortDraft = it },
        onDismissRequest = { sheetState.showEbpfEndpointConnectedBypass = false },
        onSave = {
            updateAppState { state ->
                state.copy(
                    ebpfEndpointConnectedBypassEnabled = sheetState.ebpfEndpointConnectedBypassEnabledDraft,
                    ebpfEndpointConnectedBypassIpCidr = sheetState.ebpfEndpointConnectedBypassIpCidrDraft,
                    ebpfEndpointConnectedBypassPort = sheetState.ebpfEndpointConnectedBypassPortDraft,
                )
            }
            sheetState.showEbpfEndpointConnectedBypass = false
        },
    )
}
