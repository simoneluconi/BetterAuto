package com.simoneluconi.betterauto.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.map.MapFix
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.MapStyle
import com.simoneluconi.betterauto.map.bearingDeg
import com.simoneluconi.betterauto.map.distanceM
import com.simoneluconi.betterauto.map.nav.Maneuver
import com.simoneluconi.betterauto.map.nav.ManeuverKind
import com.simoneluconi.betterauto.map.nav.NavRoute
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.roads.RoadInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import com.simoneluconi.betterauto.settings.MapOrientation
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.SignWhite
import com.simoneluconi.betterauto.ui.theme.MapColors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import kotlin.math.roundToInt

/**
 * What a screen asks of its [CarMap]: zoom, and back to the car. [following] turns false when the driver
 * moves the map by touch; the map comes back to the car by itself after a while, at [defaultZoom].
 */
@Stable
class CarMapState(private var defaultZoom: Double, private val tilt: Double) {
    var following by mutableStateOf(true)
        private set

    /** When the driver last moved the map by hand ([SystemClock.uptimeMillis]). */
    var touchedAt by mutableLongStateOf(0L)
        internal set

    /** The zoom the map is at, or going to. */
    var zoom = defaultZoom
        internal set

    internal var map: MapLibreMap? = null
    internal var orientation = MapOrientation.HEADING
    internal var heightPx = 0

    /** How far out the speed takes the map: 0 in town, 1 on faster roads, 2 on motorways. */
    private var speedBand = 0

    /** The driver chose a zoom with the knob or the buttons: the speed leaves it alone until the map comes back. */
    private var zoomChosen = false

    /** [steps] whole zoom levels in (positive) or out, from where the last step was going. */
    fun zoomBy(steps: Double) {
        val map = map ?: return
        zoomChosen = true
        zoom = (zoom + steps).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val component = map.locationComponent
        when {
            // Ignored by MapLibre meanwhile: applied as the transition ends
            transitioning() -> Unit
            following && component.isLocationComponentActivated && component.cameraMode in Tracking -> component.zoomWhileTracking(zoom, ZOOM_MS)
            else -> map.animateCamera(CameraUpdateFactory.zoomTo(zoom), ZOOM_MS.toInt())
        }
    }

    /** Back to the car, at the default zoom (as far out as the speed asks). */
    fun recenter() {
        following = true
        zoomChosen = false
        zoom = speedZoom()
        map?.let { camera(it, animate = true) }
    }

    /** The driver picked another default zoom: the map goes there at once. */
    fun setDefaultZoom(level: Double) {
        if (level == defaultZoom) return
        defaultZoom = level
        zoomChosen = false
        zoom = speedZoom()
        map?.let { camera(it, animate = true) }
    }

    /**
     * The car's speed ([enabled]: the "zoom with speed" setting): past a band's threshold the map slowly
     * goes one step farther out, or back in. A band is left a little below where it starts, so a speed
     * hovering around a threshold doesn't pump the zoom.
     */
    internal fun onSpeed(speedMs: Float?, enabled: Boolean) {
        val band = if (!enabled) 0 else speedMs?.let { speedBand(it * 3.6, speedBand) } ?: speedBand
        if (band == speedBand) return
        speedBand = band
        if (zoomChosen || !following) return
        zoom = speedZoom()
        val component = map?.locationComponent ?: return
        if (!transitioning() && component.isLocationComponentActivated && component.cameraMode in Tracking) component.zoomWhileTracking(zoom, SPEED_ZOOM_MS)
    }

    private fun speedZoom() = (defaultZoom - speedBand).coerceIn(MIN_ZOOM, MAX_ZOOM)

    internal fun dismissed() {
        following = false
    }

    /** The camera went to the car once: until then it waits far out for the first position. */
    internal var placed = false

    /**
     * When a camera mode change started ([SystemClock.uptimeMillis]), 0 with none: MapLibre ignores zoom, tilt and
     * padding until it ends, so they wait for its end ([settle]).
     */
    private var transitionSince = 0L

