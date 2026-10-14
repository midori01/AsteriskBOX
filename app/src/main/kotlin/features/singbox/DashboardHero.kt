// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.HomeServiceControl
import app.LocalAppServices
import app.R
import app.modes.RunModeEbpf
import app.modes.RunModeTproxy
import engine.singbox.normalizedEbpfLocalDataPlane
import features.home.HomeCompactValue
import features.home.HomeMonitoringOverviewState
import features.home.HomeServiceOperation
import features.home.HomeSparklineMinScale
import features.home.HomeUnavailableValue
import features.home.HomeUptimeParts
import features.home.homeServiceUptimeParts
import features.home.nextHomeSparklineScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import ui.icons.AsteriskIcons as Icons
import ui.isInDarkTheme
import ui.theme.LocalReduceMotion
import ui.theme.AsteriskShapeTokens
import utils.toReadableBytes
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun HomeHeroBackground(
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = isInDarkTheme()

    val stoppedFraction by animateFloatAsState(
        targetValue = if (running) 0f else 1f,
        animationSpec = tween(HeroStateTransitionMillis, easing = FastOutSlowInEasing),
        label = "heroStoppedFraction",
    )

    Spacer(
        modifier = modifier.drawWithCache {
            val width = size.width
            val height = size.height

            // Stay within the primary hue: mixing in tertiary (often a distant hue such as green next to purple)
            // turns the gradient muddy grey. The text-bearing top stays around tone 40-50 (light) / 30 (dark).
            val rawStops = if (!darkTheme) {
                arrayOf(
                    colorScheme.primary,
                    lerp(colorScheme.primary, colorScheme.inversePrimary, 0.25f),
                    lerp(colorScheme.primaryContainer, colorScheme.surfaceContainerLow, 0.35f),
                    colorScheme.surfaceContainerLow,
                )
            } else {
                arrayOf(
                    colorScheme.primaryContainer,
                    lerp(colorScheme.primaryContainer, colorScheme.primary, 0.12f),
                    lerp(colorScheme.primaryContainer, colorScheme.surfaceContainer, 0.6f),
                    colorScheme.surface,
                )
            }

            val c0 = desaturate(rawStops[0], stoppedFraction)
            val c1 = desaturate(rawStops[1], stoppedFraction)
            val c2 = desaturate(rawStops[2], stoppedFraction)
            val c3 = desaturate(rawStops[3], stoppedFraction)

            val verticalBrush = Brush.verticalGradient(
                0.00f to c0,
                0.38f to c1,
                0.70f to c2,
                1.00f to c3,
                startY = 0f,
                endY = height,
            )

            val rawHighlight = if (!darkTheme) {
                colorScheme.inversePrimary.copy(alpha = 0.30f)
            } else {
                colorScheme.primary.copy(alpha = 0.16f)
            }
            val highlightColor = desaturate(rawHighlight, stoppedFraction)
            val radialCenter = Offset(width * 0.85f, height * 0.08f)
            val radialRadius = (width * 0.9f).coerceAtLeast(1f)
            val radialBrush = Brush.radialGradient(
                colors = listOf(highlightColor, Color.Transparent),
                center = radialCenter,
                radius = radialRadius,
            )

            onDrawBehind {
                drawRect(brush = verticalBrush)
                drawRect(brush = radialBrush)
            }
        },
    )
}

private fun grayOf(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return Color(red = lum, green = lum, blue = lum, alpha = c.alpha)
}

private fun desaturate(c: Color, fraction: Float): Color {
    return lerp(c, grayOf(c), fraction * 0.85f)
}

