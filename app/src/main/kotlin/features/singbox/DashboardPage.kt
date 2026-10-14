// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(
    kotlinx.coroutines.FlowPreview::class,
)

package features.singbox

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.AppServices
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalHomeServiceControl
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.R
import app.collectAppState
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.SingBoxModeRule
import app.navigation.Navigator
import app.navigation.Route as AppRoute
import engine.singbox.runtime.SingBoxTrafficSample
import engine.singbox.runtime.SingBoxTrafficState
import features.home.HomeModeRuntimeAction
import features.home.HomeNetworkActivityState
import features.home.buildHomeControllerState
import features.home.buildHomeModeChange
import features.home.buildHomeModeOperationState
import features.home.buildHomeMonitoringOverviewState
import features.home.buildHomeNetworkActivityState
import features.home.formatHomeCompactBytes
import features.monitoring.MonitoringIntent
import features.monitoring.ObserveMonitoring
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import ui.LocalSystemBarOverride
import ui.components.AsteriskScaffold
import ui.components.AsteriskSegmentItem
import ui.components.AsteriskSegmentedControl
import ui.icons.AsteriskIcons as Icons
import ui.isInDarkTheme
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import kotlin.time.Duration.Companion.milliseconds

private data class HomeTrafficPresentation(
    val traffic: SingBoxTrafficState,
    val samples: List<SingBoxTrafficSample>,
)

private fun AppServices.homeTrafficPresentationSnapshot(): HomeTrafficPresentation {
    val runtimeState = singBoxRuntime.state.value
    return HomeTrafficPresentation(
        traffic = runtimeState.traffic,
        samples = singBoxRuntime.trafficHistorySnapshot(HomeNetworkHistoryLimit),
    )
}

private fun AppServices.homeMonitoringOverviewSnapshot() =
    buildHomeMonitoringOverviewState(monitoring.state.value)

private const val HomeUiSampleIntervalMillis = 1_000L
private const val HomeNetworkHistoryLimit = 60