    private fun transitioning(): Boolean {
        // Never stuck: a transition that never reported its end is over anyway
        if (transitionSince != 0L && SystemClock.uptimeMillis() - transitionSince > TRANSITION_MAX_MS) transitionSince = 0L
        return transitionSince != 0L
    }

    private val transitionEnd = object : OnLocationCameraTransitionListener {
        override fun onLocationCameraTransitionFinished(cameraMode: Int) = settle()
        override fun onLocationCameraTransitionCanceled(cameraMode: Int) = settle()
    }

    /** The mode change is over: what was asked meanwhile (a knob zoom, the size, the speed, the orientation) now. */
    private fun settle() {
        transitionSince = 0L
        map?.let { camera(it, animate = true) }
    }

    /** Follows the car: turning with it and looking ahead, or north up with the car in the middle. */
    internal fun camera(map: MapLibreMap, animate: Boolean) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated || !following || MapLocation.fix.value == null) return
        placed = true
        if (transitioning()) return
        val heading = orientation == MapOrientation.HEADING
        val duration = if (animate) CAMERA_MS else 0L
        val pitch = if (heading) tilt else 0.0
        val mode = if (heading) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
        // Looking ahead: the car sits low on the screen, the road in front gets the room
        component.paddingWhileTracking(doubleArrayOf(0.0, if (heading) heightPx * AHEAD_SHARE else 0.0, 0.0, 0.0), duration)
        if (component.cameraMode == mode) {
            // Already following this way: a new mode would start a transition that swallows the zoom and tilt
            component.zoomWhileTracking(zoom, duration)
            component.tiltWhileTracking(pitch, duration)
            return
        }
        transitionSince = SystemClock.uptimeMillis()
        component.setCameraMode(mode, duration, zoom, null, pitch, transitionEnd)
    }

    /** Positions in a row the camera was not with the car. */
    private var lostFixes = 0

    /**
     * Following, but the camera is not with the car: tracking lost on the way, or a camera that says it tracks while
     * it stays put (a big jump of the position, e.g. a car GPS back from a garage 3 km off, leaves MapLibre in a
     * transition that never ends: the map stood still until the car drove off the screen). Following, the camera's
     * centre is the car, a glide behind at most: farther than the car goes in a few seconds, twice in a row, the
     * camera goes there at once.
     */
    internal fun keepTracking(map: MapLibreMap, fix: MapFix) {
        val component = map.locationComponent
        if (!following || !component.isLocationComponentActivated) return
        val target = map.cameraPosition.target
        val away = target?.let { distanceM(it.latitude, it.longitude, fix.latitude, fix.longitude) } ?: 0.0
        val allowed = maxOf(LOST_MIN_M, (fix.speedMs ?: 0f).toDouble() * LOST_SECONDS)
        lostFixes = if (away > allowed && !transitioning()) lostFixes + 1 else 0
        if (lostFixes >= LOST_FIXES) {
            EventLog.log("Map: the camera was ${away.roundToInt()} m from the car, back to it")
            lostFixes = 0
            transitionSince = 0L
            component.cameraMode = CameraMode.NONE
            map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(fix.latitude, fix.longitude)))
            camera(map, animate = false)
            return
        }
        if (component.cameraMode !in Tracking && !transitioning()) camera(map, animate = true)
    }

    companion object {
        const val MIN_ZOOM = 4.0
        const val MAX_ZOOM = 20.0
        private const val ZOOM_MS = 250L
        private const val CAMERA_MS = 750L
        private const val TRANSITION_MAX_MS = CAMERA_MS + 1_500L

        /** The camera this far from the car while following, or as far as the car goes in [LOST_SECONDS], is lost. */
        private const val LOST_MIN_M = 50.0
        private const val LOST_SECONDS = 3.0
        private const val LOST_FIXES = 2
        private const val AHEAD_SHARE = 0.35
        /** A zoom change for speed is slow, the eye barely notices it. */
        private const val SPEED_ZOOM_MS = 2_500L

        /** The speed band for [kmh], coming from [current]: up at 65 and 105 km/h, back down at 55 and 95. */
        internal fun speedBand(kmh: Double, current: Int): Int = when {
            kmh >= 105 -> 2
            kmh >= 95 && current == 2 -> 2
            kmh >= 65 -> 1
            kmh >= 55 && current >= 1 -> 1
            else -> 0
        }
        private val Tracking = setOf(CameraMode.TRACKING, CameraMode.TRACKING_COMPASS, CameraMode.TRACKING_GPS, CameraMode.TRACKING_GPS_NORTH)
    }
}

