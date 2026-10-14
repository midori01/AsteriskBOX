// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import app.AppState
import engine.singbox.runtime.SingBoxRuntimeRepository
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Unified surface for running an Apple `networkQuality` test.
 *
 *  - [ServiceNetworkQualityExecutor] — runs while the proxy service is up and
 *    routes through the existing command client. Forwards to
 *    `daemon.StartedService/StartNetworkQualityTest` on the gRPC control
 *    channel used by every other runtime command. Honours the `outboundTag`
 *    parameter and never touches ROOT shell, iptables, or BPF state.
 *  - [StandaloneNetworkQualityExecutor] — runs without the service. In ROOT-only
 *    standalone builds without embedded libbox, standalone measurement is
 *    unavailable and reports that the proxy service must be running.
 *
 * Both paths terminate by either an `onResult` (phase = [NetworkQualityPhase.Done])
 * or `onError` callback; the resulting terminal [NetworkQualityProgress]
 * always has `finished = true`.
 */
internal interface NetworkQualityExecutor {
    fun run(
        configUrl: String,
        outboundTag: String,
        serial: Boolean,
        maxRuntimeSeconds: Int,
        http3: Boolean,
    ): Flow<NetworkQualityProgress>

    fun cancel()
}

internal class ServiceNetworkQualityExecutor(
    private val repository: SingBoxRuntimeRepository,
    private val appState: AppState,
) : NetworkQualityExecutor {
    private val sessionRef = AtomicReference<NetworkQualityTestSession?>()

    override fun run(
        configUrl: String,
        outboundTag: String,
        serial: Boolean,
        maxRuntimeSeconds: Int,
        http3: Boolean,
    ): Flow<NetworkQualityProgress> = callbackFlow {
        val client = repository.activeCommandClient(appState)
        val latestProgress = AtomicReference(NetworkQualityProgress())
        val handler = object : NetworkQualityTestHandler {
            override fun onProgress(progress: NetworkQualityProgress) {
                latestProgress.set(progress)
                trySend(progress)
            }

            override fun onError(message: String) {
                trySend(
                    NetworkQualityProgress(
                        elapsedMs = latestProgress.get().elapsedMs,
                        error = message,
                        finished = true,
                    ),
                )
                close()
            }

            override fun onResult(result: NetworkQualityProgress) {
                trySend(
                    result.copy(
                        elapsedMs = if (result.elapsedMs > 0) result.elapsedMs else latestProgress.get().elapsedMs,
                        finished = true,
                    ),
                )
                close()
            }
        }
        val session = client.startNetworkQualityTest(
            configURL = configUrl,
            outboundTag = outboundTag,
            serial = serial,
            maxRuntimeSeconds = maxRuntimeSeconds,
            http3 = http3,
            handler = handler,
        )
        sessionRef.set(session)
        awaitClose {
            sessionRef.set(null)
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        sessionRef.getAndSet(null)?.let { runCatching { it.close() } }
    }
}

internal class StandaloneNetworkQualityExecutor : NetworkQualityExecutor {
    override fun run(
        configUrl: String,
        outboundTag: String,
        serial: Boolean,
        maxRuntimeSeconds: Int,
        http3: Boolean,
    ): Flow<NetworkQualityProgress> = callbackFlow {
        trySend(
            NetworkQualityProgress(
                error = "Standalone test unavailable: proxy service must be running",
                finished = true,
            ),
        )
        close()
    }

    override fun cancel() = Unit
}
