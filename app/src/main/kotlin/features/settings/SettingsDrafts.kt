// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.AppState
import app.SingBoxDnsServerState
import engine.singbox.DefaultSingBoxSnifferProtocols
import engine.singbox.DefaultSingBoxSnifferTimeout

internal data class LocalProxySettingsDraft(
    val transparentProxyPort: String = "",
    val port: String = "",
    val enableDynamicPort: Boolean = false,
    val listenAllInterfaces: Boolean = false,
    val username: String = "",
    val password: String = "",
)

internal fun AppState.toLocalProxySettingsDraft(): LocalProxySettingsDraft {
    return LocalProxySettingsDraft(
        transparentProxyPort = transparentProxyPort,
        port = localProxyPort,
        enableDynamicPort = enableDynamicLocalProxyPort,
        listenAllInterfaces = localProxyListenAllInterfaces,
        username = localProxyUsername,
        password = localProxyPassword,
    )
}

internal data class DnsSettingsDraft(
    val enableLocalDns: Boolean = true,
    val dnsFinal: String = "",
    val routeDefaultDomainResolver: String = "",
    val dnsCacheCapacity: String = "",
    val dnsOptimisticCache: Boolean = false,
    val dnsDisableCache: Boolean = false,
    val dnsDisableExpire: Boolean = false,
    val dnsTimeout: String = "",
    val storeFakeIp: Boolean = false,
    val storeDns: Boolean = false,
    val dnsServers: List<SingBoxDnsServerState> = emptyList(),
    val nextDnsServerId: Int = 1,
    val dnsServerTagReplacements: Map<String, String> = emptyMap(),
    val dnsPreferredByTagReplacements: Map<String, String> = emptyMap(),
)

internal fun AppState.toDnsSettingsDraft(): DnsSettingsDraft {
    return DnsSettingsDraft(
        enableLocalDns = enableLocalDns,
        dnsFinal = dnsFinal,
        routeDefaultDomainResolver = routeDefaultDomainResolver,
        dnsCacheCapacity = dnsCacheCapacity,
        dnsOptimisticCache = dnsOptimisticCache,
        dnsDisableCache = dnsDisableCache,
        dnsDisableExpire = dnsDisableExpire,
        dnsTimeout = dnsTimeout,
        storeFakeIp = storeFakeIp,
        storeDns = storeDns,
        dnsServers = dnsServers,
        nextDnsServerId = nextDnsServerId,
    )
}

internal data class SnifferSettingsDraft(
    val enableSniffer: Boolean = true,
    val snifferProtocols: List<String> = DefaultSingBoxSnifferProtocols,
    val snifferTimeout: String = DefaultSingBoxSnifferTimeout,
)

internal fun AppState.toSnifferSettingsDraft(): SnifferSettingsDraft {
    return SnifferSettingsDraft(
        enableSniffer = enableSniffer,
        snifferProtocols = snifferProtocols,
        snifferTimeout = snifferTimeout,
    )
}