/**
 * The map in our style (`res/raw/map_style.json`, OpenFreeMap tiles), with the car's arrow on it, following
 * [MapLocation]. Day and night fade with the rest of the UI. [interactive]: touch can move and zoom it
 * (the Map section); otherwise it only follows the car (the home widget). Drawn in a TextureView, so it
 * clips, fades and scales like any other part of the launcher.
 */
@Composable
fun CarMap(
    state: CarMapState,
    orientation: MapOrientation,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = 32.dp,
    /** The car's arrow, about 6-7% of a full map's height as in a factory navigation: a small map takes a smaller one. */
    arrow: Dp = 70.dp,
    /** Farther out as the car goes faster (a setting). */
    autoZoom: Boolean = false,
) {
    val context = LocalContext.current
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    val palette = LocalCarColors.current
    val colors by rememberUpdatedState(palette.map)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .attributionEnabled(false)
                .logoEnabled(false)
                .compassEnabled(false)
                .foregroundLoadColor(palette.map.land.toArgb()),
        ).apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // The map draws in a TextureView, which Compose's rounded clip does not reach: the view rounds itself,
            // like the cards around it
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
            clipToOutline = true
            // Before it is attached: the drawing surface is set up here
            onCreate(null)
        }
    }
    var style by remember { mutableStateOf<Style?>(null) }
    // Known before the first camera move, so the map opens the right way round
    SideEffect { state.orientation = orientation }

    MapViewLifecycle(mapView, lifecycle)

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            // MapLibre makes its view focusable as it sets up (the d-pad pans it): the knob stopped on it, an empty
            // stop between the map and its buttons
            mapView.isFocusable = false
            mapView.isFocusableInTouchMode = false
            // No more than the car's video takes: frames beyond it would only cost battery
            mapView.setMaximumFps(MAX_FPS)
            setUp(context, map, state, interactive, colors) { style = it }
        }
        onDispose {
            state.map = null
            style = null
        }
    }

    // The position and the road only while the map is on screen: the phone's GPS and the network rest otherwise
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val releaseLocation = MapLocation.request(context)
            val releaseRoad = RoadInfo.request(context)
            try {
                awaitCancellation()
            } finally {
                releaseRoad()
                releaseLocation()
            }
        }
    }

    LaunchedEffect(autoZoom) {
        state.onSpeed(MapLocation.fix.value?.speedMs, autoZoom)
        MapLocation.fix.filterNotNull().collect { state.onSpeed(it.speedMs, autoZoom) }
    }

    LaunchedEffect(style) {
        val map = state.map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        var fedAt = 0L
        MapLocation.fix.filterNotNull().collect { fix ->
            // A car sends some fixes a tenth of a second after the one before: MapLibre glides to the next one in the
            // time between the last two, so the car lurched ahead and then stood still for a second. Evenly, at most
            // about one a second
            if (fedAt != 0L && fix.at - fedAt in 0 until MIN_FEED_MS) return@collect
            fedAt = fix.at
            // On the route, or else on the road, when the GPS is just a few metres beside it
            val shown = Navigation.snapToRoute(fix) ?: RoadInfo.snap(fix) ?: fix
            map.locationComponent.forceLocationUpdate(shown.toLocation())
            // The map opened before any position, far out: the first one brings it down to the car
            if (!state.placed) state.camera(map, animate = true) else state.keepTracking(map, shown)
        }
    }

    LaunchedEffect(style, palette.map) {
        style?.let { MapStyle.apply(context, it, palette.map) }
    }

    // The route being followed, under the street names and the car
    LaunchedEffect(style) {
        val loaded = style ?: return@LaunchedEffect
        Navigation.state.map { it?.route }.distinctUntilChanged().collect { showRoute(loaded, it) }
    }

    // The next maneuver drawn on the road, as a factory navigation does: redrawn only when it changes
    LaunchedEffect(style) {
        val loaded = style ?: return@LaunchedEffect
        Navigation.state.map { s -> s?.next?.let { s.route to it } }.distinctUntilChanged().collect { showManeuver(loaded, it?.first, it?.second) }
    }

    // The route in the focus colour; the car's arrow on it keeps a wide edge to stand apart
    LaunchedEffect(style, palette.focus, palette.map.halo) {
        style?.let {
            colorRoute(it, palette.focus, palette.map.halo)
            it.addImage(MANEUVER_HEAD, arrowHeadBitmap(context, palette.focus))
        }
    }

    // The car in the labels' grey (slate by day, light at night): it stands out on the blue route
    LaunchedEffect(style, palette.text2, palette.map.halo) {
        style?.addImage(PUCK, puckBitmap(context, arrow.value, palette.text2, palette.map.halo))
    }

    LaunchedEffect(style, orientation) {
        state.map?.takeIf { style != null }?.let { state.camera(it, animate = true) }
    }

    LaunchedEffect(style, orientation, state.following) {
        style?.let { alignArrow(it, state.following && orientation == MapOrientation.HEADING) }
    }

    // A map moved by hand comes back to the car once the driver leaves it alone
    LaunchedEffect(state.following, state.touchedAt) {
        if (state.following) return@LaunchedEffect
        delay(RECENTER_AFTER_MS)
        state.recenter()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.onSizeChanged {
            if (it.height == state.heightPx) return@onSizeChanged
            state.heightPx = it.height
            state.map?.let { map -> state.camera(map, animate = false) }
        },
    )
}

