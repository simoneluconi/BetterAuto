package com.simoneluconi.betterauto.ui.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.home.WeatherWidgetHost
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * The phone's weather app widget, drawn by that app and framed in one of our cards. Look-only: touches
 * and the knob never reach it (its buttons would open the app on the phone, not in the car). Updates
 * arrive only while it is on screen and the car shows our screen.
 */
@Composable
fun WeatherWidget(modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    // The presentation's own context: the widget is inflated at the car display's density
    val context = LocalContext.current
    val changes by WeatherWidgetHost.changes.collectAsStateWithLifecycle()
    val provider = remember(changes) { WeatherWidgetHost.provider(context) }
    var id by remember(changes, provider) { mutableStateOf(provider?.let { WeatherWidgetHost.boundId(context, it) }) }
    // Picked from the car: binding works without the phone only if the driver once ticked "always allow", and
    // only for widgets that ask nothing: one bound without its setup screen would show "Loading" forever
    LaunchedEffect(changes, provider) {
        if (provider != null && id == null && !WeatherWidgetHost.needsConfigure(provider)) {
            id = WeatherWidgetHost.bindIfAllowed(context, provider)
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bound = id
        when {
            provider == null -> PanelMessage(stringResource(R.string.weather_none))
            bound == null -> PanelMessage(stringResource(R.string.weather_setup_on_phone))
            else -> key(bound) { HostedWidget(bound, provider) }
        }
    }
}

@Composable
private fun HostedWidget(id: Int, info: AppWidgetProviderInfo) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Started with the presentation (the car showing our screen), stopped when it stops or the widget goes
    LifecycleStartEffect(id) {
        WeatherWidgetHost.startListening(context)
        onStopOrDispose { WeatherWidgetHost.stopListening(context) }
    }
    AndroidView(
        factory = { ctx ->
            LookOnlyFrame(ctx).apply {
                addView(
                    WeatherWidgetHost.host(ctx).createView(ctx, id, info),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { canFocus = false }
            .onSizeChanged { size ->
                with(density) { WeatherWidgetHost.resize(context, id, size.width.toDp().value.toInt(), size.height.toDp().value.toInt()) }
            },
    )
}

/** Holds the hosted widget: never focused, and keeps every touch from it. */
private class LookOnlyFrame(context: Context) : FrameLayout(context) {
    init {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
