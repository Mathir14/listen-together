package com.listentogether.playback

import com.listentogether.core.common.Clock

/**
 * Drift policy from architecture §7.3. Pure, clock-injected, and deliberately conservative:
 * corrections are rate nudges where possible and hard seeks only for large skew.
 */
data class DriftPolicy(
    val ignoreBelowMs: Long = 300,
    val hardSeekAboveMs: Long = 1_500,
    val settleBelowMs: Long = 100,
    val nudgeRate: Float = 0.02f,
    val maxNudgeMs: Long = 8_000,
    val cooldownMs: Long = 10_000,
    val cooldownOverrideMs: Long = 3_000,
    val remoteSeekSuppressionMs: Long = 1_000,
    val trackReadySettleMs: Long = 500,
)

sealed interface DriftAction {
    data class RateNudge(val rate: Float, val direction: Int) : DriftAction
    data class HardSeek(val positionMs: Long) : DriftAction
    data object RestoreRate : DriftAction
}

class DriftCorrector(
    private val clock: Clock,
    private val policy: DriftPolicy = DriftPolicy(),
) {
    private var cooldownUntilMs = 0L
    private var nudgeUntilMs = 0L
    private var nudgeDirection = 0
    private var lastRemoteSeekAtMs = Long.MIN_VALUE
    private var trackReadyAtMs = Long.MIN_VALUE
    private var lastCorrectionAtMs = Long.MIN_VALUE

    fun noteRemoteSeek(nowMs: Long = clock.monotonicMs()) {
        lastRemoteSeekAtMs = nowMs
        stopNudge()
    }

    fun noteTrackChanged(@Suppress("UNUSED_PARAMETER") nowMs: Long = clock.monotonicMs()) {
        trackReadyAtMs = Long.MIN_VALUE
        stopNudge()
    }

    fun noteTrackReady(nowMs: Long = clock.monotonicMs()) {
        trackReadyAtMs = nowMs
    }

    fun reset() {
        cooldownUntilMs = 0
        nudgeUntilMs = 0
        nudgeDirection = 0
        lastRemoteSeekAtMs = Long.MIN_VALUE
        trackReadyAtMs = Long.MIN_VALUE
        lastCorrectionAtMs = Long.MIN_VALUE
    }

    /**
     * @param localPositionMs current player position
     * @param authoritativePositionMs projected server position at the same instant
     * @param isPlaying both local and authoritative playing
     * @param isReady player READY
     * @param isBuffering player buffering
     */
    fun evaluate(
        localPositionMs: Long,
        authoritativePositionMs: Long,
        isPlaying: Boolean,
        isReady: Boolean,
        isBuffering: Boolean,
        nowMs: Long = clock.monotonicMs(),
    ): DriftAction? {
        if (!isPlaying || !isReady || isBuffering) return null
        if (lastRemoteSeekAtMs != Long.MIN_VALUE && nowMs - lastRemoteSeekAtMs < policy.remoteSeekSuppressionMs) return null
        if (trackReadyAtMs != Long.MIN_VALUE && nowMs - trackReadyAtMs < policy.trackReadySettleMs) return null

        val skew = localPositionMs - authoritativePositionMs
        val magnitude = kotlin.math.abs(skew)

        // An active nudge continues until it settles or its budget expires.
        if (nudgeDirection != 0) {
            if (magnitude < policy.settleBelowMs || nowMs >= nudgeUntilMs) {
                stopNudge()
                return DriftAction.RestoreRate
            }
            return DriftAction.RateNudge(1f + nudgeDirection * policy.nudgeRate, nudgeDirection)
        }

        if (magnitude < policy.ignoreBelowMs) return null
        if (magnitude <= policy.hardSeekAboveMs) {
            if (nowMs < cooldownUntilMs) return null
            nudgeDirection = if (skew < 0) 1 else -1
            nudgeUntilMs = nowMs + policy.maxNudgeMs
            cooldownUntilMs = nowMs + policy.cooldownMs
            lastCorrectionAtMs = nowMs
            return DriftAction.RateNudge(1f + nudgeDirection * policy.nudgeRate, nudgeDirection)
        }

        // > 1500 ms: one hard seek, allowed even inside the cooldown when extreme.
        if (magnitude <= policy.cooldownOverrideMs && nowMs < cooldownUntilMs) return null
        cooldownUntilMs = nowMs + policy.cooldownMs
        lastCorrectionAtMs = nowMs
        return DriftAction.HardSeek(authoritativePositionMs)
    }

    fun lastCorrectionAtMs(): Long = lastCorrectionAtMs
    fun isNudging(): Boolean = nudgeDirection != 0

    private fun stopNudge() {
        nudgeDirection = 0
        nudgeUntilMs = 0
    }
}