@SuppressLint("MissingPermission") // our own positions only: the component never asks the phone for any
private fun setUp(
    context: Context,
    map: MapLibreMap,
    state: CarMapState,
    interactive: Boolean,
    colors: MapColors,
    onStyle: (Style) -> Unit,
) {
    map.setMinZoomPreference(CarMapState.MIN_ZOOM)
    map.setMaxZoomPreference(CarMapState.MAX_ZOOM)
    map.uiSettings.apply {
        if (interactive) {
            // Turning and tilting by hand would fight the camera that follows the car
            setRotateGesturesEnabled(false)
            setTiltGesturesEnabled(false)
        } else {
            setAllGesturesEnabled(false)
        }
    }
    val start = MapLocation.fix.value
    map.cameraPosition = if (start != null) {
        CameraPosition.Builder().target(LatLng(start.latitude, start.longitude)).zoom(state.zoom).build()
    } else {
        CameraPosition.Builder().target(LatLng(NO_FIX_LAT, NO_FIX_LON)).zoom(NO_FIX_ZOOM).build()
    }
    var byHand = false
    map.addOnCameraMoveStartedListener { reason ->
        byHand = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
        if (byHand) state.touchedAt = SystemClock.uptimeMillis()
    }
    // A pinch changes the zoom too: the next knob step goes on from there. Only a hand's: the far view
    // before the first position is not a zoom anyone chose
    map.addOnCameraIdleListener { if (byHand) state.zoom = map.cameraPosition.zoom }

    map.setStyle(Style.Builder().fromJson(MapStyle.json(context, colors))) { loaded ->
        // A placeholder until the theme's colors are set (just below, as the style arrives)
        loaded.addImage(PUCK, puckBitmap(context, 1f, Color.Transparent, Color.Transparent))
        val component = map.locationComponent
        val options = LocationComponentOptions.builder(context)
            .gpsName(PUCK)
            // Seen at a glance at any zoom: it shrinks only a little when the map is far out
            .minZoomIconScale(0.85f)
            .maxZoomIconScale(1f)
            .accuracyAlpha(0f)
            .pulseEnabled(false)
            .enableStaleState(false)
            .elevation(0f)
            .build()
        component.activateLocationComponent(
            LocationComponentActivationOptions.Builder(context, loaded)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .build(),
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.GPS
        alignArrow(loaded, state.following && state.orientation == MapOrientation.HEADING)
        component.setMaxAnimationFps(MAX_FPS)
        component.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() = state.dismissed()
            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        })
        MapLocation.fix.value?.let { component.forceLocationUpdate(it.toLocation()) }
        state.map = map
        state.camera(map, animate = false)
        onStyle(loaded)
    }
}

