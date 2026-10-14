// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import app.AppState
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import system.RootShellGateway
import system.ShellExecOptions
import java.io.File

private const val LogTag = "VpnTetheringRelayManager"
private const val ScriptFileName = "vpn_tether_relay.sh"
private const val PidFileName = "vpn_tether_relay.pid"
private const val LogFileName = "vpn_tether_relay.log"
private const val ActiveVpnFileName = "active_vpn.txt"
private const val ActiveVpnDnsFileName = "active_vpn_dns.txt"
private const val SysctlBackupFileName = "vpn_tether_relay.sysctl"
private const val BpfObjectFileName = "abox_relay_pass.o"

internal class VpnTetheringRelayManager(
    private val context: Context,
    private val rootAccess: RootShellGateway,
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutex = Mutex()
    private var scope: CoroutineScope? = null

    private var currentAppState: AppState? = null
    private var isProxyRunning: Boolean = false
    @Volatile
    private var isDaemonRunning: Boolean = false
    // Script of the running daemon; relaunching tears down and re-applies every rule, so only do it on change.
    private var launchedScriptContent: String? = null

    private val vpnNetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateActiveVpn(network)
            notifyDaemonWakeup()
        }

        override fun onLost(network: Network) {
            clearActiveVpn()
            notifyDaemonWakeup()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            updateFromLinkProperties(linkProperties)
            notifyDaemonWakeup()
        }
    }

    private fun updateActiveVpn(network: Network) {
        val linkProperties = connectivity?.getLinkProperties(network) ?: return
        updateFromLinkProperties(linkProperties)
    }

    private fun updateFromLinkProperties(linkProperties: LinkProperties) {
        val iface = linkProperties.interfaceName
        if (!iface.isNullOrBlank()) {
            writeActiveVpn(iface)
        }
        val dns = linkProperties.dnsServers
            .mapNotNull { it.hostAddress }
            .firstOrNull { it.contains('.') && !it.contains(':') }
        if (!dns.isNullOrBlank()) {
            writeActiveVpnDns(dns)
        }
    }

    private fun writeActiveVpn(iface: String) {
        runCatching {
            File(context.filesDir, ActiveVpnFileName).writeText(iface.trim())
        }
    }

    private fun writeActiveVpnDns(dns: String) {
        runCatching {
            File(context.filesDir, ActiveVpnDnsFileName).writeText(dns.trim())
        }
    }

    private fun clearActiveVpn() {
        runCatching {
            File(context.filesDir, ActiveVpnFileName).delete()
            File(context.filesDir, ActiveVpnDnsFileName).delete()
        }
    }

    private val tetheringReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            notifyDaemonWakeup()
        }
    }

    private var callbackRegistered = false
    private var receiverRegistered = false

    fun start(appState: AppState) {
        currentAppState = appState
        isProxyRunning = true

        registerCallbacksIfNeeded()

        val managerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        managerScope.launch {
            mutex.withLock {
                reconcileLocked()
            }
        }
    }

    fun update(appState: AppState) {
        currentAppState = appState
        val managerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        managerScope.launch {
            mutex.withLock {
                reconcileLocked()
            }
        }
    }

    fun stop() {
        if (!isProxyRunning && !isDaemonRunning) return
        isProxyRunning = false

        unregisterCallbacksIfNeeded()

        val currentScope = scope
        scope = null
        currentScope?.cancel()

        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                stopDaemonLocked()
            }
        }
    }

    private fun registerCallbacksIfNeeded() {
        if (!callbackRegistered && connectivity != null) {
            runCatching {
                connectivity.registerNetworkCallback(vpnNetworkRequest, networkCallback)
                callbackRegistered = true
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to register VPN network callback", error)
            }
        }
        if (!receiverRegistered) {
            runCatching {
                val filter = IntentFilter().apply {
                    addAction("android.net.conn.TETHER_STATE_CHANGED")
                    addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(tetheringReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(tetheringReceiver, filter)
                }
                receiverRegistered = true
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to register tethering broadcast receiver", error)
            }
        }
    }

    private fun unregisterCallbacksIfNeeded() {
        if (callbackRegistered && connectivity != null) {
            runCatching {
                connectivity.unregisterNetworkCallback(networkCallback)
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to unregister VPN network callback", error)
            }
            callbackRegistered = false
        }
        if (receiverRegistered) {
            runCatching {
                context.unregisterReceiver(tetheringReceiver)
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to unregister tethering broadcast receiver", error)
            }
            receiverRegistered = false
        }
    }

    private fun notifyDaemonWakeup() {
        if (!isDaemonRunning) return
        scope?.launch {
            runCatching {
                rootAccess.exec("pkill -USR1 -f $ScriptFileName 2>/dev/null")
            }
        }
    }

    private suspend fun reconcileLocked() {
        val state = currentAppState
        val shouldRun = isProxyRunning && (state?.dynamicVpnTetheringBypass == true)

        if (!shouldRun) {
            if (isDaemonRunning) {
                stopDaemonLocked()
            }
            return
        }

        val configuredInterfaces = state?.tunSharedNetworkInterfaces
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "lo" }
            ?: emptyList()

        startOrUpdateDaemonLocked(configuredInterfaces)
    }

    private suspend fun startOrUpdateDaemonLocked(configuredInterfaces: List<String>) {
        val scriptFile = File(context.filesDir, ScriptFileName)
        val pidFile = File(context.filesDir, PidFileName)
        val logFile = File(context.filesDir, LogFileName)

        val scriptContent = generateRelayScript(configuredInterfaces)
        if (isDaemonRunning && scriptContent == launchedScriptContent && isDaemonAlive(pidFile)) {
            notifyDaemonWakeup()
            return
        }
        runCatching {
            context.filesDir.mkdirs()
            scriptFile.writeText(scriptContent)
        }.onFailure { e ->
            AndroidAppLogger.warn(LogTag, "Failed to write relay daemon script", e)
            return
        }

        val scriptPath = scriptFile.absolutePath
        val pidPath = pidFile.absolutePath
        val logPath = logFile.absolutePath

        val launchCommands = listOf(
            "pkill -TERM -f $ScriptFileName 2>/dev/null",
            "rm -f '$pidPath' 2>/dev/null",
            "chmod 755 '$scriptPath'",
            "nohup /system/bin/sh '$scriptPath' </dev/null >>'$logPath' 2>&1 &",
        ).joinToString("; ")

        try {
            rootAccess.exec(launchCommands)
            isDaemonRunning = true
            launchedScriptContent = scriptContent
            AndroidAppLogger.info(LogTag, "Started native VPN tethering relay daemon (configuredAps=$configuredInterfaces)")
        } catch (e: Throwable) {
            AndroidAppLogger.warn(LogTag, "Failed to launch native VPN tethering relay daemon", e)
        }
    }

    private suspend fun stopDaemonLocked() {
        val scriptFile = File(context.filesDir, ScriptFileName)
        val pidFile = File(context.filesDir, PidFileName)
        val activeVpnFile = File(context.filesDir, ActiveVpnFileName)
        val activeVpnDnsFile = File(context.filesDir, ActiveVpnDnsFileName)
        val scriptPath = scriptFile.absolutePath
        val pidPath = pidFile.absolutePath
        val activeVpnPath = activeVpnFile.absolutePath
        val activeVpnDnsPath = activeVpnDnsFile.absolutePath

        val stopCommands = listOf(
            "pkill -TERM -f $ScriptFileName 2>/dev/null",
            "if [ -f '$scriptPath' ]; then /system/bin/sh '$scriptPath' --cleanup 2>/dev/null; fi",
            "rm -f '$pidPath' '$activeVpnPath' '$activeVpnDnsPath' 2>/dev/null",
        ).joinToString("; ")

        try {
            rootAccess.exec(stopCommands)
            AndroidAppLogger.info(LogTag, "Stopped native VPN tethering relay daemon and cleaned rules")
        } catch (e: Throwable) {
            AndroidAppLogger.warn(LogTag, "Failed to stop native VPN tethering relay daemon", e)
        } finally {
            isDaemonRunning = false
            launchedScriptContent = null
        }
    }

    private suspend fun isDaemonAlive(pidFile: File): Boolean {
        val pidPath = pidFile.absolutePath
        return runCatching {
            rootAccess.exec(
                "pid=\$(cat '$pidPath' 2>/dev/null); [ -n \"\$pid\" ] && kill -0 \"\$pid\" 2>/dev/null",
                ShellExecOptions(logFailure = false),
            ).errno == 0
        }.getOrDefault(false)
    }

    private fun generateRelayScript(configuredInterfaces: List<String>): String {
        val configuredAps = configuredInterfaces.joinToString(" ")
        val pidPath = File(context.filesDir, PidFileName).absolutePath
        val activeVpnPath = File(context.filesDir, ActiveVpnFileName).absolutePath
        val activeVpnDnsPath = File(context.filesDir, ActiveVpnDnsFileName).absolutePath
        val sysctlBackupPath = File(context.filesDir, SysctlBackupFileName).absolutePath
        val bpfObjectPath = File(context.cacheDir, BpfObjectFileName).absolutePath
        return ScriptTemplate
            .replace("__PID_FILE__", pidPath)
            .replace("__SYSCTL_BACKUP_FILE__", sysctlBackupPath)
            .replace("__PASS_BPF_OBJ__", bpfObjectPath)
            .replace("__ACTIVE_VPN_FILE__", activeVpnPath)
            .replace("__ACTIVE_VPN_DNS_FILE__", activeVpnDnsPath)
            .replace("__CONFIGURED_APS__", configuredAps)
            .replace("@@", "$")
    }
}

