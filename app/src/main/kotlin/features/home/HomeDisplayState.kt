// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.home

import app.AppState
import engine.singbox.runtime.SingBoxTrafficSample
import engine.singbox.runtime.SingBoxTrafficState
import engine.singbox.runtime.TrafficHistoryCapacity
import features.monitoring.MonitoringState
import utils.toReadableBytes
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class HomeServiceStatus {
    Enabled,
    Disabled,
    Error,
}

internal enum class HomeNetworkRowKind {
    Ipv4,
    Ipv6,
}

internal data class HomeNetworkRow(
    val kind: HomeNetworkRowKind,
    val value: String?,
)

internal data class HomeControllerState(
    val serviceStatus: HomeServiceStatus,
    val runMode: Int,
    val singBoxMode: Int,
)

internal data class HomeModeChange(
    val runtimeAppState: AppState,
    val persistSelection: Boolean,
    val runtimeAction: HomeModeRuntimeAction,
)

internal enum class HomeModeRuntimeAction {
    None,
    PatchRuntime,
}

internal data class HomeModeOperationState(
    val serviceOperationInProgress: Boolean,
    val modeOperationInProgress: Boolean,
)

internal data class HomeNetworkActivityState(
    val accumulatedUploadBytes: Long?,
    val accumulatedDownloadBytes: Long?,
    val uploadBytesPerSecond: Long?,
    val downloadBytesPerSecond: Long?,
    val networkSamples: List<SingBoxTrafficSample>,
) {
    val hasNetworkSamples: Boolean
        get() = networkSamples.isNotEmpty()
}

internal data class HomeMonitoringOverviewState(
    val serviceRunning: Boolean,
    val resourceCpuPercent: Double?,
    val resourceMemoryBytes: Long?,
    val activeConnectionCount: Int?,
    val directConnectionCount: Int?,
    val proxyConnectionCount: Int?,
    val todayTrafficBytes: Long,
    val networkRows: List<HomeNetworkRow>,
)

internal fun buildHomeControllerState(
    appState: AppState,
): HomeControllerState = HomeControllerState(
    serviceStatus = if (appState.proxyRunning) HomeServiceStatus.Enabled else HomeServiceStatus.Disabled,
    runMode = appState.runMode,
    singBoxMode = appState.singBoxMode,
)

internal fun buildHomeModeChange(
    appState: AppState,
    currentMode: Int,
    requestedMode: Int,
): HomeModeChange? {
    if (requestedMode == currentMode) return null
    return HomeModeChange(
        runtimeAppState = appState.copy(singBoxMode = requestedMode),
        persistSelection = true,
        runtimeAction = when {
            !appState.proxyRunning -> HomeModeRuntimeAction.None
            else -> HomeModeRuntimeAction.PatchRuntime
        },
    )
}

internal fun buildHomeModeOperationState(
    runtimeAction: HomeModeRuntimeAction,
): HomeModeOperationState = when (runtimeAction) {
    HomeModeRuntimeAction.None -> HomeModeOperationState(
        serviceOperationInProgress = false,
        modeOperationInProgress = false,
    )
    HomeModeRuntimeAction.PatchRuntime -> HomeModeOperationState(
        serviceOperationInProgress = false,
        modeOperationInProgress = true,
    )
}

internal fun buildHomeNetworkActivityState(
    appState: AppState,
    traffic: SingBoxTrafficState,
    networkSamples: List<SingBoxTrafficSample>,
): HomeNetworkActivityState {
    val trafficAvailable = appState.proxyRunning && traffic.connected
    return HomeNetworkActivityState(
        // Gate on a live traffic connection so a restart never shows the previous session's totals.
        accumulatedUploadBytes = traffic.totalUp.takeIf { trafficAvailable },
        accumulatedDownloadBytes = traffic.totalDown.takeIf { trafficAvailable },
        uploadBytesPerSecond = traffic.latest.up.takeIf { trafficAvailable },
        downloadBytesPerSecond = traffic.latest.down.takeIf { trafficAvailable },
        networkSamples = networkSamples.takeLast(HomeNetworkSampleLimit).takeIf { trafficAvailable }.orEmpty(),
    )
}

internal fun buildHomeMonitoringOverviewState(
    monitoringState: MonitoringState,
): HomeMonitoringOverviewState = HomeMonitoringOverviewState(
    serviceRunning = monitoringState.serviceRunning,
    resourceCpuPercent = monitoringState.resource.cpuPercent,
    resourceMemoryBytes = monitoringState.resource.memoryBytes,
    activeConnectionCount = monitoringState.connections.activeCount,
    directConnectionCount = monitoringState.connections.directCount,
    proxyConnectionCount = monitoringState.connections.proxyCount,
    todayTrafficBytes = monitoringState.traffic.today.total,
    networkRows = listOf(
        HomeNetworkRow(HomeNetworkRowKind.Ipv4, monitoringState.network.local.ipv4Addresses.firstOrNull()),
        HomeNetworkRow(HomeNetworkRowKind.Ipv6, monitoringState.network.local.ipv6Addresses.firstOrNull()),
    ),
)

internal fun formatHomeRuntimeBytes(bytes: Long?): String {
    return bytes?.toReadableBytes(keepTrailingZero = false) ?: HomeUnavailableValue
}

// Fixed chart window, sourced from the ring-buffer capacity so the axis can always be filled.
internal const val HomeNetworkSampleLimit = TrafficHistoryCapacity
internal const val HomeUnavailableValue = "—"

// Applying: a pending configuration change is being reloaded or restarted into the running core.
internal enum class HomeServiceOperation { Idle, Starting, Stopping, Applying }

// Retain only operation state; UI callbacks must not retain an Activity.
internal class HomeServiceOperationState {
    var operation by mutableStateOf(HomeServiceOperation.Idle)
    var modeOperationInProgress by mutableStateOf(false)
    var lastOperationFailed by mutableStateOf(false)
}

internal data class HomeUptimeParts(val days: Long, val clock: String)

internal fun homeServiceUptimeParts(uptimeMillis: Long?): HomeUptimeParts? {
    if (uptimeMillis == null || uptimeMillis < 0L) return null
    val seconds = uptimeMillis / 1_000L
    val days = seconds / 86_400L
    val totalHours = seconds / 3_600L
    val hours = if (days > 0L) totalHours % 24L else totalHours
    val minutes = (seconds / 60L) % 60L
    val remainingSeconds = seconds % 60L
    val clock = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
    return HomeUptimeParts(days, clock)
}

internal data class HomeCompactValue(val number: String, val unit: String)

internal fun formatHomeCompactBytes(bytes: Long?): HomeCompactValue? {
    if (bytes == null) return null
    val nonNegative = bytes.coerceAtLeast(0L)
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    var value = nonNegative.toDouble()
    var unitIndex = 0
    // Thresholds account for rounding so the shown number never exceeds three significant digits
    // (e.g. 999.6 KB becomes 0.98 MB rather than "1000 KB", 9.996 MB becomes "10.0 MB").
    while (value >= 999.5 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val unit = units[unitIndex]
    val number = if (unitIndex == 0) {
        nonNegative.toString()
    } else when {
        value < 9.995 -> String.format(Locale.ROOT, "%.2f", value)
        value < 99.95 -> String.format(Locale.ROOT, "%.1f", value)
        else -> String.format(Locale.ROOT, "%.0f", value)
    }
    return HomeCompactValue(number, unit)
}

internal const val HomeSparklineMinScale = 2048L

internal fun nextHomeSparklineScale(previous: Long, windowMax: Long): Long =
    maxOf(windowMax, (previous * 0.85).toLong(), HomeSparklineMinScale)
