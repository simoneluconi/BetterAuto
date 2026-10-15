package com.simoneluconi.betterauto.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

private val ActivateKeys = setOf(Key.Enter, Key.DirectionCenter, Key.NumPadEnter)

/**
 * A control usable both by touch and by the car's rotary/d-pad: always focusable (touch mode or not),
 * activated by tap or by the center/enter key, with a clear focus ring and slight zoom when focused.
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
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "focusScale")
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
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
            if (event.type == KeyEventType.KeyUp) click()
            true
        }
        .focusable(interactionSource = interaction)
        .pointerInput(Unit) {
            detectTapGestures(
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
