// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

/**
 * Mirrors the sing-box NetworkQualityPhase wire int constants:
 *   Idle = 0, Download = 1, Upload = 2, Done = 3
 *
 * Values map to the wire constants used by `NetworkQualityProgress.phase`,
 * so callers can convert without hard-coding magic numbers.
 */
internal enum class NetworkQualityPhase(val wire: Int) {
    Idle(0),
    Download(1),
    Upload(2),
    Done(3),
    ;

    companion object {
        fun ofWire(wireValue: Int): NetworkQualityPhase =
            entries.firstOrNull { it.wire == wireValue } ?: Idle
    }
}

/**
 * One snapshot delivered to the UI from a networkQuality measurement run.
 * Flattened for Compose consumption. `finished` is set on the terminal
 * snapshot (phase == [NetworkQualityPhase.Done] for success, or any phase
 * paired with an [error]).
 */
internal data class NetworkQualityProgress(
    val phase: NetworkQualityPhase = NetworkQualityPhase.Idle,
    val downloadCapacityBitsPerSecond: Long = 0L,
    val uploadCapacityBitsPerSecond: Long = 0L,
    val downloadRpm: Int = 0,
    val uploadRpm: Int = 0,
    val idleLatencyMs: Int = 0,
    val elapsedMs: Long = 0L,
    val downloadCapacityAccuracy: Int = 0,
    val uploadCapacityAccuracy: Int = 0,
    val downloadRpmAccuracy: Int = 0,
    val uploadRpmAccuracy: Int = 0,
    val error: String? = null,
    val finished: Boolean = false,
)

internal interface NetworkQualityTestHandler {
    fun onProgress(progress: NetworkQualityProgress)
    fun onError(message: String)
    fun onResult(result: NetworkQualityProgress)
}

internal interface NetworkQualityTestSession : AutoCloseable

