package com.music.bitchord.playback

/**
 * Real-time voice and audio DSP transformation presets.
 */
enum class VoiceEffect(
    val label: String,
    val pitch: Float,
) {
    NORMAL("Normal", 1.0f),
    CHIPMUNK("Chipmunk", 1.6f),
    DEMON("Demon", 0.6f),
    ROBOT("Robot", 1.35f),
    STADIUM("Stadium", 1.0f),
    UNDERWATER("Underwater", 0.85f),
}
