package com.s0undplus.twitch.playback

enum class RefreshMode(val label: String) {
    /** Checks that the streamer is still online before retrying; shows "offline" instead of hammering Twitch. */
    NORMAL("Normal"),

    /** Retries regardless (for flaky streams that report offline while still up). */
    AGGRESSIVE("Aggressive"),
}

sealed interface RefreshStep {
    /** Re-request a playback token and playlist after [delayMs]. */
    data class Retry(val delayMs: Long) : RefreshStep

    /** The channel really went offline: stop retrying and tell the viewer. */
    data object Offline : RefreshStep

    /** Retries exhausted. */
    data object GaveUp : RefreshStep
}

/** The decision logic for recovering a live stream that stopped, separate from the player so it can be tested. */
class AutoRefreshPolicy(
    val mode: RefreshMode = RefreshMode.NORMAL,
    val maxAttempts: Int = 5,
    val intervalMs: Long = 5_000,
) {
    /**
     * @param attempt how many recoveries have already been tried for this stall (0 on the first failure)
     * @param online whether the channel is live per Helix, or null if unknown (not checked / check failed)
     */
    fun next(attempt: Int, online: Boolean?): RefreshStep = when {
        mode == RefreshMode.NORMAL && online == false -> RefreshStep.Offline
        attempt >= maxAttempts -> RefreshStep.GaveUp
        else -> RefreshStep.Retry(intervalMs)
    }
}

/** Notices when the player has been stuck buffering too long. Time is injected so tests don't sleep. */
class StallDetector(private val thresholdMs: Long = 15_000) {
    private var bufferingSince: Long? = null

    fun onBuffering(nowMs: Long) {
        if (bufferingSince == null) bufferingSince = nowMs
    }

    /** Playback resumed (or the player was reset). */
    fun onProgress() {
        bufferingSince = null
    }

    fun isStalled(nowMs: Long): Boolean = bufferingSince?.let { nowMs - it >= thresholdMs } ?: false
}
