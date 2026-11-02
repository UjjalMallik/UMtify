package com.music.bitchord.playback

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.music.bitchord.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * The playback pill UMtify draws itself.
 *
 * Every OEM with a status-bar island keeps its own allow-list — Vivo's Mini
 * Capsule, ColorOS's, HyperOS's and Samsung's Live Update each decide which
 * packages may occupy the cutout — and a third-party music app is not on them.
 * The notification already carries the metadata those surfaces read, but nothing
 * guarantees a pill appears, and on an iQOO Z9x none ever does.
 *
 * So this draws one. `TYPE_APPLICATION_OVERLAY` is the only window an app may
 * place at the top of the screen without being on an allow-list, and it is the
 * same window class the OEMs' own capsules are made of, so the result is a black
 * capsule with the cover, the track and a live waveform in the cutout area on any
 * Android phone. It costs one permission the listener grants from Settings, and
 * it is what makes the feature work everywhere instead of on the handful of
 * skins that happen to whitelist this package.
 *
 * Plain Views, not Compose: a `ComposeView` in an overlay window needs a
 * lifecycle owner, a saved-state registry and a view-store wired up by hand, and
 * this is a fixed strip with three moving parts. The window exists only while
 * audio is coming out of a backgrounded app, and the animator stops the moment
 * either half of that goes false, so nothing is redrawn when nobody is looking.
 */
internal class IslandCapsule(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onOpenPlayer: () -> Unit,
    private val onTogglePlay: () -> Unit,
    private val onNext: () -> Unit,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var artwork: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var bars: WaveBarsView? = null
    private var controls: LinearLayout? = null
    private var playButton: ImageButton? = null
    private var expanded = false
    private var attached = false
    private var playing = false
    private var artworkToken = 0

    /** The pill is on screen only while music plays with UMtify out of the foreground. */
    fun setVisible(visible: Boolean) {
        if (visible == attached) return
        if (visible) attach() else detach()
    }

    /**
     * Points the pill at a track. The cover arrives late, off the same cache entry
     * the row on screen filled, and is dropped if the track moved on while it was
     * being decoded — a stale cover on a pill that outlives the notification is
     * worse than no cover.
     */
    fun setTrack(title: String?, artist: String?, artworkUri: Uri?) {
        titleView?.text = title.orEmpty()
        artistView?.text = artist.orEmpty()
        val token = ++artworkToken
        artwork?.setImageDrawable(null)
        if (artworkUri == null) return
        scope.launch {
            val bitmap = sessionArtworkBitmap(context, artworkUri, CAPSULE_ART_PX)
            if (token != artworkToken || !attached) return@launch
            artwork?.setImageBitmap(bitmap)
        }
    }

    /**
     * Freezes the waveform on pause, so a pill over a silent phone never pulses.
     *
     * Player events arrive far more often than the play state changes, so an
     * unchanged value does nothing rather than re-inflating the glyph.
     */
    fun setPlaying(isPlaying: Boolean) {
        if (isPlaying == playing) return
        playing = isPlaying
        bars?.setRunning(isPlaying && attached)
        applyPlayGlyph()
    }

    /** The glyph follows [playing]; a rebuilt shell starts from the field, not the button. */
    private fun applyPlayGlyph() {
        playButton?.setImageResource(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
    }

    fun release() {
        detach()
    }

    private fun attach() {
        if (!Settings.canDrawOverlays(context)) return
        val shell = buildShell()
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: the pill takes the taps meant for it and nothing
            // else. A focusable overlay would steal the keyboard and open the IME
            // behind it in whichever app the listener was typing into.
            // Not touch-modal either, so the rest of the screen stays live.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarInset()
        }
        if (runCatching { windowManager.addView(shell, layout) }.isFailure) {
            // The shell holds references to itself through its listeners; let go
            // rather than keep a pill alive that never reached the screen.
            detach()
            return
        }
        root = shell
        params = layout
        attached = true
        bars?.setRunning(playing)
        applyPlayGlyph()
    }

    private fun detach() {
        bars?.setRunning(false)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        params = null
        artwork = null
        titleView = null
        artistView = null
        bars = null
        controls = null
        playButton = null
        expanded = false
        attached = false
        artworkToken++
    }

    private fun buildShell(): LinearLayout {
        val cover = ImageView(context).apply {
            contentDescription = context.getString(R.string.capsule_artwork_description)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(CAPSULE_ART_DP), dp(CAPSULE_ART_DP)).apply {
                marginEnd = dp(9f)
            }
            // A circle, not a rounded square: every OEM pill crops the cover
            // round, and a square here reads as somebody else's app at a glance.
            clipToOutline = true
            outlineProvider = CircleOutline
        }
        val title = textView(CAPSULE_TITLE_SP, Color.WHITE)
        val artist = textView(CAPSULE_ARTIST_SP, TEXT_DIM)
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(CAPSULE_TEXT_DP), LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(title)
            addView(artist)
        }
        val waveform = WaveBarsView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(CAPSULE_BARS_DP), dp(18f)).apply {
                marginStart = dp(8f)
            }
        }
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(6f), dp(10f), dp(6f))
            addView(cover)
            addView(labels)
            addView(waveform)
            setOnClickListener { if (expanded) collapse() else onOpenPlayer() }
            setOnLongClickListener {
                if (expanded) collapse() else expand()
                true
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2f), dp(2f), dp(6f), dp(6f))
            visibility = View.GONE
            addView(controlButton(R.drawable.ic_widget_play, R.string.capsule_play_pause_description) { onTogglePlay() })
            addView(controlButton(R.drawable.ic_widget_next, R.string.capsule_next_description) { onNext() })
        }
        playButton = buttons.getChildAt(0) as? ImageButton
        artwork = cover
        titleView = title
        artistView = artist
        bars = waveform
        controls = buttons
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Half the pill's own height, so the closed state is a stadium and
                // the opened state keeps the same round ends with a straighter body.
                cornerRadius = dp(CAPSULE_HEIGHT_DP / 2f).toFloat()
                setColor(CAPSULE_BACKGROUND)
            }
            addView(strip)
            addView(buttons)
        }
    }

    /**
     * Long-press opens the mini-controller. A WRAP_CONTENT overlay keeps the size
     * it was added at until [WindowManager.updateViewLayout] says otherwise, so
     * the new height is pushed back to the window once the row has been measured.
     */
    private fun expand() {
        expanded = true
        controls?.visibility = View.VISIBLE
        setCornerRadius(CAPSULE_EXPANDED_RADIUS_DP)
        relayout()
    }

    private fun collapse() {
        expanded = false
        controls?.visibility = View.GONE
        setCornerRadius(CAPSULE_HEIGHT_DP / 2f)
        relayout()
    }

    private fun setCornerRadius(dpValue: Float) {
        (root?.background as? GradientDrawable)?.cornerRadius = dp(dpValue).toFloat()
    }

    private fun relayout() {
        val shell = root ?: return
        val layout = params ?: return
        shell.post {
            if (!attached) return@post
            runCatching { windowManager.updateViewLayout(shell, layout) }
        }
    }

    private fun controlButton(@DrawableRes icon: Int, descriptionRes: Int, action: () -> Unit) =
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { marginEnd = dp(4f) }
            background = null
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            contentDescription = context.getString(descriptionRes)
            setOnClickListener { action() }
        }

    private fun textView(sp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun dp(value: Float) = (value * density + 0.5f).toInt()

    /**
     * The cutout is not a fixed distance down, and a pill overlapping the clock is
     * worse than one sitting below it. `status_bar_height` is what the OEMs clear
     * by themselves; on a notch-less phone it is the bar and nothing more.
     */
    private fun statusBarInset(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fromSystem = if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        return fromSystem + dp(2f)
    }

    private companion object {
        const val CAPSULE_ART_DP = 30f
        const val CAPSULE_ART_PX = 128
        const val CAPSULE_HEIGHT_DP = 42f
        const val CAPSULE_EXPANDED_RADIUS_DP = 22f
        const val CAPSULE_TEXT_DP = 132f
        const val CAPSULE_BARS_DP = 22f
        const val CAPSULE_TITLE_SP = 12.5f
        const val CAPSULE_ARTIST_SP = 10.5f
        const val CAPSULE_BACKGROUND = 0xF2121214.toInt()
        const val TEXT_DIM = 0xB3FFFFFF.toInt()
    }
}

