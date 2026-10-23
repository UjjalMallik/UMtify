package com.music.bitchord.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The player's seek bar: a slim unplayed rail, a played stretch that breathes
 * into a gentle travelling wave while the music runs and settles flat when it
 * stops, and a round handle that sits on the bar rather than above it.
 *
 * Drawn directly rather than built from Material's Slider for the same reason
 * the old hairline was: the wave is the progress itself, not a decoration laid
 * over a track, and a stock thumb would hide the crest it rides. The touch
 * target, tap-to-seek and drag-to-scrub behaviour are the old bar's, unchanged.
 */
@Composable
internal fun WavySeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    /** A version switch's wait — a soft sheen crossing the rail. */
    loading: Boolean = false,
    /** An Automix handoff, whose pulse claims the whole rail while it runs. */
    mixing: Boolean = false,
    mixPulse: MixPulse? = null,
    /** The stretch of the track the next Automix transition will occupy. */
    transitionWindow: ClosedFloatingPointRange<Float>? = null,
    activeColor: Color = Color.White.copy(alpha = 0.95f),
    inactiveColor: Color = Color.White.copy(alpha = 0.22f),
    markerColor: Color = Color.White.copy(alpha = 0.36f),
) {
    var dragging by remember { mutableStateOf(false) }
    // The wave is a property of playback, not of the handle: it flows while the
    // music does, and a finger on the bar doesn't stop it. Only pause does.
    val amplitude by animateDpAsState(
        targetValue = if (isPlaying) WAVE_AMPLITUDE else 0.dp,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "waveAmplitude",
    )
    val phase = if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "seekWave")
        val flowing by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "seekWavePhase",
        )
        flowing
    } else {
        0f
    }
    // -1 means no sheen at all, so an idle bar pays for no gradient.
    val sheen = if (loading) {
        val transition = rememberInfiniteTransition(label = "seekLoading")
        val travelling by transition.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "seekSheen",
        )
        travelling
    } else {
        -1f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SEEK_TOUCH_HEIGHT)
            // One gesture loop for taps and drags, as the old bar had: a separate
            // tap detector never sees the pointer once a drag detector takes it.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    onValueChange((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        if (pointer.positionChanged()) {
                            onValueChange((pointer.position.x / size.width).coerceIn(0f, 1f))
                            pointer.consume()
                        }
                    }
                    dragging = false
                    onValueChangeFinished?.invoke()
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    onValueChange(target.coerceIn(0f, 1f))
                    onValueChangeFinished?.invoke()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(SEEK_TRACK_AREA),
        ) {
            val inset = THUMB_RADIUS.toPx() + 1.dp.toPx()
            val trackLeft = inset
            val trackRight = size.width - inset
            val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)
            val middle = size.height / 2f
            val rail = TRACK_HEIGHT.toPx()

            fun xAt(fraction: Float) = trackLeft + trackWidth * fraction.coerceIn(0f, 1f)

            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(trackLeft, middle - rail / 2f),
                size = Size(trackWidth, rail),
                cornerRadius = CornerRadius(rail / 2f),
            )

            // Drawn under the played fill: once the playhead reaches it, the
            // ordinary progress colour taking it over says it is no longer ahead.
            transitionWindow?.let { window ->
                val from = xAt(window.start)
                val to = xAt(window.endInclusive)
                if (to > from) {
                    drawRoundRect(
                        color = markerColor,
                        topLeft = Offset(from, middle - rail / 2f),
                        size = Size(to - from, rail),
                        cornerRadius = CornerRadius(rail / 2f),
                    )
                }
            }

            val base = value.coerceIn(0f, 1f)
            // A blend claims the whole bar, as the old hairline's did, so the
            // handoff reads on the bar rather than only on a label.
            val cover = if (dragging || !mixing) 0f else mixPulse?.cover ?: 0f
            val fraction = base + (1f - base) * cover
            val playedWidth = trackWidth * fraction
            val stroke = WAVE_STROKE.toPx()
            val playedColor = if (mixPulse != null && mixing && !dragging) {
                activeColor.copy(alpha = mixPulse.alpha(activeColor.alpha))
            } else {
                activeColor
            }

            if (playedWidth > 0.5f) {
                val ripple = amplitude.toPx()
                if (ripple < 0.5f) {
                    drawRoundRect(
                        color = playedColor,
                        topLeft = Offset(trackLeft, middle - stroke / 2f),
                        size = Size(playedWidth.coerceAtLeast(stroke), stroke),
                        cornerRadius = CornerRadius(stroke / 2f),
                    )
                } else {
                    // A sine sampled finely enough to stay a curve at phone
                    // density, tapered at both ends so the wave grows out of
                    // the rail rather than starting on a crest.
                    val wavelength = WAVELENGTH.toPx()
                    val steps = (playedWidth / (wavelength / 10f)).toInt().coerceIn(8, 240)
                    val path = Path()
                    for (step in 0..steps) {
                        val t = step.toFloat() / steps
                        val x = trackLeft + playedWidth * t
                        val edge = minOf(t, 1f - t) / 0.08f
                        val envelope = edge.coerceIn(0f, 1f)
                        val y = middle + ripple * envelope * sin(
                            (x / wavelength) * (2f * PI).toFloat() - phase,
                        )
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = playedColor,
                        style = Stroke(
                            width = stroke,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }

            if (sheen >= 0f) {
                val band = trackWidth * 0.34f
                val start = trackLeft + (trackWidth + band) * sheen - band
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.42f),
                            Color.Transparent,
                        ),
                        startX = start,
                        endX = start + band,
                    ),
                    topLeft = Offset(trackLeft, middle - rail / 2f),
                    size = Size(trackWidth, rail),
                    cornerRadius = CornerRadius(rail / 2f),
                )
            }

            // The handle sits at the playhead, not at the blend's cover: the
            // bar is what fills during a mix, and the point the finger owns
            // stays where the music actually is.
            val thumbX = xAt(base)
            val thumbRadius = (if (dragging) THUMB_DRAG_RADIUS else THUMB_RADIUS).toPx()
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = thumbRadius + 3.dp.toPx(),
                center = Offset(thumbX, middle),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = thumbRadius + 0.5.dp.toPx(),
                center = Offset(thumbX, middle),
            )
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(thumbX, middle),
            )
        }
    }
}

/** Height of the tappable strip. The rail itself is a fraction of it. */
private val SEEK_TOUCH_HEIGHT = 44.dp

/** Height of the canvas the rail is drawn in, centred in the touch strip. */
private val SEEK_TRACK_AREA = 22.dp

private val TRACK_HEIGHT = 4.dp

/** Wave and flat played rail alike: the played stretch is the fuller line. */
private val WAVE_STROKE = 6.dp

private val WAVE_AMPLITUDE = 3.dp

private val WAVELENGTH = 22.dp

private val THUMB_RADIUS = 7.dp
private val THUMB_DRAG_RADIUS = 9.dp