@Composable
internal fun HomePowerHero(
    running: Boolean,
    serviceControl: HomeServiceControl,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
    // Set when the hero fills a fixed slot above the mode control (portrait): it then positions itself so the
    // full-size rings end just above that control instead of being centred and shrunk.
    slotWidth: Dp? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = isInDarkTheme()
    val reduceMotion = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current

    val services = LocalAppServices.current
    val uptimeMillis by produceState<Long?>(null, running) {
        value = null
        if (!running) return@produceState
        while (true) {
            val startedAt = services.singBoxRuntime.state.value.serviceStartedAtMillis
            value = if (startedAt > 0L) (System.currentTimeMillis() - startedAt).coerceAtLeast(0L) else null
            delay(1000L.milliseconds)
        }
    }

    // Only a fresh failure vibrates; re-entering the page with a stale failure flag stays silent.
    LaunchedEffect(serviceControl) {
        snapshotFlow { serviceControl.lastOperationFailed }
            .drop(1)
            .collect { failed ->
                if (failed) haptic.performHapticFeedback(HapticFeedbackType.Reject)
            }
    }

    val hintText = stringResource(
        when (serviceControl.operation) {
            HomeServiceOperation.Starting -> R.string.home_service_starting
            HomeServiceOperation.Stopping -> R.string.home_service_stopping
            HomeServiceOperation.Applying -> R.string.home_service_applying
            HomeServiceOperation.Idle -> when {
                serviceControl.lastOperationFailed && !running -> R.string.home_hero_start_failed
                running -> R.string.home_hero_tap_stop
                else -> R.string.home_hero_tap_start
            }
        },
    )

    // Size the button from the space actually left for the hero (after the hint and clock, which scale with the
    // font size), so the whole home page fits on one screen at any density, display size, or font scale.
    val zoneMax = (availableHeight - heroTextBlockHeight() - HeroVerticalMargin)
        .coerceAtLeast(HeroMinButtonDiameter + 16.dp)
    val buttonDiameter = (zoneMax / 1.7f).coerceIn(HeroMinButtonDiameter, HeroMaxButtonDiameter)
    val zoneHeight = (buttonDiameter * 1.9f).coerceAtMost(zoneMax).coerceAtLeast(buttonDiameter + 16.dp)
    val buttonRadius = buttonDiameter / 2
    val textBlockHeight = heroTextBlockHeight()
    // Lift the hero (never lower than centred, never above the slot top) until the outermost ring, breathing
    // overshoot included, ends ~8 dp above the mode control; rings shrink only if even that is not enough.
    val heroTopOffset = if (slotWidth != null) {
        val ringRadius = minOf(buttonRadius * 3.7f, slotWidth / 2)
        val centered = ((availableHeight - textBlockHeight - zoneHeight) / 2).coerceAtLeast(0.dp)
        val ringFit = availableHeight + 8.dp - ringRadius * 1.05f - textBlockHeight - zoneHeight / 2
        ringFit.coerceAtMost(centered).coerceAtLeast(0.dp)
    } else {
        0.dp
    }

    val busy = serviceControl.serviceOperationInProgress
    val targetAmp = if (reduceMotion) 0f else if (busy) 1.35f else if (running) 1f else 0f
    val amp by animateFloatAsState(
        targetValue = targetAmp,
        animationSpec = tween(HeroStateTransitionMillis, easing = FastOutSlowInEasing),
        label = "ringAmp",
    )

    var phase by remember { mutableFloatStateOf(0f) }
    var zoneCenter by remember { mutableStateOf(Offset.Unspecified) }
    // Cycles per second; eased so the switch between the busy (0.9 s) and idle (2 s) cadence never jolts.
    val speed = animateFloatAsState(
        targetValue = if (busy) 1f / 0.9f else 1f / 2.0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "ringSpeed",
    )
    val currentRunning by rememberUpdatedState(running)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val currentBusy by rememberUpdatedState(busy)
    val animating by remember {
        derivedStateOf { !currentReduceMotion && (currentRunning || currentBusy || amp > 0.001f) }
    }

    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        var lastNanos: Long? = null
        while (true) {
            withFrameNanos { frameTimeNanos ->
                val prev = lastNanos
                lastNanos = frameTimeNanos
                if (prev != null) {
                    val dt = ((frameTimeNanos - prev) / 1_000_000_000.0f).coerceAtMost(0.05f)
                    phase = (phase + dt * speed.value) % 1f
                }
            }
        }
    }

    val burst = remember { Animatable(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press && !currentReduceMotion) {
                burst.snapTo(1f)
                burst.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 380f))
            }
        }
    }

    // A light disc in both themes keeps the power button the focal point; in dark theme the inverse roles give
    // a tone-90 disc with tone-40 icons, mirroring the light theme's white disc and primary icon.
    // The button stays pressed while the finger is down and springs back on release.
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "powerPressScale",
    )
    val buttonColor = if (!darkTheme) Color.White else colorScheme.inverseSurface
    val iconTint by animateColorAsState(
        targetValue = when {
            serviceControl.lastOperationFailed && !running ->
                if (!darkTheme) colorScheme.error else colorScheme.errorContainer
            running -> if (!darkTheme) colorScheme.primary else colorScheme.inversePrimary
            else -> if (!darkTheme) colorScheme.onSurfaceVariant else colorScheme.inverseOnSurface.copy(alpha = 0.62f)
        },
        animationSpec = tween(HeroStateTransitionMillis, easing = FastOutSlowInEasing),
        label = "powerIconTint",
    )

    val buttonLabel = stringResource(
        when (serviceControl.operation) {
            HomeServiceOperation.Starting -> R.string.home_service_starting
            HomeServiceOperation.Stopping -> R.string.home_service_stopping
            HomeServiceOperation.Applying -> R.string.home_service_applying
            HomeServiceOperation.Idle -> if (running) R.string.home_service_stop else R.string.home_service_start
        },
    )
    val buttonStateDescription = stringResource(
        when {
            serviceControl.lastOperationFailed && !running -> R.string.home_hero_start_failed
            running -> R.string.home_service_enabled
            else -> R.string.home_service_disabled
        },
    )

    // Rings are drawn behind the whole column (not just the button zone) so they sit under the hint and uptime.
    Column(
        modifier = modifier.drawBehind {
            val centerOffset = zoneCenter
            if (centerOffset.isUnspecified) return@drawBehind
            val buttonRadiusPx = buttonRadius.toPx()
            // Keep the outermost ring inside the content width and, in a fixed slot, end it (breathing overshoot
            // included) about 8 dp above the mode control, whose top sits 16 dp below the slot bottom.
            val verticalLimit = if (slotWidth != null) {
                (size.height - centerOffset.y + 8.dp.toPx()) / 1.05f
            } else {
                Float.MAX_VALUE
            }
            val outerRadius = minOf(buttonRadiusPx * 3.7f, size.width / 2f, verticalLimit)
                .coerceAtLeast(buttonRadiusPx * 1.7f)
            val innerRadius = minOf(buttonRadiusPx * 1.58f, outerRadius * 0.9f)
            val baseRadii = floatArrayOf(
                innerRadius,
                // Always between the inner and outer ring, so a squeezed outer ring never inverts the order.
                minOf(buttonRadiusPx * 2.56f, innerRadius + (outerRadius - innerRadius) * 0.55f),
                outerRadius,
            )
            val ringColor = if (!darkTheme) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.06f)
            val ringEdgeColor = if (!darkTheme) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.11f)
            for (i in 0..2) {
                val w = 0.5f - 0.5f * cos(2.0 * PI * (phase - i * 0.13f)).toFloat()
                val scale = 1f + amp * (0.022f + i * 0.012f) * w + burst.value * 0.03f * (1f - i * 0.3f)
                val ringAlpha = (1f - amp * 0.3f * w).coerceIn(0f, 1f)
                val r = baseRadii[i] * scale
                if (r > 0f && ringAlpha > 0.001f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.62f to ringColor.copy(alpha = ringColor.alpha * ringAlpha),
                            1.0f to ringEdgeColor.copy(alpha = ringEdgeColor.alpha * ringAlpha),
                            center = centerOffset,
                            radius = r,
                        ),
                        radius = r,
                        center = centerOffset,
                    )
                }
            }
            val rippleBaseColor = if (!darkTheme) Color.White.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.40f)
            val rippleEnd = outerRadius * 0.98f
            for (k in 0..1) {
                val q = (phase + k * 0.5f) % 1f
                val easeOut = 1f - (1f - q) * (1f - q) * (1f - q)
                val rippleRadius = buttonRadiusPx + easeOut * (rippleEnd - buttonRadiusPx)
                // Fade in over the first 15% of travel so a ripple never pops out of the button edge.
                val fadeIn = (q / 0.15f).coerceIn(0f, 1f).let { t -> t * t * (3f - 2f * t) }
                val rippleAlpha = (minOf(1f, amp) * 0.55f * fadeIn * (1f - q).toDouble().pow(1.6).toFloat())
                    .coerceIn(0f, 1f)
                if (rippleAlpha > 0.001f && rippleRadius > 0f) {
                    drawCircle(
                        color = rippleBaseColor.copy(alpha = rippleBaseColor.alpha * rippleAlpha),
                        radius = rippleRadius,
                        center = centerOffset,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (heroTopOffset > 0.dp) Spacer(Modifier.height(heroTopOffset))
        val hintFailed = serviceControl.lastOperationFailed && !running && !busy
        Crossfade(targetState = hintText to hintFailed, animationSpec = tween(220), label = "heroHint") { (text, failed) ->
            if (failed) {
                // A start failure must not read like an ordinary hint.
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onErrorContainer,
                    maxLines = 1,
                    modifier = Modifier
                        .background(colorScheme.errorContainer, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Fixed height (scaled with the font size) so swapping the uptime and "Not running" never moves the button.
        val clockHeight = with(LocalDensity.current) { (HeroClockFontSize * 1.25f).toDp() }
        val clockState = when {
            running -> HeroClockState.Running
            busy -> HeroClockState.Pending
            else -> HeroClockState.Stopped
        }
        Crossfade(
            targetState = clockState,
            animationSpec = tween(HeroStateTransitionMillis / 2),
            modifier = Modifier.height(clockHeight),
            label = "heroClock",
        ) { state ->
            Box(modifier = Modifier.height(clockHeight), contentAlignment = Alignment.Center) {
                if (state == HeroClockState.Running) {
                    // Until the runtime reports a start timestamp, show the unavailable mark rather than a made-up 00:00:00.
                    val parts = homeServiceUptimeParts(uptimeMillis) ?: HomeUptimeParts(0L, HomeUnavailableValue)
                    val daysText = if (parts.days > 0L) {
                        pluralStringResource(R.plurals.home_uptime_days, parts.days.toInt(), parts.days.toInt())
                    } else {
                        null
                    }
                    val uptimeSemantics = stringResource(
                        R.string.home_service_uptime,
                        if (daysText != null) "$daysText ${parts.clock}" else parts.clock,
                    )
                    // The day counter shares the clock's line and baseline, so passing 24 h never adds a row.
                    BasicText(
                        text = buildAnnotatedString {
                            if (daysText != null) {
                                withStyle(SpanStyle(fontSize = 20.sp, color = Color.White.copy(alpha = 0.85f))) {
                                    append(daysText)
                                    append("  ")
                                }
                            }
                            append(parts.clock)
                        },
                        style = heroClockStyle(Color.White),
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = uptimeSemantics },
                    )
                } else if (state == HeroClockState.Pending) {
                    // Starting: a dimmed zero clock instead of a contradictory "Not running" under "Starting…".
                    BasicText(
                        text = "00:00:00",
                        style = heroClockStyle(Color.White.copy(alpha = 0.55f)),
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.home_hero_not_running),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(zoneHeight)
                .onPlaced { coordinates ->
                    zoneCenter = coordinates.positionInParent() +
                        Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                HomeBusyArc(
                    diameter = buttonDiameter + 22.dp,
                    reduceMotion = reduceMotion,
                )
            }

            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    serviceControl.toggleService()
                },
                modifier = Modifier
                    .size(buttonDiameter)
                    .graphicsLayer {
                        val p = phase
                        val a = amp
                        val w0 = 0.5f - 0.5f * cos(2.0 * PI * p).toFloat()
                        val s = (1f + a * 0.014f * w0) * pressScale.value
                        scaleX = s
                        scaleY = s
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = buttonLabel
                        stateDescription = buttonStateDescription
                    },
                enabled = !serviceControl.busy,
                shape = CircleShape,
                color = buttonColor,
                shadowElevation = 10.dp,
                interactionSource = interactionSource,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(buttonDiameter * 0.4f),
                        tint = iconTint,
                    )
                }
            }
        }
    }
}

private const val HeroStateTransitionMillis = 800
private val HeroClockFontSize = 44.sp
private val HeroMinButtonDiameter = 72.dp
private val HeroMaxButtonDiameter = 128.dp
private val HeroVerticalMargin = 16.dp

// Hint (label line + capsule padding), gap, fixed-height clock, gap: everything above the button zone.
@Composable
private fun heroTextBlockHeight(): Dp = with(LocalDensity.current) {
    20.sp.toDp() + (HeroClockFontSize * 1.25f).toDp()
} + 8.dp + 4.dp + 8.dp

/** The smallest hero that still shows the hint, the clock, and a minimum-size power button. */
@Composable
internal fun homeHeroMinHeight(): Dp = heroTextBlockHeight() + HeroMinButtonDiameter + 16.dp + HeroVerticalMargin

/**
 * Measures [controls] first at their natural height and gives [hero] exactly the viewport height left above them,
 * so the page fills one screen without clipping. Only when even a minimum-size hero does not fit does the content
 * grow taller than the viewport, and the enclosing scroll container takes over.
 */
@Composable
internal fun HomeHeroAboveControls(
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
    hero: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val minHeroHeight = homeHeroMinHeight()
    Layout(contents = listOf(hero, controls), modifier = modifier) { (heroMeasurables, controlMeasurables), constraints ->
        val width = constraints.maxWidth
        val controlConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val controlPlaceables = controlMeasurables.map { measurable -> measurable.measure(controlConstraints) }
        val controlsHeight = controlPlaceables.sumOf { placeable -> placeable.height }
        val heroHeight = maxOf(viewportHeight.roundToPx() - controlsHeight, minHeroHeight.roundToPx())
        val heroPlaceables = heroMeasurables.map { measurable -> measurable.measure(Constraints.fixed(width, heroHeight)) }
        layout(width, heroHeight + controlsHeight) {
            heroPlaceables.forEach { placeable -> placeable.place(0, 0) }
            var y = heroHeight
            controlPlaceables.forEach { placeable ->
                placeable.place((width - placeable.width) / 2, y)
                y += placeable.height
            }
        }
    }
}

private enum class HeroClockState { Running, Pending, Stopped }

// Proportional face with tabular figures and slightly tightened tracking: digits stay put without the wide gaps a
// monospace font leaves around the colons.
private fun heroClockStyle(color: Color) = TextStyle(
    color = color,
    fontSize = HeroClockFontSize,
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.Medium,
    letterSpacing = (-0.6).sp,
)
private const val HomeSparklineWindow = 40

// Composed only while a start/stop is in flight, so the infinite transition never ticks at rest.
@Composable
private fun HomeBusyArc(
    diameter: Dp,
    reduceMotion: Boolean,
) {
    val rotation = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "busyArcTransition").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "arcRotation",
        )
    }
    Canvas(modifier = Modifier.size(diameter)) {
        drawArc(
            color = Color.White.copy(alpha = 0.9f),
            startAngle = rotation?.value ?: 0f,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
internal fun HomeStatCard(
    title: String,
    icon: ImageVector,
    badgeColor: Color,
    value: String?,
    unit: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sparkline: (@Composable BoxScope.() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = isInDarkTheme()
    val shape = AsteriskShapeTokens.PageCard
    // On the near-white light card the saturated badge hue is too weak for 14 sp text; darken it for the title only.
    val titleColor = if (!darkTheme) lerp(badgeColor, Color.Black, 0.32f) else badgeColor
    val containerColor = if (!darkTheme) {
        colorScheme.surfaceContainerLowest.copy(alpha = 0.72f)
    } else {
        colorScheme.surfaceContainerHigh.copy(alpha = 0.60f)
    }
    val borderBrush = Brush.verticalGradient(
        if (!darkTheme) {
            listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.35f))
        } else {
            listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f))
        },
    )

    // Surface(onClick) merges its descendants, so TalkBack reads "title value unit" exactly once.
    Surface(
        onClick = onClick,
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = colorScheme.primary.copy(alpha = 0.18f),
                spotColor = colorScheme.primary.copy(alpha = 0.18f),
            )
            .border(width = 1.dp, brush = borderBrush, shape = shape)
            .semantics { role = Role.Button },
        shape = shape,
        color = containerColor,
    ) {
        // Fill the whole card so the sparkline always sits on the bottom edge of equal-height cards.
        Box(modifier = Modifier.fillMaxSize()) {
            // Without a sparkline the value sinks to the bottom of the equal-height card (title top, number bottom)
            // instead of hugging the title and leaving the lower half empty.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (sparkline == null) Modifier.fillMaxHeight() else Modifier)
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = if (sparkline != null) 42.dp else 16.dp,
                    ),
                verticalArrangement = if (sparkline == null) Arrangement.SpaceBetween else Arrangement.Top,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = RoundedCornerShape(7.dp),
                        color = badgeColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.White,
                            )
                        }
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (value != null) {
                    val annotated = buildAnnotatedString {
                        append(value)
                        if (!unit.isNullOrEmpty()) {
                            append(" ")
                            withStyle(
                                SpanStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                append(unit)
                            }
                        }
                    }
                    BasicText(
                        text = annotated,
                        autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 28.sp),
                        maxLines = 1,
                        style = TextStyle(
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                } else {
                    BasicText(
                        text = HomeUnavailableValue,
                        autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 28.sp),
                        maxLines = 1,
                        style = TextStyle(
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }

            sparkline?.invoke(this)
        }
    }
}

