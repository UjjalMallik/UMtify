package com.music.bitchord.data.settings

/**
 * Stream bitrate ceiling on the YouTube fallback path, and the gate on which
 * other sources get asked at all.
 *
 * What actually tells the rungs apart is two things, not one:
 *
 * - [maxKbps] sorts YouTube's own format ladder — the itag list behind a track,
 *   where Opus 48/128/160 and AAC 256 sit at different prices. [LOW] takes the
 *   rung at or under 64, [MEDIUM] the one at or under 160 (YouTube's best Opus),
 *   [HIGH] and [LOSSLESS] take the top of whatever is offered. A ceiling is
 *   enforced on the *result*, not only on the ask: InnerTubeX hands back one
 *   rung and cannot be told "anything but the top", so a stream over the ceiling
 *   sends the resolver to the sorted ladder before the over-budget copy is
 *   accepted.
 * - [permits][com.music.bitchord.data.settings.permits] says who may answer
 *   before YouTube does:
 *   - [LOSSLESS] — the user's own addons and JioSaavn both asked, and asked
 *     first.
 *   - [HIGH] — the addons skipped, JioSaavn asked.
 *   - [MEDIUM] and [LOW] — both skipped; YouTube's own Opus ladder is all there
 *     is, capped at [maxKbps].
 *
 * [hourly] is what the ceiling costs in data over an hour of listening, which
 * is the only part of this a user actually cares about on a metered plan.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    LOW(64, "Low", "~64 kbps · smallest download", "29 MB/hr"),
    MEDIUM(160, "Medium", "Opus up to 160kbps · no AAC 256", "72 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "JioSaavn up to 320kbps, YouTube fallback", "144 MB/hr"),
    LOSSLESS(Int.MAX_VALUE, "Lossless", "Your addons + JioSaavn, bit-exact where available", "300+ MB/hr"),
    ;
}

/** The surface that was last open inside the expanded player. */
enum class LastPlayerScreen {
    MAIN,
    LYRICS,
    QUEUE,
}
