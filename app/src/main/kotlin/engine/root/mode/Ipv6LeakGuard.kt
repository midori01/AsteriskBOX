// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import engine.root.daemon.config.AsteriskdAppPolicyMode
import engine.root.daemon.config.AsteriskdConfig
import engine.root.daemon.config.AsteriskdMode
import engine.singbox.config.APP_ROOT_INBOUND
import engine.singbox.config.APP_TUN_INBOUND
import engine.singbox.config.SingBoxTunAutoRedirectOutputMark
import engine.singbox.config.parseSingBoxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Keeps proxied apps from learning the physical IPv6 address, which WebRTC reads back from a socket
 * connected to a public address. Matching local IPv6 traffic is sent to [table], whose route makes
 * the kernel pick a non-physical source address, or refused when [block] is set.
 */
internal data class Ipv6LeakGuard(
    val table: Int? = null,
    val coreMark: Int? = null,
    val firstUid: Int = 0,
    val includeUids: List<IntRange>? = null,
    val excludeUids: List<IntRange> = emptyList(),
    val loopbackSource: Boolean = false,
    val block: Boolean = false,
)

private const val TunRoutingTable = 2022
private const val TproxyRoutingTable = 160
private const val FirstApplicationUid = 10000
private const val LoopbackSourceAddress = "fdfe:dcba:9877::1/128"
private const val SkipPref = 21900
private const val RoutePref = 21901
private const val NopPref = 21902
private val UnguardedDestinations = listOf("fc00::/7", "fe80::/10", "ff00::/8")

// The rules sit after netd's VPN, explicit network, output interface and local network rules and
// before its implicit network rules, so VPNs, bound sockets, scoped addresses and replies to
// incoming connections keep their routes. A table owned by the core is empty once it stops.
internal fun ipv6LeakGuardCommands(guard: Ipv6LeakGuard?): List<String> = buildList {
    for (pref in listOf(SkipPref, RoutePref, NopPref)) {
        add("while ip -6 rule del pref $pref 2>/dev/null; do :; done")
    }
    add("ip -6 addr del $LoopbackSourceAddress dev lo 2>/dev/null || true")
    if (guard == null) return@buildList
    if (guard.loopbackSource) add("ip -6 addr add $LoopbackSourceAddress dev lo")
    if (guard.table == null && !guard.block) return@buildList
    val includes = guard.includeUids?.mapNotNull { range ->
        val first = maxOf(range.first, guard.firstUid)
        if (first <= range.last) first..range.last else null
    }
    if (includes != null && includes.isEmpty()) return@buildList
    val skip = "ip -6 rule add pref $SkipPref"
    val route = "ip -6 rule add pref $RoutePref"
    add("ip -6 rule add pref $NopPref nop")
    add("$skip not iif lo goto $NopPref")
    guard.coreMark?.let { add("$skip fwmark ${it.hex()}/${it.hex()} goto $NopPref") }
    UnguardedDestinations.forEach { add("$skip to $it goto $NopPref") }
    if (guard.firstUid > 0) add("$skip uidrange 0-${guard.firstUid - 1} goto $NopPref")
    guard.excludeUids.forEach { add("$skip uidrange ${it.first}-${it.last} goto $NopPref") }
    val selectors = includes?.map { "uidrange ${it.first}-${it.last} " } ?: listOf("")
    guard.table?.let { table -> selectors.forEach { add("$route ${it}lookup $table") } }
    if (guard.block) selectors.forEach { add("$route ${it}unreachable") }
}

// Built from the final configurations so the rules follow what the core and asteriskd run.
internal fun ipv6LeakGuard(singBoxConfig: ByteArray, daemon: AsteriskdConfig): Ipv6LeakGuard? {
    if (daemon.network.disableSystemIpv6) return null
    return when (daemon.mode) {
        AsteriskdMode.Tun -> tunIpv6LeakGuard(singBoxConfig.inbound(APP_TUN_INBOUND))
        AsteriskdMode.Tproxy -> tproxyIpv6LeakGuard(daemon)
        AsteriskdMode.Ebpf -> ebpfIpv6LeakGuard(singBoxConfig.inbound(APP_ROOT_INBOUND)?.get("local") as? JsonObject)
        else -> null
    }
}