/**
 * Four bars that move on their own clock rather than on the audio.
 *
 * A real spectrum would need a `Visualizer` attached to the player's audio session
 * — a second tap on bytes the decoder is already handing the ribbon on the player
 * screen, for a pill the listener glances at once. This animates off elapsed time,
 * and the phase per bar is deliberately not a multiple of the others so the
 * pattern never visibly repeats. It stops with the window.
 */
private class WaveBarsView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private var clock = 0f
    private var animator: ValueAnimator? = null

    fun setRunning(running: Boolean) {
        if (running) {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    clock += 0.045f
                    invalidate()
                }
            }.also { it.start() }
        } else {
            animator?.cancel()
            animator = null
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        setRunning(false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val slot = width / (BAR_COUNT * 2f)
        val half = height / 2f
        for (i in 0 until BAR_COUNT) {
            val phase = clock + i * 0.83f
            val swing = abs(sin(phase)) * 0.72f + abs(sin(phase * 1.7f + 0.4f)) * 0.28f
            val barHeight = (half * (0.30f + 0.66f * swing)).coerceAtLeast(2f)
            val left = i * slot * 2f + slot / 2f
            rect.set(left, half - barHeight, left + slot, half + barHeight)
            canvas.drawRoundRect(rect, slot / 2f, slot / 2f, paint)
        }
    }

    private companion object {
        const val BAR_COUNT = 4
    }
}

/** Rounds a view to a circle, sized to whatever it ends up as. */
private object CircleOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val side = minOf(view.width, view.height)
        outline.setRoundRect(0, 0, side, side, side / 2f)
    }
}