@Composable
internal fun HomeStatCard(
    title: String,
    icon: ImageVector,
    badgeColor: Color,
    compactValue: HomeCompactValue?,
    unitSuffix: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sparkline: (@Composable BoxScope.() -> Unit)? = null,
) {
    HomeStatCard(
        title = title,
        icon = icon,
        badgeColor = badgeColor,
        value = compactValue?.number,
        unit = compactValue?.unit?.let { if (unitSuffix != null) "$it$unitSuffix" else it },
        onClick = onClick,
        modifier = modifier,
        sparkline = sparkline,
    )
}

@Composable
internal fun HomeSparkline(
    samples: List<Long>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val visibleSamples = remember(samples) { samples.takeLast(HomeSparklineWindow) }
    val windowMax = remember(visibleSamples) { visibleSamples.maxOrNull() ?: 0L }

    val scaleState = remember { mutableLongStateOf(HomeSparklineMinScale) }
    // Each new sample starts one step to the right and glides into place, instead of the line jumping a step.
    val slide = remember { Animatable(0f) }
    LaunchedEffect(samples) {
        scaleState.longValue = nextHomeSparklineScale(scaleState.longValue, windowMax)
        if (!reduceMotion && visibleSamples.size >= 2) {
            slide.snapTo(1f)
            slide.animateTo(0f, tween(900, easing = LinearOutSlowInEasing))
        }
    }

    val animatedScale by animateFloatAsState(
        targetValue = scaleState.longValue.toFloat(),
        animationSpec = tween(400),
        label = "sparklineScale",
    )

    Canvas(modifier = modifier) {
        val top = 6.dp.toPx()
        // Keep the end dot (6dp radius) clear of the card's bottom border and its 24dp rounded corner.
        val base = size.height - 10.dp.toPx()
        val xStart = 12.dp.toPx()
        val xEnd = size.width - 20.dp.toPx()

        if (visibleSamples.size < 2) {
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(xStart, base),
                end = Offset(xEnd, base),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val currentScale = animatedScale.coerceAtLeast(1f)
        // Fixed spacing, right-aligned: a filling window grows in from the right instead of being stretched.
        val stepX = (xEnd - xStart) / (HomeSparklineWindow - 1)
        val shift = slide.value * stepX
        val points = visibleSamples.mapIndexed { index, v ->
            val fraction = (v.toFloat() / currentScale).coerceIn(0f, 1f)
            Offset(xEnd - (visibleSamples.lastIndex - index) * stepX + shift, base - fraction * (base - top))
        }

        val strokePath = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 0 until points.lastIndex) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val cx = (p0.x + p1.x) / 2f
                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
        }

        val fillPath = Path().apply {
            addPath(strokePath)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }

        // Fade the area fill towards the card edge so it does not end in a hard block.
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)),
                startY = top,
                endY = size.height,
            ),
        )

        drawPath(
            path = strokePath,
            color = color,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
        )

        val lastPoint = points.last()
        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = lastPoint,
        )
        drawCircle(
            color = color,
            radius = 4.2.dp.toPx(),
            center = lastPoint,
        )
    }
}

