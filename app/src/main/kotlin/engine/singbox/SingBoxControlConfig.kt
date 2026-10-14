// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import app.AppState
import engine.network.toPortOrNull

internal const val SingBoxControlHost = "127.0.0.1"
internal const val DefaultSingBoxControlPort = 9090
internal const val DefaultSingBoxDelayTestUrl = "https://www.gstatic.com/generate_204"
internal const val DefaultSingBoxDelayTimeoutMillis = 5000

internal data class SingBoxControlConfig(
    val host: String = SingBoxControlHost,
    val port: Int = DefaultSingBoxControlPort,
    val secret: String = "",
    val scheme: String = "http",
) {
    val address: String
        get() = if (":" in host) "[$host]:$port" else "$host:$port"

    val baseUrl: String
        get() = "$scheme://$address"

    override fun toString(): String {
        return "SingBoxControlConfig(host=$host, port=$port, scheme=$scheme, secret=<redacted>)"
    }
}

internal fun AppState.singBoxControlConfig(): SingBoxControlConfig {
    return SingBoxControlConfig(
        port = singBoxControlPort.toPortOrNull() ?: DefaultSingBoxControlPort,
        secret = singBoxControlSecret.trim(),
    )
}

internal fun AppState.withResolvedSingBoxControlPort(): AppState {
    val fixedPortText = DefaultSingBoxControlPort.toString()
    return if (singBoxControlPort == fixedPortText) this else copy(singBoxControlPort = fixedPortText)
}
