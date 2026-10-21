package com.simoneluconi.betterauto.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Job
import com.simoneluconi.betterauto.EventLog
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ActivateKeys = setOf(Key.Enter, Key.DirectionCenter, Key.NumPadEnter)

private const val MIN_PRESS_MS = 120L

/** Holding the knob's center (or enter) this long is a long press, as holding a finger is. */
private const val KEY_LONG_PRESS_MS = 600L

/**
 * What holding a control does when it has no long press of its own: a home widget's controls edit the
 * home screen. Without it, holding is just a slow tap.
 */
val LocalHoldAction = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The control the knob was last on, so an overlay (arrival buttons, a panel) can give the focus back
 * where it was when it closes. Main thread only.
 */
object FocusMemory {
    var last: FocusRequester? = null
        private set

    internal fun focused(requester: FocusRequester) {
        last = requester
    }

    /** Back to [requester] if it is still on screen; false when it is gone (the caller picks another place). */
    fun restore(requester: FocusRequester?): Boolean = requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)
}

/** Press-in and focus zoom: this many dp on the longest side, at most these fractions on small controls. */
private const val PRESS_SHRINK_DP = 8
private const val MAX_PRESS_SHRINK = 0.08f
private const val FOCUS_GROW_DP = 6
private const val MAX_FOCUS_GROW = 0.03f

/**
 * A control usable both by touch and by the car's rotary/d-pad: always focusable (touch mode or not),
 * activated by tap or by the center/enter key, with a clear focus ring and slight zoom when focused,
 * and a short press-in (shrink + veil) on every press so the driver sees it registered.
 * [onLongClick] (else [LocalHoldAction]) runs on a held finger or a held center key; the release then
 * doesn't also click.
 */
fun Modifier.carClickable(
    shape: Shape,
    label: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val self = remember { FocusRequester() }
    val colors = LocalCarColors.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var pressedAt by remember { mutableLongStateOf(0L) }
    // How far into the press-in and the focus zoom the control is (0..1); the size change is applied below
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    val zoom by animateFloatAsState(if (focused && !pressed) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "zoom")
    val veil by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 60 else 260), label = "pressVeil")
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick ?: LocalHoldAction.current)
    var keyHold by remember { mutableStateOf<Job?>(null) }
    var heldLong by remember { mutableStateOf(false) }
    // A release whose press went to another control (a hold that opened this one) is not a click
    var keyDown by remember { mutableStateOf(false) }

    // Once per hold, from the timer or from the car's own long-press flag, whichever comes first
    fun keyLongPress(keyCode: Int) {
        if (heldLong) return
        val action = longClick ?: return
        heldLong = true
        keyHold?.cancel()
        EventLog.log("Input: key $keyCode long press")
        action()
    }

    fun press() {
        pressed = true
        pressedAt = SystemClock.uptimeMillis()
    }

    // Knob presses can last a few ms: keep the pressed look long enough to be seen
    fun release() {
        val left = MIN_PRESS_MS - (SystemClock.uptimeMillis() - pressedAt)
        scope.launch {
            if (left > 0) delay(left)
            pressed = false
        }
    }

    this
        .graphicsLayer {
            // The same few dp for a small button and a long card: a percentage would make big cards jump
            val longest = maxOf(size.width, size.height).coerceAtLeast(1f)
            val shrink = minOf(MAX_PRESS_SHRINK, PRESS_SHRINK_DP.dp.toPx() / longest)
            // Slight: controls sit close together, the ring must not reach the next one
            val grow = minOf(MAX_FOCUS_GROW, FOCUS_GROW_DP.dp.toPx() / longest)
            val scale = 1f - shrink * press + grow * zoom
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (veil > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), colors.text.copy(alpha = 0.14f * veil))
            }
            if (focused) {
                // Solid ring just outside the control, then a soft halo band outside the ring:
                // 6 dp in all, so it stays within the gap to the next control
                val ring = 3.dp.toPx()
                val halo = 6.dp.toPx()
                drawRingOutside(shape, ring / 2, ring) { outline, stroke ->
                    drawOutline(outline, colors.focus, style = stroke)
                }
                drawRingOutside(shape, (ring + halo) / 2, halo - ring) { outline, stroke ->
                    drawOutline(outline, colors.focus.copy(alpha = 0.28f), style = stroke)
                }
            }
        }
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick { click(); true }
        }
        .onKeyEvent { event ->
            if (event.key !in ActivateKeys) return@onKeyEvent false
            val native = event.nativeKeyEvent
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (!pressed) {
                        keyDown = true
                        press()
                        heldLong = false
                        if (longClick != null) keyHold = scope.launch {
                            delay(KEY_LONG_PRESS_MS)
                            keyLongPress(native.keyCode)
                        }
                    }
                    // Repeats while held, or a car that flags the long press itself
                    if (native.isLongPress || native.repeatCount > 0) keyLongPress(native.keyCode)
                }
                KeyEventType.KeyUp -> {
                    keyHold?.cancel()
                    keyHold = null
                    if (keyDown) {
                        release()
                        if (!heldLong) click()
                    }
                    keyDown = false
                    heldLong = false
                }
            }
            true
        }
        .focusRequester(self)
        .onFocusChanged { if (it.isFocused) FocusMemory.focused(self) }
        .focusable(interactionSource = interaction)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    press()
                    tryAwaitRelease()
                    release()
                },
                onTap = { click() },
                onLongPress = { longClick?.invoke() ?: click() },
            )
        }
}

/** Strokes [shape]'s outline grown by [distance] on every side. */
private inline fun DrawScope.drawRingOutside(
    shape: Shape,
    distance: Float,
    width: Float,
    draw: DrawScope.(Outline, Stroke) -> Unit,
) {
    val outline = shape.createOutline(Size(size.width + distance * 2, size.height + distance * 2), layoutDirection, this)
    translate(-distance, -distance) { draw(outline, Stroke(width)) }
}

/**
 * How lists scroll to the control the knob lands on: with a margin, so its focus ring (drawn around it)
 * and a bit of what follows stay in view, instead of stopping flush with the edge and cutting the ring.
 */
@OptIn(ExperimentalFoundationApi::class)
val CarBringIntoView: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = (containerSize * 0.08f).coerceAtMost(FOCUS_MARGIN_PX)
        val leading = offset - margin
        val trailing = offset + size + margin - containerSize
        return when {
            // Bigger than the room left with its margins: its start shows
            size + 2 * margin > containerSize -> leading
            leading < 0 -> leading
            trailing > 0 -> trailing
            else -> 0f
        }
    }
}

private const val FOCUS_MARGIN_PX = 64f
