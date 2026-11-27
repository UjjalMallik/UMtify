package com.music.bitchord.download

/**
 * A downloadable audio format with resolved stream metadata, bitrate, and size.
 */
data class DownloadFormatOption(
    val id: String,
    val label: String,
    val codec: String,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val sizeBytes: Long? = null,
    val formattedSize: String,
    val streamUrl: String,
    val fileExtension: String,
    val isLossless: Boolean = false,
    val isDolbyAtmos: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)