private const val ScriptTemplate = """#!/system/bin/sh
PID_FILE="__PID_FILE__"
ACTIVE_VPN_FILE="__ACTIVE_VPN_FILE__"
ACTIVE_VPN_DNS_FILE="__ACTIVE_VPN_DNS_FILE__"
CONFIGURED_APS="__CONFIGURED_APS__"
RELAY_TABLE=99
SYSCTL_BACKUP_FILE="__SYSCTL_BACKUP_FILE__"
GLOBAL_SYSCTLS="net.ipv4.conf.all.disable_policy net.ipv4.conf.default.disable_policy net.ipv4.conf.all.disable_xfrm net.ipv4.conf.default.disable_xfrm net.ipv6.conf.all.disable_policy net.ipv6.conf.default.disable_policy net.ipv6.conf.all.disable_xfrm net.ipv6.conf.default.disable_xfrm"

PASS_BPF_PIN="/sys/fs/bpf/abox_relay_pass"
PASS_BPF_OBJ="__PASS_BPF_OBJ__"
PASS_BPF_B64="f0VMRgIBAQAAAAAAAAAAAAEA9wABAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAEAAAAAAAEAABwABALQAAAAAAAAAlQAAAAAAAABHUEwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACoAAAAEAPH/AAAAAAAAAAAAAAAAAAAAAAcAAAASAAMAAAAAAAAAAAAQAAAAAAAAAB4AAAARAAQAAAAAAAAAAAAEAAAAAAAAAAAudGV4dABwYXNzX2FsbAAubGx2bV9hZGRyc2lnAF9saWNlbnNlAHRjAHBhc3MuYwAuc3RydGFiAC5zeW10YWIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAxAAAAAwAAAAAAAAAAAAAAAAAAAAAAAAC4AAAAAAAAAEEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAEAAAAGAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAACcAAAABAAAABgAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAfAAAAAQAAAAMAAAAAAAAAAAAAAAAAAABQAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAEAAAAANM/28AAACAAAAAAAAAAAAAAAAAuAAAAAAAAAAAAAAAAAAAAAYAAAAAAAAAAQAAAAAAAAAAAAAAAAAAADkAAAACAAAAAAAAAAAAAAAAAAAAAAAAAFgAAAAAAAAAYAAAAAAAAAABAAAAAgAAAAgAAAAAAAAAGAAAAAAAAAA="

ensure_pass_bpf() {
    if [ ! -f "@@PASS_BPF_PIN" ]; then
        echo "@@PASS_BPF_B64" | base64 -d > "@@PASS_BPF_OBJ" 2>/dev/null
        if [ -s "@@PASS_BPF_OBJ" ]; then
            bpftool prog load "@@PASS_BPF_OBJ" "@@PASS_BPF_PIN" 2>/dev/null
            rm -f "@@PASS_BPF_OBJ" 2>/dev/null
        fi
    fi
}

APPLIED_VPN=""
APPLIED_AP=""
SLEEP_PID=""

log() {
    echo "[@@(date '+%Y-%m-%d %H:%M:%S')] @@*"
}

# Record original global XFRM sysctls once so cleanup can restore them.
save_global_sysctls() {
    [ -f "@@SYSCTL_BACKUP_FILE" ] && return 0
    for key in @@GLOBAL_SYSCTLS; do
        val=@@(sysctl -n "@@key" 2>/dev/null) || continue
        echo "@@key=@@val"
    done > "@@SYSCTL_BACKUP_FILE"
}

restore_global_sysctls() {
    [ -f "@@SYSCTL_BACKUP_FILE" ] || return 0
    while IFS= read -r entry; do
        [ -n "@@entry" ] && sysctl -w "@@entry" >/dev/null 2>&1
    done < "@@SYSCTL_BACKUP_FILE"
    rm -f "@@SYSCTL_BACKUP_FILE"
}

# Delete only the policy rules this relay installed, never foreign rules sharing a priority:
# 999 "to <subnet> lookup RELAY_TABLE", 1000 "iif <if> lookup RELAY_TABLE", 1001 "iif <relay if> lookup <vpn table>".
del_relay_rules() {
    ifaces=" @@* "
    for fam in -4 -6; do
        ip @@fam rule show 2>/dev/null | grep -E "^(999|1000|1001):" | while read -r line; do
            pri=@@{line%%:*}
            set -- @@{line#*:}
            sel_key=""; sel_val=""; table=""
            while [ @@# -gt 0 ]; do
                case "@@1" in
                    to|iif) sel_key="@@1"; sel_val="@@2"; shift ;;
                    lookup|table) table="@@2"; shift ;;
                esac
                shift
            done
            [ -n "@@sel_key" ] || continue
            case "@@pri:@@sel_key" in
                999:to|1000:iif) [ "@@table" = "@@RELAY_TABLE" ] || continue ;;
                1001:iif) case "@@ifaces" in *" @@sel_val "*) ;; *) continue ;; esac ;;
                *) continue ;;
            esac
            ip @@fam rule del priority "@@pri" "@@sel_key" "@@sel_val" 2>/dev/null
        done
    done
}

# Delete only the DNS DNAT rules this relay installed (incoming tethering interface, port 53).
del_relay_dns_dnat() {
    for ap in "@@@"; do
        iptables -t nat -S PREROUTING 2>/dev/null | grep -E -- "-i @@ap -p (udp|tcp) -m (udp|tcp) --dport 53 -j DNAT" | while read -r line; do
            rule=@@(echo "@@line" | sed 's/^-A //')
            iptables -t nat -D @@rule 2>/dev/null
        done
    done
}

relay_ifaces() {
    for ap in "@@@"; do
        echo "@@ap"
        for sub in /sys/class/net/"@@ap"/brif/*; do
            [ -e "@@sub" ] && basename "@@sub"
        done
    done
}

cleanup() {
    log "Cleaning up relay rules and exiting"
    [ -n "@@SLEEP_PID" ] && kill "@@SLEEP_PID" 2>/dev/null
    if [ -n "@@APPLIED_VPN" ] && [ -n "@@APPLIED_AP" ]; then
        for ap in @@APPLIED_AP; do
            tc filter del dev "@@ap" ingress pref 1 2>/dev/null
            sysctl -w "net.ipv4.conf.@@ap.disable_policy=0" 2>/dev/null
            sysctl -w "net.ipv4.conf.@@ap.disable_xfrm=0" 2>/dev/null
            sysctl -w "net.ipv6.conf.@@ap.disable_policy=0" 2>/dev/null
            sysctl -w "net.ipv6.conf.@@ap.disable_xfrm=0" 2>/dev/null
            for sub in /sys/class/net/"@@ap"/brif/*; do
                if [ -e "@@sub" ]; then
                    sub_name=@@(basename "@@sub")
                    tc filter del dev "@@sub_name" ingress pref 1 2>/dev/null
                    sysctl -w "net.ipv4.conf.@@sub_name.disable_policy=0" 2>/dev/null
                    sysctl -w "net.ipv4.conf.@@sub_name.disable_xfrm=0" 2>/dev/null
                    sysctl -w "net.ipv6.conf.@@sub_name.disable_policy=0" 2>/dev/null
                    sysctl -w "net.ipv6.conf.@@sub_name.disable_xfrm=0" 2>/dev/null
                    iptables -D FORWARD -i "@@sub_name" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
                    iptables -D FORWARD -i "@@APPLIED_VPN" -o "@@sub_name" -j ACCEPT 2>/dev/null
                    ip6tables -D FORWARD -i "@@sub_name" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
                    ip6tables -D FORWARD -i "@@APPLIED_VPN" -o "@@sub_name" -j ACCEPT 2>/dev/null
                fi
            done

            iptables -D FORWARD -i "@@ap" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
            iptables -D FORWARD -i "@@APPLIED_VPN" -o "@@ap" -j ACCEPT 2>/dev/null
            ip6tables -D FORWARD -i "@@ap" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
            ip6tables -D FORWARD -i "@@APPLIED_VPN" -o "@@ap" -j ACCEPT 2>/dev/null
        done
        ip route flush table @@RELAY_TABLE 2>/dev/null
        ip -6 route flush table @@RELAY_TABLE 2>/dev/null
        ip route flush cache 2>/dev/null
        iptables -t nat -D POSTROUTING -o "@@APPLIED_VPN" -j MASQUERADE 2>/dev/null
        ip6tables -t nat -D POSTROUTING -o "@@APPLIED_VPN" -j MASQUERADE 2>/dev/null
        iptables -t mangle -D FORWARD -o "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        iptables -t mangle -D FORWARD -i "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        ip6tables -t mangle -D FORWARD -o "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        ip6tables -t mangle -D FORWARD -i "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null

        relay_if_list=@@(relay_ifaces @@APPLIED_AP)
        del_relay_rules @@relay_if_list
        del_relay_dns_dnat @@relay_if_list

        APPLIED_VPN=""
        APPLIED_AP=""
    fi
    restore_global_sysctls
    rm -f "@@PID_FILE" "@@ACTIVE_VPN_FILE" "@@ACTIVE_VPN_DNS_FILE" "@@PASS_BPF_PIN" "@@PASS_BPF_OBJ" 2>/dev/null
}

if [ "@@1" = "--cleanup" ]; then
    cleanup
    exit 0
fi

trap 'cleanup; exit 0' EXIT TERM INT
trap ':' USR1

resolve_table_id() {
    vpn="@@1"
    tid=@@(grep -w "@@vpn" /data/misc/net/rt_tables 2>/dev/null | awk '{print @@1}' | head -n1)
    if [ -n "@@tid" ] && [ "@@tid" -eq "@@tid" ] 2>/dev/null; then
        echo "@@tid"
        return 0
    fi
    tid=@@(ip rule show 2>/dev/null | grep -E "lookup [0-9]+" | grep -w "@@vpn" | awk '{for(i=1;i<=NF;i++) if(@@i=="lookup") print @@(i+1)}' | head -n1)
    if [ -n "@@tid" ] && [ "@@tid" -eq "@@tid" ] 2>/dev/null; then
        echo "@@tid"
        return 0
    fi
    tid=@@(ip route show dev "@@vpn" table all 2>/dev/null | grep -o 'table [0-9]*' | awk '{print @@2}' | head -n1)
    if [ -n "@@tid" ] && [ "@@tid" -eq "@@tid" ] 2>/dev/null; then
        echo "@@tid"
        return 0
    fi
    echo ""
}

probe_vpn_dns() {
    vpn="@@1"
    if [ -f "@@ACTIVE_VPN_DNS_FILE" ]; then
        cand_dns=@@(cat "@@ACTIVE_VPN_DNS_FILE" 2>/dev/null | tr -d '\r\n ')
        case "@@cand_dns" in
            *.*.*.*) echo "@@cand_dns"; return 0 ;;
        esac
    fi
    cand_dns=@@(dumpsys connectivity --networks 2>/dev/null | grep -E "InterfaceName: @@vpn" -A 1 | grep -o 'DnsAddresses: \[ [^]]* \]' | tr -d '[]/' | awk '{print @@2}')
    case "@@cand_dns" in
        *.*.*.*) echo "@@cand_dns"; return 0 ;;
    esac
    cand_dns=@@(getprop net.dns1 2>/dev/null | tr -d '\r\n ')
    case "@@cand_dns" in
        *.*.*.*) echo "@@cand_dns"; return 0 ;;
    esac
    echo "1.1.1.1"
}

apply_rules() {
    vpn="@@1"
    aps="@@2"

    vpn_dns=@@(probe_vpn_dns "@@vpn")
    table_id=@@(resolve_table_id "@@vpn")
    log "Applying relay rules: aps=[@@aps] -> vpn=[@@vpn] (vpn_dns=@@vpn_dns, sys_table=@@table_id, relay_table=@@RELAY_TABLE)"

    ip route flush table @@RELAY_TABLE 2>/dev/null
    del_relay_rules @@(relay_ifaces @@APPLIED_AP @@aps)
    for ap in @@aps; do
        subnet=@@(ip route show dev "@@ap" table all 2>/dev/null | grep -E "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+" | awk '{print @@1}' | head -n1)
        if [ -n "@@subnet" ]; then
            ip route replace "@@subnet" dev "@@ap" table @@RELAY_TABLE 2>/dev/null
            ip rule add to "@@subnet" table @@RELAY_TABLE priority 999 2>/dev/null
        fi
    done
    ip route replace default dev "@@vpn" table @@RELAY_TABLE 2>/dev/null
    ip -6 route flush table @@RELAY_TABLE 2>/dev/null
    ip -6 route replace default dev "@@vpn" table @@RELAY_TABLE 2>/dev/null

    save_global_sysctls
    ensure_pass_bpf

    for ap in @@aps; do
        tc qdisc add dev "@@ap" clsact 2>/dev/null
        tc filter del dev "@@ap" ingress pref 1 2>/dev/null

        # Direct-action eBPF pass filter (terminates TC pipeline at pref 1 and bypasses sing-box eBPF)
        if [ -f "@@PASS_BPF_PIN" ]; then
            tc filter add dev "@@ap" ingress pref 1 bpf object-pinned "@@PASS_BPF_PIN" direct-action 2>/dev/null
        fi
        if ! tc filter show dev "@@ap" ingress 2>/dev/null | grep -q "pref 1"; then
            tc filter add dev "@@ap" ingress pref 1 protocol all u32 match u32 0 0 2>/dev/null || \
            tc filter add dev "@@ap" ingress pref 1 protocol all bpf bytecode '1,6 0 0 1' 2>/dev/null
        fi

        if tc filter show dev "@@ap" ingress 2>/dev/null | grep -q "pref 1"; then
            log "TC bypass filter active on @@ap (pref 1)"
        else
            log "WARNING: TC bypass filter not installed on @@ap"
        fi

        sysctl -w "net.ipv4.conf.@@ap.forwarding=1" 2>/dev/null
        sysctl -w "net.ipv4.conf.@@ap.rp_filter=0" 2>/dev/null
        sysctl -w "net.ipv4.conf.@@ap.disable_policy=1" 2>/dev/null
        sysctl -w "net.ipv4.conf.@@ap.disable_xfrm=1" 2>/dev/null
        sysctl -w "net.ipv6.conf.@@ap.disable_policy=1" 2>/dev/null
        sysctl -w "net.ipv6.conf.@@ap.disable_xfrm=1" 2>/dev/null

        ip rule add iif "@@ap" table @@RELAY_TABLE priority 1000 2>/dev/null
        ip -6 rule add iif "@@ap" table @@RELAY_TABLE priority 1000 2>/dev/null
        if [ -n "@@table_id" ] && [ "@@table_id" -eq "@@table_id" ] 2>/dev/null; then
            ip rule add iif "@@ap" table "@@table_id" priority 1001 2>/dev/null
            ip -6 rule add iif "@@ap" table "@@table_id" priority 1001 2>/dev/null
        fi

        iptables -D FORWARD -i "@@ap" -o "@@vpn" -j ACCEPT 2>/dev/null
        iptables -I FORWARD 1 -i "@@ap" -o "@@vpn" -j ACCEPT 2>/dev/null
        iptables -D FORWARD -i "@@vpn" -o "@@ap" -j ACCEPT 2>/dev/null
        iptables -I FORWARD 1 -i "@@vpn" -o "@@ap" -j ACCEPT 2>/dev/null

        ip6tables -D FORWARD -i "@@ap" -o "@@vpn" -j ACCEPT 2>/dev/null
        ip6tables -I FORWARD 1 -i "@@ap" -o "@@vpn" -j ACCEPT 2>/dev/null
        ip6tables -D FORWARD -i "@@vpn" -o "@@ap" -j ACCEPT 2>/dev/null
        ip6tables -I FORWARD 1 -i "@@vpn" -o "@@ap" -j ACCEPT 2>/dev/null

        # Hijack DNS to VPN DNS so hotspot clients don't get trapped by local port 53 sockets
        iptables -t nat -D PREROUTING -i "@@ap" -p udp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null
        iptables -t nat -I PREROUTING 1 -i "@@ap" -p udp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null
        iptables -t nat -D PREROUTING -i "@@ap" -p tcp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null
        iptables -t nat -I PREROUTING 1 -i "@@ap" -p tcp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null

        for sub in /sys/class/net/"@@ap"/brif/*; do
            if [ -e "@@sub" ]; then
                sub_name=@@(basename "@@sub")
                tc qdisc add dev "@@sub_name" clsact 2>/dev/null
                tc filter del dev "@@sub_name" ingress pref 1 2>/dev/null
                if [ -f "@@PASS_BPF_PIN" ]; then
                    tc filter add dev "@@sub_name" ingress pref 1 bpf object-pinned "@@PASS_BPF_PIN" direct-action 2>/dev/null
                fi
                if ! tc filter show dev "@@sub_name" ingress 2>/dev/null | grep -q "pref 1"; then
                    tc filter add dev "@@sub_name" ingress pref 1 protocol all u32 match u32 0 0 2>/dev/null || \
                    tc filter add dev "@@sub_name" ingress pref 1 protocol all bpf bytecode '1,6 0 0 1' 2>/dev/null
                fi

                sysctl -w "net.ipv4.conf.@@sub_name.forwarding=1" 2>/dev/null
                sysctl -w "net.ipv4.conf.@@sub_name.rp_filter=0" 2>/dev/null
                sysctl -w "net.ipv4.conf.@@sub_name.disable_policy=1" 2>/dev/null
                sysctl -w "net.ipv4.conf.@@sub_name.disable_xfrm=1" 2>/dev/null
                sysctl -w "net.ipv6.conf.@@sub_name.disable_policy=1" 2>/dev/null
                sysctl -w "net.ipv6.conf.@@sub_name.disable_xfrm=1" 2>/dev/null

                ip rule add iif "@@sub_name" table @@RELAY_TABLE priority 1000 2>/dev/null
                ip -6 rule add iif "@@sub_name" table @@RELAY_TABLE priority 1000 2>/dev/null
                if [ -n "@@table_id" ] && [ "@@table_id" -eq "@@table_id" ] 2>/dev/null; then
                    ip rule add iif "@@sub_name" table "@@table_id" priority 1001 2>/dev/null
                    ip -6 rule add iif "@@sub_name" table "@@table_id" priority 1001 2>/dev/null
                fi

                iptables -D FORWARD -i "@@sub_name" -o "@@vpn" -j ACCEPT 2>/dev/null
                iptables -I FORWARD 1 -i "@@sub_name" -o "@@vpn" -j ACCEPT 2>/dev/null
                iptables -D FORWARD -i "@@vpn" -o "@@sub_name" -j ACCEPT 2>/dev/null
                iptables -I FORWARD 1 -i "@@vpn" -o "@@sub_name" -j ACCEPT 2>/dev/null

                ip6tables -D FORWARD -i "@@sub_name" -o "@@vpn" -j ACCEPT 2>/dev/null
                ip6tables -I FORWARD 1 -i "@@sub_name" -o "@@vpn" -j ACCEPT 2>/dev/null
                ip6tables -D FORWARD -i "@@vpn" -o "@@sub_name" -j ACCEPT 2>/dev/null
                ip6tables -I FORWARD 1 -i "@@vpn" -o "@@sub_name" -j ACCEPT 2>/dev/null

                iptables -t nat -D PREROUTING -i "@@sub_name" -p udp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null
                iptables -t nat -I PREROUTING 1 -i "@@sub_name" -p udp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null
                iptables -t nat -D PREROUTING -i "@@sub_name" -p tcp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null
                iptables -t nat -I PREROUTING 1 -i "@@sub_name" -p tcp --dport 53 -j DNAT --to-destination "@@vpn_dns:53" 2>/dev/null
            fi
        done
    done

    sysctl -w net.ipv4.ip_forward=1 2>/dev/null
    sysctl -w net.ipv6.conf.all.forwarding=1 2>/dev/null
    sysctl -w net.ipv4.conf.all.forwarding=1 2>/dev/null
    sysctl -w net.ipv4.conf.default.forwarding=1 2>/dev/null
    sysctl -w net.ipv4.conf.all.rp_filter=0 2>/dev/null
    sysctl -w net.ipv4.conf.default.rp_filter=0 2>/dev/null

    sysctl -w net.ipv4.conf.all.disable_policy=1 2>/dev/null
    sysctl -w net.ipv4.conf.default.disable_policy=1 2>/dev/null
    sysctl -w net.ipv4.conf.all.disable_xfrm=1 2>/dev/null
    sysctl -w net.ipv4.conf.default.disable_xfrm=1 2>/dev/null

    sysctl -w net.ipv6.conf.all.disable_policy=1 2>/dev/null
    sysctl -w net.ipv6.conf.default.disable_policy=1 2>/dev/null
    sysctl -w net.ipv6.conf.all.disable_xfrm=1 2>/dev/null
    sysctl -w net.ipv6.conf.default.disable_xfrm=1 2>/dev/null

    sysctl -w "net.ipv4.conf.@@vpn.forwarding=1" 2>/dev/null
    sysctl -w "net.ipv4.conf.@@vpn.rp_filter=0" 2>/dev/null
    sysctl -w "net.ipv4.conf.@@vpn.disable_policy=1" 2>/dev/null
    sysctl -w "net.ipv4.conf.@@vpn.disable_xfrm=1" 2>/dev/null
    sysctl -w "net.ipv6.conf.@@vpn.disable_policy=1" 2>/dev/null
    sysctl -w "net.ipv6.conf.@@vpn.disable_xfrm=1" 2>/dev/null

    # Flush any blocking forward XFRM policies installed by Android IpSecTunnel
    ip xfrm policy deleteall dir fwd 2>/dev/null

    # Flush IPv4 route cache to force new rtable allocations with DST_NOPOLICY
    ip route flush cache 2>/dev/null

    for stale_if in @@(iptables -t nat -S POSTROUTING 2>/dev/null | grep -E 'MASQUERADE.*-o (ipsec|tun|wg|cloudflared|tailscale)' | awk '{for(i=1;i<=NF;i++) if(@@i=="-o") print @@(i+1)}'); do
        [ "@@stale_if" != "@@vpn" ] && {
            iptables -t nat -D POSTROUTING -o "@@stale_if" -j MASQUERADE 2>/dev/null
            iptables -D FORWARD -o "@@stale_if" -j ACCEPT 2>/dev/null
            iptables -D FORWARD -i "@@stale_if" -j ACCEPT 2>/dev/null
        }
    done

    iptables -t nat -D POSTROUTING -o "@@vpn" -j MASQUERADE 2>/dev/null
    iptables -t nat -I POSTROUTING 1 -o "@@vpn" -j MASQUERADE 2>/dev/null
    ip6tables -t nat -D POSTROUTING -o "@@vpn" -j MASQUERADE 2>/dev/null
    ip6tables -t nat -I POSTROUTING 1 -o "@@vpn" -j MASQUERADE 2>/dev/null

    iptables -t mangle -D FORWARD -o "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
    iptables -t mangle -I FORWARD 1 -o "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
    iptables -t mangle -D FORWARD -i "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
    iptables -t mangle -I FORWARD 1 -i "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null

    ip6tables -t mangle -D FORWARD -o "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
    ip6tables -t mangle -I FORWARD 1 -o "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
    ip6tables -t mangle -D FORWARD -i "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
    ip6tables -t mangle -I FORWARD 1 -i "@@vpn" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null

    APPLIED_VPN="@@vpn"
    APPLIED_AP="@@aps"
}

clear_rules() {
    log "Clearing relay rules: aps=[@@APPLIED_AP] -> vpn=[@@APPLIED_VPN]"
    if [ -n "@@APPLIED_VPN" ] && [ -n "@@APPLIED_AP" ]; then
        for ap in @@APPLIED_AP; do
            tc filter del dev "@@ap" ingress pref 1 2>/dev/null
            sysctl -w "net.ipv4.conf.@@ap.disable_policy=0" 2>/dev/null
            sysctl -w "net.ipv4.conf.@@ap.disable_xfrm=0" 2>/dev/null
            sysctl -w "net.ipv6.conf.@@ap.disable_policy=0" 2>/dev/null
            sysctl -w "net.ipv6.conf.@@ap.disable_xfrm=0" 2>/dev/null
            for sub in /sys/class/net/"@@ap"/brif/*; do
                if [ -e "@@sub" ]; then
                    sub_name=@@(basename "@@sub")
                    tc filter del dev "@@sub_name" ingress pref 1 2>/dev/null
                    sysctl -w "net.ipv4.conf.@@sub_name.disable_policy=0" 2>/dev/null
                    sysctl -w "net.ipv4.conf.@@sub_name.disable_xfrm=0" 2>/dev/null
                    sysctl -w "net.ipv6.conf.@@sub_name.disable_policy=0" 2>/dev/null
                    sysctl -w "net.ipv6.conf.@@sub_name.disable_xfrm=0" 2>/dev/null
                    iptables -D FORWARD -i "@@sub_name" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
                    iptables -D FORWARD -i "@@APPLIED_VPN" -o "@@sub_name" -j ACCEPT 2>/dev/null
                    ip6tables -D FORWARD -i "@@sub_name" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
                    ip6tables -D FORWARD -i "@@APPLIED_VPN" -o "@@sub_name" -j ACCEPT 2>/dev/null
                fi
            done

            iptables -D FORWARD -i "@@ap" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
            iptables -D FORWARD -i "@@APPLIED_VPN" -o "@@ap" -j ACCEPT 2>/dev/null
            ip6tables -D FORWARD -i "@@ap" -o "@@APPLIED_VPN" -j ACCEPT 2>/dev/null
            ip6tables -D FORWARD -i "@@APPLIED_VPN" -o "@@ap" -j ACCEPT 2>/dev/null
        done
        ip route flush table @@RELAY_TABLE 2>/dev/null
        ip -6 route flush table @@RELAY_TABLE 2>/dev/null
        ip route flush cache 2>/dev/null
        iptables -t nat -D POSTROUTING -o "@@APPLIED_VPN" -j MASQUERADE 2>/dev/null
        ip6tables -t nat -D POSTROUTING -o "@@APPLIED_VPN" -j MASQUERADE 2>/dev/null
        iptables -t mangle -D FORWARD -o "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        iptables -t mangle -D FORWARD -i "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        ip6tables -t mangle -D FORWARD -o "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        ip6tables -t mangle -D FORWARD -i "@@APPLIED_VPN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null

        relay_if_list=@@(relay_ifaces @@APPLIED_AP)
        del_relay_rules @@relay_if_list
        del_relay_dns_dnat @@relay_if_list

        APPLIED_VPN=""
        APPLIED_AP=""
    fi
    restore_global_sysctls
}

probe_vpn() {
    if [ -f "@@ACTIVE_VPN_FILE" ]; then
        vpn_cand=@@(cat "@@ACTIVE_VPN_FILE" 2>/dev/null | tr -d '\r\n ')
        if [ -n "@@vpn_cand" ] && [ -d "/sys/class/net/@@vpn_cand" ]; then
            flags=@@(cat "/sys/class/net/@@vpn_cand/flags" 2>/dev/null || echo 0)
            if [ @@((flags & 1)) -ne 0 ]; then
                echo "@@vpn_cand"
                return 0
            fi
        fi
    fi
    for iface in /sys/class/net/*; do
        [ -d "@@iface" ] || continue
        n=@@(basename "@@iface")
        case "@@n" in
            tunl*|dummy*|lo|rmnet*|wlan*|sbd*|sbt*|ifb*|sit*|erspan*|gre*|ip6*|ip_*|aware*|thread*|umts*|wondertap*)
                continue
                ;;
            ipsec*|tun*|wg*|cloudflared*|tailscale*)
                flags=@@(cat "@@iface/flags" 2>/dev/null || echo 0)
                if [ @@((flags & 1)) -ne 0 ]; then
                    echo "@@n"
                    return 0
                fi
                ;;
        esac
    done
    return 1
}

probe_aps() {
    res=""
    candidates="@@CONFIGURED_APS"
    [ -z "@@candidates" ] && candidates="ap_br* softap* ap* rndis* usb*"
    for cand in @@candidates; do
        for iface in /sys/class/net/@@cand; do
            [ -d "@@iface" ] || continue
            flags=@@(cat "@@iface/flags" 2>/dev/null || echo 0)
            if [ @@((flags & 1)) -ne 0 ]; then
                n=@@(basename "@@iface")
                res="@@res @@n"
            fi
        done
    done
    echo "@@res" | tr ' ' '\n' | sort -u | xargs
}

echo @@@@ > "@@PID_FILE"
log "VPN tethering relay daemon started (pid=@@@@)"

while true; do
    cur_vpn=@@(probe_vpn)
    cur_aps=@@(probe_aps)

    if [ -n "@@cur_vpn" ] && [ -n "@@cur_aps" ]; then
        need_apply=0
        if [ "@@cur_vpn" != "@@APPLIED_VPN" ] || [ "@@cur_aps" != "@@APPLIED_AP" ]; then
            need_apply=1
        else
            for ap in @@cur_aps; do
                if [ -f "@@PASS_BPF_PIN" ]; then
                    if ! tc filter show dev "@@ap" ingress 2>/dev/null | grep -q "abox_relay_pass"; then
                        log "BPF filter missing or displaced on @@ap, re-applying rules"
                        need_apply=1
                        break
                    fi
                else
                    if ! tc filter show dev "@@ap" ingress 2>/dev/null | grep -q "pref 1"; then
                        log "TC filter missing on @@ap, re-applying rules"
                        need_apply=1
                        break
                    fi
                fi
            done
        fi

        if [ "@@need_apply" -eq 1 ]; then
            if [ -n "@@APPLIED_VPN" ]; then
                clear_rules
            fi
            apply_rules "@@cur_vpn" "@@cur_aps"
        else
            # Continuous health check: dynamically sync any newly assigned subnet into table 99
            for ap in @@cur_aps; do
                subnet=@@(ip route show dev "@@ap" table all 2>/dev/null | grep -E "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+" | awk '{print @@1}' | head -n1)
                if [ -n "@@subnet" ] && ! ip route show table @@RELAY_TABLE 2>/dev/null | grep -q "@@subnet"; then
                    log "Syncing newly assigned subnet @@subnet on @@ap into table @@RELAY_TABLE"
                    ip route replace "@@subnet" dev "@@ap" table @@RELAY_TABLE 2>/dev/null
                    ip rule add to "@@subnet" table @@RELAY_TABLE priority 999 2>/dev/null
                fi
            done
        fi
    else
        if [ -n "@@APPLIED_VPN" ]; then
            clear_rules
        fi
    fi

    sleep 2 &
    SLEEP_PID=@@!
    wait @@SLEEP_PID 2>/dev/null
    SLEEP_PID=""
done
"""


