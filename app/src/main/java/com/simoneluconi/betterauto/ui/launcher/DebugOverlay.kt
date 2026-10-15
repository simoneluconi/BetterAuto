package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.car.CarState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val OverlayText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp, color = Color(0xFFE6EDF7), lineHeight = 27.sp)
private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** Diagnostics drawn over the car UI. Redraws every frame, so its clock also shows how smooth the stream is. */
@Composable
fun DebugOverlay(modifier: Modifier = Modifier) {
    val video by CarState.video.collectAsStateWithLifecycle()
    val link by CarState.link.collectAsStateWithLifecycle()
    val carNight by CarState.nightMode.collectAsStateWithLifecycle()
    var renderFps by remember { mutableDoubleStateOf(0.0) }
    var clock by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        var frames = 0
        var windowStart = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                frames++
                clock = LocalTime.now().format(ClockFormat)
                if (now - windowStart >= 1_000_000_000L) {
                    renderFps = frames * 1e9 / (now - windowStart)
                    frames = 0
                    windowStart = now
                }
            }
        }
    }

    val lines = listOf(
        "time     $clock",
        "render   %.1f fps".format(Locale.ROOT, renderFps),
        "sent     %.1f fps · %d kbps".format(Locale.ROOT, video.sentFps, video.kbps),
        "encoder  ${video.width}×${video.height} ${video.encoder}",
        "ack win  ${video.maxUnacked}",
        "link     ${link ?: "not connected"}",
        "night    ${carNight?.let { if (it) "yes (car)" else "no (car)" } ?: "unknown"}",
    )
    Column(
        modifier
            .background(Color(0xD606080C), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        for (line in lines) BasicText(line, style = OverlayText)
    }
}