@Composable
fun SingBoxDashboardPage(
    padding: PaddingValues,
) {
    val override = LocalSystemBarOverride.current
    DisposableEffect(override) {
        override.lightStatusBars = false
        onDispose { override.lightStatusBars = null }
    }

    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val services = LocalAppServices.current
    val trafficPresentation by remember(services.singBoxRuntime) {
        services.singBoxRuntime.state
            .map { state -> state.traffic }
            .sample(HomeUiSampleIntervalMillis.milliseconds)
            .map { traffic ->
                HomeTrafficPresentation(
                    traffic = traffic,
                    samples = services.singBoxRuntime.trafficHistorySnapshot(HomeNetworkHistoryLimit),
                )
            }
            .distinctUntilChanged()
    }.collectAsState(initial = services.homeTrafficPresentationSnapshot())
    val monitoringOverviewState by remember(services.monitoring) {
        services.monitoring.state
            .map(::buildHomeMonitoringOverviewState)
            .distinctUntilChanged()
            .sample(HomeUiSampleIntervalMillis.milliseconds)
    }.collectAsState(initial = services.homeMonitoringOverviewSnapshot())
    val navigator = LocalNavigator.current
    val isWideScreen = LocalIsWideScreen.current
    ObserveMonitoring(MonitoringIntent.Home)
    val serviceControl = LocalHomeServiceControl.current
    val serviceOperationInProgress = serviceControl.serviceOperationInProgress
    val modeOperationInProgress = serviceControl.modeOperationInProgress
    val controllerState = remember(appState.proxyRunning, appState.runMode, appState.singBoxMode) {
        buildHomeControllerState(appState)
    }
    val networkActivityState = remember(appState.proxyRunning, trafficPresentation) {
        buildHomeNetworkActivityState(
            appState = appState,
            traffic = trafficPresentation.traffic,
            networkSamples = trafficPresentation.samples,
        )
    }
    val latestAppState = rememberUpdatedState(appState)

    val modeFailedMessage = stringResource(R.string.home_mode_change_failed)

    fun changeMode(mode: Int) {
        if (serviceControl.busy) return
        val stateSnapshot = latestAppState.value
        val modeChange = buildHomeModeChange(
            appState = stateSnapshot,
            currentMode = stateSnapshot.singBoxMode,
            requestedMode = mode,
        ) ?: return
        val previousMode = stateSnapshot.singBoxMode
        if (modeChange.persistSelection) {
            updateAppState { state -> state.copy(singBoxMode = mode) }
        }
        if (modeChange.runtimeAction != HomeModeRuntimeAction.None) {
            val operationState = buildHomeModeOperationState(modeChange.runtimeAction)
            serviceControl.modeOperationInProgress = operationState.modeOperationInProgress
            services.appScope.launch {
                try {
                    val failure = when (modeChange.runtimeAction) {
                        HomeModeRuntimeAction.None -> null
                        HomeModeRuntimeAction.PatchRuntime ->
                            services.singBoxRuntime.patchMode(modeChange.runtimeAppState).exceptionOrNull()
                    }
                    failure?.let { error ->
                        if (modeChange.persistSelection) {
                            updateAppState { state ->
                                if (state.singBoxMode == mode) {
                                    state.copy(singBoxMode = previousMode)
                                } else {
                                    state
                                }
                            }
                        }
                        services.tipNotifier.showError(error, modeFailedMessage)
                    }
                } finally {
                    serviceControl.modeOperationInProgress = false
                }
            }
        }
    }

    val statusBarHeightPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val narrowScrollState = rememberScrollState()
    val heroScrollState = rememberScrollState()
    val cardsScrollState = rememberScrollState()
    // The status bar scrim only fades in once content scrolls under it, so at rest the rings reach the top edge.
    val contentScrolled by remember {
        derivedStateOf { narrowScrollState.value > 0 || heroScrollState.value > 0 || cardsScrollState.value > 0 }
    }
    // Stats recede while the service is stopped (they only show "—"), keeping the power button the focus.
    val statsAlpha by animateFloatAsState(
        targetValue = if (appState.proxyRunning) 1f else 0.6f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "homeStatsAlpha",
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (contentScrolled) 1f else 0f,
        animationSpec = tween(200),
        label = "homeStatusBarScrim",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        HomeHeroBackground(
            running = appState.proxyRunning,
            modifier = Modifier.matchParentSize(),
        )
        AsteriskScaffold(
            containerColor = Color.Transparent,
        ) { innerPadding ->
            val contentPadding = pageContentPaddingWithCutout(
                innerPadding = innerPadding,
                outerPadding = padding,
                isWideScreen = isWideScreen,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Without a top app bar nothing else reserves the status bar, so add whatever part of it the
                // scaffold padding does not already cover. The small bottom extra plus the navigation bar's own
                // 10 dp inset leaves a ~16 dp gap, matching the page's spacing rhythm.
                val statusBarExtra = (statusBarTop - contentPadding.calculateTopPadding()).coerceAtLeast(0.dp)
                val listPadding = pageListPadding(contentPadding, bottomExtra = 6.dp, topExtra = statusBarExtra + 8.dp)
                val availableHeight = (
                    this@BoxWithConstraints.maxHeight -
                        listPadding.calculateTopPadding() -
                        listPadding.calculateBottomPadding()
                    ).coerceAtLeast(0.dp)
                val isWide = LocalIsWideScreen.current || (maxWidth > maxHeight && maxWidth >= 600.dp)

                if (isWide) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(listPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 980.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(heroScrollState),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                HomePowerHero(
                                    running = appState.proxyRunning,
                                    serviceControl = serviceControl,
                                    availableHeight = availableHeight,
                                )
                                Spacer(Modifier.height(16.dp))
                                AsteriskSegmentedControl(
                                    items = homeModeOptions().map { option ->
                                        AsteriskSegmentItem(value = option.mode, label = option.label)
                                    },
                                    selectedValue = controllerState.singBoxMode,
                                    onSelected = ::changeMode,
                                    enabled = !serviceOperationInProgress && !modeOperationInProgress,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(cardsScrollState),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                DashboardCards(
                                    appStateRunning = appState.proxyRunning,
                                    activeConnectionCount = monitoringOverviewState.activeConnectionCount,
                                    networkActivityState = networkActivityState,
                                    navigator = navigator,
                                    modifier = Modifier.graphicsLayer { alpha = statsAlpha },
                                )
                                HomeInfoStrip(
                                    resourceText = homeResourceSummary(monitoringOverviewState),
                                    runModeText = runModeLabel(controllerState.runMode),
                                    runModeDetail = runModeDataPlane(controllerState.runMode, appState.ebpfLocalDataPlane),
                                    onResourceClick = { navigator.push(AppRoute.ResourceMonitor) },
                                    onRunModeClick = { navigator.push(AppRoute.NetworkMonitor) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer { alpha = statsAlpha },
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(narrowScrollState)
                            .padding(listPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The controls and cards are measured first and keep their natural height just above the
                        // navigation bar; the power hero takes exactly the space left, so the page fits one screen.
                        HomeHeroAboveControls(
                            viewportHeight = availableHeight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 560.dp),
                            hero = {
                                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                    HomePowerHero(
                                        running = appState.proxyRunning,
                                        serviceControl = serviceControl,
                                        availableHeight = maxHeight,
                                        slotWidth = maxWidth,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            },
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(16.dp))
                                AsteriskSegmentedControl(
                                    items = homeModeOptions().map { option ->
                                        AsteriskSegmentItem(value = option.mode, label = option.label)
                                    },
                                    selectedValue = controllerState.singBoxMode,
                                    onSelected = ::changeMode,
                                    enabled = !serviceOperationInProgress && !modeOperationInProgress,
                                )
                                Spacer(Modifier.height(20.dp))
                                DashboardCards(
                                    appStateRunning = appState.proxyRunning,
                                    activeConnectionCount = monitoringOverviewState.activeConnectionCount,
                                    networkActivityState = networkActivityState,
                                    navigator = navigator,
                                    modifier = Modifier.graphicsLayer { alpha = statsAlpha },
                                )
                                Spacer(Modifier.height(12.dp))
                                HomeInfoStrip(
                                    resourceText = homeResourceSummary(monitoringOverviewState),
                                    runModeText = runModeLabel(controllerState.runMode),
                                    runModeDetail = runModeDataPlane(controllerState.runMode, appState.ebpfLocalDataPlane),
                                    onResourceClick = { navigator.push(AppRoute.ResourceMonitor) },
                                    onRunModeClick = { navigator.push(AppRoute.NetworkMonitor) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer { alpha = statsAlpha },
                                )
                            }
                        }
                    }
                }
            }
        }
        // Repaint the background over the status bar band once content scrolls, so cards never sit under the
        // white status bar icons; same composable, so the band matches the gradient seamlessly.
        HomeHeroBackground(
            running = appState.proxyRunning,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = scrimAlpha }
                .drawWithContent {
                    clipRect(bottom = statusBarHeightPx.toFloat()) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
    }
}

@Composable
private fun DashboardCards(
    appStateRunning: Boolean,
    activeConnectionCount: Int?,
    networkActivityState: HomeNetworkActivityState,
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val darkTheme = isInDarkTheme()
    val connectionsBadgeColor = if (darkTheme) Color(0xFFFF6B6F) else Color(0xFFE5484D)
    val sessionTrafficBadgeColor = if (darkTheme) Color(0xFF4CC274) else Color(0xFF2FA55A)
    // Fixed blue rather than the dynamic primary: with warm wallpapers primary turns amber and download
    // became indistinguishable from the orange upload card.
    val downloadBadgeColor = if (darkTheme) Color(0xFF6EA8FF) else Color(0xFF2F7CF6)
    val uploadBadgeColor = if (darkTheme) Color(0xFFFFA24D) else Color(0xFFF08A24)

    val sessionTotalBytes = if (networkActivityState.accumulatedUploadBytes == null && networkActivityState.accumulatedDownloadBytes == null) {
        null
    } else {
        (networkActivityState.accumulatedUploadBytes ?: 0L) + (networkActivityState.accumulatedDownloadBytes ?: 0L)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            HomeStatCard(
                title = stringResource(R.string.home_monitor_connections),
                icon = Icons.Rounded.Lan,
                badgeColor = connectionsBadgeColor,
                value = if (appStateRunning) activeConnectionCount?.toString() else null,
                onClick = { navigator.push(AppRoute.ConnectionsMonitor) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = HomeStatCardMinHeight),
            )
            HomeStatCard(
                title = stringResource(R.string.home_stat_session_traffic),
                icon = Icons.Rounded.DataUsage,
                badgeColor = sessionTrafficBadgeColor,
                compactValue = formatHomeCompactBytes(sessionTotalBytes),
                onClick = { navigator.push(AppRoute.TrafficMonitor) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = HomeStatCardMinHeight),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            HomeStatCard(
                title = stringResource(R.string.monitor_connections_download),
                icon = Icons.Rounded.Download,
                badgeColor = downloadBadgeColor,
                compactValue = formatHomeCompactBytes(networkActivityState.downloadBytesPerSecond),
                unitSuffix = "/s",
                onClick = { navigator.push(AppRoute.TrafficMonitor) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = HomeStatCardMinHeight),
                sparkline = {
                    HomeSparkline(
                        samples = networkActivityState.networkSamples.map { it.down },
                        color = downloadBadgeColor,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(34.dp),
                    )
                },
            )
            HomeStatCard(
                title = stringResource(R.string.monitor_connections_upload),
                icon = Icons.Rounded.Upload,
                badgeColor = uploadBadgeColor,
                compactValue = formatHomeCompactBytes(networkActivityState.uploadBytesPerSecond),
                unitSuffix = "/s",
                onClick = { navigator.push(AppRoute.TrafficMonitor) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = HomeStatCardMinHeight),
                sparkline = {
                    HomeSparkline(
                        samples = networkActivityState.networkSamples.map { it.up },
                        color = uploadBadgeColor,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(34.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun homeModeOptions(): List<HomeModeOption> {
    return listOf(
        HomeModeOption(
            SingBoxModeRule,
            stringResource(R.string.sing_box_mode_rule),
        ),
        HomeModeOption(
            SingBoxModeGlobal,
            stringResource(R.string.sing_box_mode_global),
        ),
        HomeModeOption(
            SingBoxModeDirect,
            stringResource(R.string.sing_box_mode_direct),
        ),
    )
}

// All four stat cards share one height: the height of a card with a sparkline (header, value, curve clearance).
private val HomeStatCardMinHeight = 118.dp

private data class HomeModeOption(
    val mode: Int,
    val label: String,
)
