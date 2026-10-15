package com.simoneluconi.betterauto.projection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.ui.launcher.LauncherFocus
import com.simoneluconi.betterauto.ui.launcher.LauncherRoot

/** The launcher placed in the part of the video frame the car actually shows; the margins stay black. */
@Composable
fun ProjectedLauncher(focus: LauncherFocus) {
    val real by CarState.viewport.collectAsStateWithLifecycle()
    val simulated by CarState.simulatedScreen.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val viewport = CarState.viewportFor(constraints.maxWidth, constraints.maxHeight, real, simulated)
        val density = LocalDensity.current
        Box(
            Modifier
                .offset { IntOffset(viewport.left, viewport.top) }
                .size(with(density) { viewport.width.toDp() }, with(density) { viewport.height.toDp() })
        ) {
            LauncherRoot(focus = focus, insets = viewport.insets)
        }
    }
}