/** Draws [route] (or nothing), adding its source and lines the first time: below the road names, so they stay readable. */
private fun showRoute(style: Style, route: NavRoute?) {
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: GeoJsonSource(ROUTE_SOURCE).also { s ->
        style.addSource(s)
        val width = { near: Float, far: Float ->
            Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(10, far), Expression.stop(18, near))
        }
        val casing = LineLayer(ROUTE_CASING, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineWidth(width(30f, 7f)),
        )
        val line = LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineWidth(width(22f, 4f)),
        )
        if (style.getLayer(FIRST_LABEL) != null) {
            style.addLayerBelow(casing, FIRST_LABEL)
            style.addLayerBelow(line, FIRST_LABEL)
        } else {
            style.addLayer(casing)
            style.addLayer(line)
        }
    }
    source.setGeoJson(route?.let { lineGeoJson(it.points) } ?: EMPTY_FEATURES)
}

private fun colorRoute(style: Style, line: Color, casing: Color) {
    style.getLayer(ROUTE_LINE)?.setProperties(PropertyFactory.lineColor(line.toArgb()))
    style.getLayer(ROUTE_CASING)?.setProperties(PropertyFactory.lineColor(casing.toArgb()))
}

/**
 * The next maneuver as an arrow on the route: a white line along the road from a little before the turn to a little
 * after it (through a roundabout to its exit), with a head at the end, narrower than the route under it, and the same
 * for the one after when it follows close. Nothing for going straight on or the arrival. Added the first time, above the road names, which give way to it (one written
 * across it hid the head), and below the car.
 */
private fun showManeuver(style: Style, route: NavRoute?, next: Maneuver?) {
    val line = style.getSourceAs<GeoJsonSource>(MANEUVER_SOURCE) ?: GeoJsonSource(MANEUVER_SOURCE).also { s ->
        style.addSource(s)
        style.addSource(GeoJsonSource(MANEUVER_HEAD_SOURCE))
        val width = Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(12, 3f), Expression.stop(18, 18f))
        val iconSize = Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(12, 0.35f), Expression.stop(18, 1.8f))
        val shaft = LineLayer(MANEUVER_LINE, MANEUVER_SOURCE).withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(width), PropertyFactory.lineColor(SignWhite.toArgb()),
        )
        // Unseen marks all along the shaft: the names placed after them (every layer below) keep away from it
        val clear = SymbolLayer(MANEUVER_CLEAR_LAYER, MANEUVER_SOURCE).withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            PropertyFactory.symbolSpacing(CLEAR_SPACING_PX),
            PropertyFactory.iconImage(MANEUVER_HEAD),
            PropertyFactory.iconOpacity(0f),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(iconSize),
        )
        val head = SymbolLayer(MANEUVER_HEAD_LAYER, MANEUVER_HEAD_SOURCE).withProperties(
            PropertyFactory.iconImage(MANEUVER_HEAD),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            // Lying on the road, pointing along it, whatever else is drawn there
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(iconSize),
        )
        // The location component's layers are the car: the arrow goes under it
        val car = style.layers.firstOrNull { it.id.startsWith(LOCATION_LAYERS) }?.id
        for (layer in listOf(shaft, clear, head)) {
            if (car != null) style.addLayerBelow(layer, car) else style.addLayer(layer)
        }
    }
    val heads = style.getSourceAs<GeoJsonSource>(MANEUVER_HEAD_SOURCE) ?: return
    // The one after too when it comes right after: the driver gets ready for both
    val pieces = if (route == null) emptyList() else {
        listOfNotNull(next, followingSoon(route, next)).map { arrowPiece(route, it) }.filter { it.size >= 4 }
    }
    line.setGeoJson(featureCollection(pieces.map { lineGeoJson(it) }))
    heads.setGeoJson(
        featureCollection(
            pieces.map { piece ->
                val n = piece.size / 2
                val bearing = bearingDeg(piece[2 * n - 4], piece[2 * n - 3], piece[2 * n - 2], piece[2 * n - 1])
                """{"type":"Feature","properties":{"bearing":$bearing},"geometry":{"type":"Point","coordinates":[${piece[2 * n - 1]},${piece[2 * n - 2]}]}}"""
            },
        ),
    )
}

