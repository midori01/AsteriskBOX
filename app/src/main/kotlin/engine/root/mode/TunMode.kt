// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import engine.proxy.toLocalProxyOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootIptablesConfig
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.singbox.config.SingBoxTunAutoRedirectOutputMark
import engine.singbox.config.SingBoxTunDevice

internal fun RootConfigBuildContext.buildTunStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val rootStartConfig = buildRootStartConfig()
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tun,
            iptablesConfig = RootIptablesConfig(
                externalInterfacePrefixes = appState.tunSharedNetworkInterfaces
                    .map(String::trim).filter { it.isNotEmpty() && it != "lo" }.distinct(),
            ),
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(
                transparentPort = null,
                tunnelName = SingBoxTunDevice,
            ),
        ),
    )
}

private val TunVpnInterfacePatterns = listOf("tun+", "ipsec+")

// Android discards replies addressed to a VPN's address that arrive on any other interface, so
// traffic Android routes into a VPN gets the auto_redirect output mark and stays on that VPN.
internal fun tunVpnYieldCommands(enabled: Boolean): List<String> {
    val mark = "0x" + SingBoxTunAutoRedirectOutputMark.toString(16) + "/0xffffffff"
    return buildList {
        for (iptables in listOf("iptables", "ip6tables")) {
            for (pattern in TunVpnInterfacePatterns) {
                val rule = "OUTPUT -o $pattern -j MARK --set-xmark $mark"
                if (enabled) {
                    add("{ $iptables -t raw -C $rule 2>/dev/null || $iptables -t raw -A $rule; }")
                } else {
                    add("while $iptables -t raw -D $rule 2>/dev/null; do :; done")
                }
            }
        }
    }
}
