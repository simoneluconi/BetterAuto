package com.simoneluconi.betterauto.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
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

/**
 * A control usable both by touch and by the car's rotary/d-pad: always focusable (touch mode or not),
 * activated by tap or by the center/enter key, with a clear focus ring and slight zoom when focused,
 * and a short press-in (shrink + veil) on every press so the driver sees it registered.
 */
fun Modifier.carClickable(
    shape: Shape,
    label: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = LocalCarColors.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var pressedAt by remember { mutableLongStateOf(0L) }
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            focused -> 1.06f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "pressScale",
    )
    val veil by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 60 else 260), label = "pressVeil")
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)

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
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (veil > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), colors.text.copy(alpha = 0.14f * veil))
            }
            if (focused) {
                // Solid ring just outside the control, then a soft halo band outside the ring
                val ring = 4.dp.toPx()
                val halo = 10.dp.toPx()
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
            when (event.type) {
                KeyEventType.KeyDown -> if (!pressed) press()
                KeyEventType.KeyUp -> {
                    release()
                    click()
                }
            }
            true
        }
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