/** The part of [route] [m]'s arrow covers (latitude, longitude pairs); none for going straight on or the arrival. */
private fun arrowPiece(route: NavRoute, m: Maneuver): DoubleArray {
    if (m.kind == ManeuverKind.STRAIGHT || m.kind == ManeuverKind.ARRIVE) return DoubleArray(0)
    val at = route.cumulative[m.beginIndex]
    val end = m.exitIndex?.let { route.cumulative[it] } ?: at
    return route.slice(at - ARROW_BEFORE_M, end + ARROW_AFTER_M)
}

/** The maneuver after [next], when it comes within [FOLLOWING_M] of it (of a roundabout's exit). */
private fun followingSoon(route: NavRoute, next: Maneuver?): Maneuver? {
    next ?: return null
    val after = route.maneuvers.getOrNull(route.maneuvers.indexOf(next) + 1) ?: return null
    val end = route.cumulative[next.exitIndex ?: next.beginIndex]
    return after.takeIf { route.cumulative[it.beginIndex] - end <= FOLLOWING_M }
}

private fun featureCollection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

/**
 * The head of the maneuver's arrow, pointing up: white, its two sides edged in [edge] so it shows past the route's
 * sides. The base stays open, flat and white: the shaft runs into it, one shape (edged all round, it looked shut off).
 */
private fun arrowHeadBitmap(context: Context, edge: Color): Bitmap {
    val metrics = context.resources.displayMetrics
    val size = (metrics.density * ARROW_HEAD_DP).roundToInt().coerceAtLeast(8)
    // The car screen's density, as for the car's arrow: left at the phone's, MapLibre drew the head a third as big,
    // no wider than the line (a road test: "it doesn't look like an arrow")
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { density = metrics.densityDpi }
    val canvas = android.graphics.Canvas(bitmap)
    // The two sides, from the base's left corner over the tip to its right one
    val sides = android.graphics.Path().apply {
        moveTo(size * 0.08f, size * 0.86f)
        lineTo(size / 2f, size * 0.08f)
        lineTo(size * 0.92f, size * 0.86f)
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = android.graphics.Paint.Join.ROUND }
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = SignWhite.toArgb()
    canvas.drawPath(android.graphics.Path(sides).apply { close() }, paint)
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = size * 0.06f
    paint.color = edge.toArgb()
    canvas.drawPath(sides, paint)
    return bitmap
}

/** A line through [points] (latitude, longitude pairs) as GeoJSON, which wants longitude first. */
private fun lineGeoJson(points: DoubleArray): String = buildString {
    append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
    for (i in 0 until points.size / 2) {
        if (i > 0) append(',')
        append('[').append(points[2 * i + 1]).append(',').append(points[2 * i]).append(']')
    }
    append("]}}")
}

private const val EMPTY_FEATURES = """{"type":"FeatureCollection","features":[]}"""
private const val ROUTE_SOURCE = "betterauto-route"
private const val ROUTE_LINE = "betterauto-route-line"
private const val ROUTE_CASING = "betterauto-route-casing"
private const val MANEUVER_SOURCE = "betterauto-maneuver"
private const val MANEUVER_LINE = "betterauto-maneuver-line"
private const val MANEUVER_HEAD_SOURCE = "betterauto-maneuver-head"
private const val MANEUVER_HEAD_LAYER = "betterauto-maneuver-head"
private const val MANEUVER_HEAD = "betterauto-maneuver-head-image"
private const val MANEUVER_CLEAR_LAYER = "betterauto-maneuver-clear"
private const val CLEAR_SPACING_PX = 12f

/** The start of the ids of the layers MapLibre's location component adds (LocationComponentConstants). */
private const val LOCATION_LAYERS = "mapbox-location-"

