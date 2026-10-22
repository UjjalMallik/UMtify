package com.music.bitchord.playback.smart

/** Loads the analyser's native library, however the platform keeps it. */
object NativeAnalysisLibrary {

    private var loader: (() -> Unit)? = null

    /** Must be called before anything touches the analyser. */
    fun install(loader: () -> Unit) {
        this.loader = loader
    }

    val available: Boolean by lazy {
        val install = loader ?: return@lazy false
        runCatching { install() }
            .onFailure { AnalysisLog.warn("Analysis library unavailable; Automix will not run", it) }
            .isSuccess
    }
}