@Composable
internal fun HomeInfoStrip(
    resourceText: String,
    runModeText: String,
    runModeDetail: String?,
    onResourceClick: () -> Unit,
    onRunModeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = isInDarkTheme()
    val shape = AsteriskShapeTokens.PageCard
    val containerColor = if (!darkTheme) {
        colorScheme.surfaceContainerLowest.copy(alpha = 0.72f)
    } else {
        colorScheme.surfaceContainerHigh.copy(alpha = 0.60f)
    }
    val borderBrush = Brush.verticalGradient(
        if (!darkTheme) {
            listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.35f))
        } else {
            listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f))
        },
    )

    Surface(
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = colorScheme.primary.copy(alpha = 0.18f),
                spotColor = colorScheme.primary.copy(alpha = 0.18f),
            )
            .border(width = 1.dp, brush = borderBrush, shape = shape),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val resourceLabel = stringResource(R.string.home_monitor_resource)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onResourceClick)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = resourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = resourceText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
                    .background(colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )

            val runModeTitle = stringResource(R.string.settings_run_mode)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onRunModeClick)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = runModeTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val runModeDetailStyle = MaterialTheme.typography.labelMedium
                    Text(
                        // The eBPF local data_plane trails the mode in a smaller, muted span and wraps onto the
                        // second line on narrow cells or large font scales.
                        text = buildAnnotatedString {
                            append(runModeText)
                            if (runModeDetail != null) {
                                withStyle(
                                    SpanStyle(
                                        fontSize = runModeDetailStyle.fontSize,
                                        fontWeight = FontWeight.Normal,
                                        color = colorScheme.onSurfaceVariant,
                                    ),
                                ) {
                                    append(" · ")
                                    append(runModeDetail)
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun homeResourceSummary(monitoringState: HomeMonitoringOverviewState): String {
    if (!monitoringState.serviceRunning) return stringResource(R.string.home_value_unavailable)
    val cpu = monitoringState.resourceCpuPercent
        ?.let { value -> String.format(Locale.ROOT, "%.1f%%", value) }
        ?: stringResource(R.string.home_value_unavailable)
    val memory = monitoringState.resourceMemoryBytes
        ?.toReadableBytes()
        ?: stringResource(R.string.home_value_unavailable)
    return stringResource(R.string.home_resource_summary, cpu, memory)
}

@Composable
internal fun runModeLabel(runMode: Int): String {
    return stringResource(
        when (runMode) {
            RunModeTproxy -> R.string.settings_run_mode_tproxy
            RunModeEbpf -> R.string.settings_run_mode_ebpf
            else -> R.string.settings_run_mode_ebpf
        },
    )
}

// Mirrors runModeLabel: anything that is not TPROXY runs the eBPF inbound, whose local data_plane is shown.
internal fun runModeDataPlane(runMode: Int, ebpfLocalDataPlane: String): String? =
    if (runMode == RunModeTproxy) null else ebpfLocalDataPlane.normalizedEbpfLocalDataPlane()