/** The maneuver's arrow starts this far before the turn and ends this far after it (40 and 25 looked long). */
private const val ARROW_BEFORE_M = 20.0
private const val ARROW_AFTER_M = 20.0

/** The maneuver after the next one this close after it: an arrow for it too. */
private const val FOLLOWING_M = 200.0
private const val ARROW_HEAD_DP = 40f

/** The style's lowest label layer (res/raw/map_style.json): the route goes under it. */
private const val FIRST_LABEL = "label_road"

/**
 * The car's arrow stands up facing the driver instead of lying on the tilted map, where it looked squashed.
 * [locked]: the map turns with the car, so the arrow just points up; turning it as well, two animations that
 * don't quite agree left it a few degrees askew. Otherwise (north up, or the map moved by hand) it turns with
 * the car's heading. On the location component's own layers (LocationComponentConstants), once it made them.
 */
private fun alignArrow(style: Style, locked: Boolean) {
    for (layer in listOf("mapbox-location-foreground-layer", "mapbox-location-background-layer")) {
        style.getLayer(layer)?.setProperties(
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(if (locked) Property.ICON_ROTATION_ALIGNMENT_VIEWPORT else Property.ICON_ROTATION_ALIGNMENT_MAP),
            if (locked) PropertyFactory.iconRotate(0f) else PropertyFactory.iconRotate(Expression.get("mapbox-property-gps-bearing")),
        )
    }
}

/** Drives the MapView with the launcher's lifecycle: it stops drawing while the car shows its own screen. */
@Composable
private fun MapViewLifecycle(mapView: MapView, lifecycle: Lifecycle) {
    DisposableEffect(mapView, lifecycle) {
        // Created as it was made
        var at = Lifecycle.State.CREATED
        fun moveTo(target: Lifecycle.State) {
            if (at < Lifecycle.State.STARTED && target >= Lifecycle.State.STARTED) { mapView.onStart(); at = Lifecycle.State.STARTED }
            if (at < Lifecycle.State.RESUMED && target >= Lifecycle.State.RESUMED) { mapView.onResume(); at = Lifecycle.State.RESUMED }
            if (at == Lifecycle.State.RESUMED && target < Lifecycle.State.RESUMED) { mapView.onPause(); at = Lifecycle.State.STARTED }
            if (at == Lifecycle.State.STARTED && target < Lifecycle.State.STARTED) { mapView.onStop(); at = Lifecycle.State.CREATED }
        }
        val observer = LifecycleEventObserver { _, _ -> moveTo(maxOf(lifecycle.currentState, Lifecycle.State.CREATED)) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            moveTo(Lifecycle.State.CREATED)
            mapView.onDestroy()
        }
    }
}

/** The car: an arrow in [fill], outlined in [outline] so it stands out on any road and on the route. */
private fun puckBitmap(context: Context, sizeDp: Float, fill: Color, outline: Color): Bitmap {
    val metrics = context.resources.displayMetrics
    val size = (sizeDp * metrics.density).roundToInt().coerceAtLeast(16)
    // The car screen's density, not the phone's: MapLibre sizes the image by it
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { density = metrics.densityDpi }
    val s = size.toFloat()
    val arrow = Path().apply {
        moveTo(s * 0.5f, s * 0.12f)
        lineTo(s * 0.84f, s * 0.86f)
        lineTo(s * 0.5f, s * 0.68f)
        lineTo(s * 0.16f, s * 0.86f)
        close()
    }
    val canvas = Canvas(bitmap)
    canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // A wide edge: it keeps the arrow apart from the route drawn under it
        strokeWidth = s * 0.15f
        strokeJoin = Paint.Join.ROUND
        color = outline.toArgb()
    })
    canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fill.toArgb()
    })
    return bitmap
}

private const val PUCK = "betterauto-car"
private const val MAX_FPS = 30


/** Fixes closer together than this are not drawn one by one (see the position collector). */
private const val MIN_FEED_MS = 500L
private const val RECENTER_AFTER_MS = 15_000L

/** Before the first position: Europe from afar. */
private const val NO_FIX_LAT = 45.0
private const val NO_FIX_LON = 10.0
private const val NO_FIX_ZOOM = 4.0