// The TUN address becomes the source; the core's own sockets carry the auto_redirect output mark.
private fun tunIpv6LeakGuard(inbound: JsonObject?): Ipv6LeakGuard? {
    if (inbound == null) return null
    val addresses = inbound["address"].listable() + inbound["inet6_address"].listable()
    if (addresses.none { it.contentOrNull?.contains(':') == true }) return null
    return Ipv6LeakGuard(
        table = TunRoutingTable,
        coreMark = SingBoxTunAutoRedirectOutputMark,
        includeUids = uidRanges(inbound["include_uid"], inbound["include_uid_range"]).ifEmpty { null },
        excludeUids = uidRanges(inbound["exclude_uid"], inbound["exclude_uid_range"]),
    )
}

// asteriskd's local route sends traffic over lo, where the loopback address becomes the source and
// its TPROXY rules take what it captures. The core runs as root, so only applications are matched.
private fun tproxyIpv6LeakGuard(daemon: AsteriskdConfig): Ipv6LeakGuard {
    val policy = daemon.network.appPolicy
    val bypass = if (policy.mode == AsteriskdAppPolicyMode.Blacklist) policy.uids else emptyList()
    return Ipv6LeakGuard(
        table = TproxyRoutingTable,
        firstUid = FirstApplicationUid,
        includeUids = if (policy.mode == AsteriskdAppPolicyMode.Whitelist) mergeUidRanges(policy.uids.map { it..it }) else null,
        excludeUids = mergeUidRanges((bypass + policy.bypassUids).map { it..it }),
        loopbackSource = true,
        block = !daemon.network.enableIpv6,
    )
}

// The cgroup data plane redirects to a loopback route, so the loopback address becomes the source.
// Socket assignment captures after routing and IPv6 off leaves IPv6 uncaptured, so both block it.
private fun ebpfIpv6LeakGuard(local: JsonObject?): Ipv6LeakGuard? {
    if (local == null) return null
    val ipv6 = (local["ipv6"] as? JsonPrimitive)?.booleanOrNull == true
    val cgroup = (local["data_plane"] as? JsonPrimitive)?.contentOrNull == "cgroup"
    return Ipv6LeakGuard(
        firstUid = FirstApplicationUid,
        includeUids = uidRanges(local["include_uid"], local["include_uid_range"]).ifEmpty { null },
        excludeUids = uidRanges(local["exclude_uid"], local["exclude_uid_range"]),
        loopbackSource = ipv6 && cgroup,
        block = !ipv6 || !cgroup,
    )
}

private fun ByteArray.inbound(tag: String): JsonObject? =
    (runCatching { parseSingBoxJson(decodeToString()) }.getOrNull()?.get("inbounds") as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.firstOrNull { (it["tag"] as? JsonPrimitive)?.contentOrNull == tag }

private fun Int.hex(): String = "0x" + toString(16)

private fun JsonElement?.listable(): List<JsonPrimitive> = when (this) {
    is JsonArray -> filterIsInstance<JsonPrimitive>()
    is JsonPrimitive -> listOf(this)
    else -> emptyList()
}

private fun uidRanges(uids: JsonElement?, ranges: JsonElement?): List<IntRange> {
    val single = uids.listable().mapNotNull { value ->
        value.intOrNull?.takeIf { it >= 0 }?.let { it..it }
    }
    val spans = ranges.listable().mapNotNull { value ->
        val bounds = value.contentOrNull?.split(':')?.map { it.trim().toIntOrNull() }
        val start = bounds?.getOrNull(0)
        val end = bounds?.getOrNull(1)
        if (bounds?.size == 2 && start != null && end != null && start in 0..end) start..end else null
    }
    return mergeUidRanges(single + spans)
}

private fun mergeUidRanges(ranges: List<IntRange>): List<IntRange> {
    val merged = mutableListOf<IntRange>()
    for (range in ranges.filter { it.first >= 0 }.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}
