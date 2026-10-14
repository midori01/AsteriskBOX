// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.mode.Ipv6LeakGuard
import engine.root.mode.ipv6LeakGuardCommands
import engine.root.mode.tunVpnYieldCommands
import features.logs.AndroidAppLogger
import system.RootShellGateway
import kotlin.coroutines.cancellation.CancellationException

private const val LogTag = "RootTunVpnYield"

internal suspend fun RootShellGateway.applyTunVpnYield(enabled: Boolean) {
    execRootRules(tunVpnYieldCommands(enabled), "Failed to update TUN VPN yield rules")
}

internal suspend fun RootShellGateway.applyIpv6LeakGuard(guard: Ipv6LeakGuard?) {
    execRootRules(ipv6LeakGuardCommands(guard), "Failed to update IPv6 leak guard rules")
}

private suspend fun RootShellGateway.execRootRules(commands: List<String>, failure: String) {
    try {
        exec(commands.joinToString("; "))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        AndroidAppLogger.warn(LogTag, failure, error)
    }
}
