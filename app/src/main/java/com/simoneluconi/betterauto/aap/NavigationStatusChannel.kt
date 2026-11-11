package com.simoneluconi.betterauto.aap

import aap_protobuf.service.navigationstatus.NavigationStatusMessageIdOuterClass.NavigationStatusMessageId
import aap_protobuf.service.navigationstatus.NavigationStatusServiceOuterClass.NavigationStatusService
import aap_protobuf.service.navigationstatus.message.NavigationCueOuterClass.NavigationCue
import aap_protobuf.service.navigationstatus.message.NavigationCurrentPositionOuterClass.NavigationCurrentPosition
import aap_protobuf.service.navigationstatus.message.NavigationDestinationDistanceOuterClass.NavigationDestinationDistance
import aap_protobuf.service.navigationstatus.message.NavigationDestinationOuterClass.NavigationDestination
import aap_protobuf.service.navigationstatus.message.NavigationDistanceOuterClass.NavigationDistance
import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnDistanceEventOuterClass.NavigationNextTurnDistanceEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationRoadOuterClass.NavigationRoad
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.navigationstatus.message.NavigationStepDistanceOuterClass.NavigationStepDistance
import aap_protobuf.service.navigationstatus.message.NavigationStepOuterClass.NavigationStep
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.google.protobuf.ByteString
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.map.nav.ManeuverKind
import com.simoneluconi.betterauto.map.nav.NavState
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.phone.PhoneCountry
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.UnitSystem
import com.simoneluconi.betterauto.ui.map.iconOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * When the cluster's distance may go out: at most every [intervalMs], and only when the words shown change.
 * [delayFor] says 0 to send now, how long to wait for a change that comes too soon, or -1 with nothing new.
 */
internal class DistancePacer(private val intervalMs: Long) {
    private var sentText: String? = null
    private var sentAt = 0L
    private var everSent = false

    fun delayFor(text: String, now: Long): Long {
        if (text == sentText) return -1
        if (!everSent) return 0
        return (sentAt + intervalMs - now).coerceAtLeast(0)
    }

    fun sent(text: String, now: Long) {
        sentText = text
        sentAt = now
        everSent = true
    }

    /** A new maneuver (or none): its distance goes out even if the words are the same as the last one's. */
    fun reset() {
        sentText = null
    }
}

/**
 * Turn-by-turn for the car's own displays (instrument cluster, head-up display), as navigation apps do through
 * Android Auto: whether a navigation is on, the next maneuver with its road, how far it is, and the time and way
 * left to the destination. The car asks for it ([NavigationStatusMessageId.INSTRUMENT_CLUSTER_START]) when its
 * cluster can show it, and stops it again. Each car takes what it knows: the turn and distance events of older
 * head units, and the steps and position of newer ones, so both go out. A cluster that wants a picture
 * ([NavigationStatusService.InstrumentClusterType.IMAGE]) gets the maneuver's arrow drawn at its size.
 * The distance goes at most as often as the car allows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationStatusChannel(
    session: AapSession,
    channelId: Int,
    private val service: NavigationStatusService,
    private val context: Context,
    /** Asks the car to let us be its navigation ([AapSession]'s navigation focus). */
    private val requestNavFocus: () -> Unit,
) : ServiceChannel(session, channelId) {
    override val name = "navigation status"

    // Off the main thread: sending blocks on the USB link, so an IO thread, one at a time to keep the order
    private val scope = channelScope(Dispatchers.IO.limitedParallelism(1))
    private var job: Job? = null
    private var status: NavigationStatus.NavigationStatusEnum? = null
    private var turn: TurnKey? = null
    private val interval = service.minimumIntervalMs.toLong().coerceAtLeast(MIN_INTERVAL_MS)
    private val pacer = DistancePacer(interval)
    private var pendingDistance: Job? = null
    private var latest: NavState? = null

    /** What makes a new maneuver for the cluster. */
    private data class TurnKey(val kind: ManeuverKind, val road: String?, val exit: Int?, val at: Int, val clockwise: Boolean)

    override fun onOpened() {
        val image = if (service.hasImageOptions()) service.imageOptions.let { ", picture ${it.width}x${it.height} ${it.colourDepthBits}-bit" } else ""
        EventLog.log("Navigation status: cluster ${service.type.name.lowercase()}$image, every ${service.minimumIntervalMs} ms at most; waiting for the car")
        // The car starts its cluster for the navigation that has the focus: asked each time a guidance starts
        scope.launch {
            Navigation.state.map { it != null }.distinctUntilChanged().collect { guiding -> if (guiding) requestNavFocus() }
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            // On the channel's own thread, like every update: never two threads on the same state
            NavigationStatusMessageId.INSTRUMENT_CLUSTER_START_VALUE -> {
                EventLog.log("Navigation status: the car's cluster starts")
                scope.launch { start() }
            }
            NavigationStatusMessageId.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                EventLog.log("Navigation status: the car's cluster stops")
                scope.launch { stop() }
            }
            else -> EventLog.log("Navigation status: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun start() {
        stop()
        job = scope.launch { Navigation.state.collect { update(it) } }
    }

    private fun stop() {
        job?.cancel()
        job = null
        pendingDistance?.cancel()
        pendingDistance = null
        status = null
        turn = null
        latest = null
        pacer.reset()
    }

    private fun update(state: NavState?) {
        val now = when {
            state == null -> NavigationStatus.NavigationStatusEnum.INACTIVE
            state.rerouting -> NavigationStatus.NavigationStatusEnum.REROUTING
            else -> NavigationStatus.NavigationStatusEnum.ACTIVE
        }
        if (now != status) {
            status = now
            send(NavigationStatusMessageId.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, NavigationStatus.newBuilder().setStatus(now).build())
            EventLog.log("Navigation status: ${now.name.lowercase()}")
            if (now == NavigationStatus.NavigationStatusEnum.INACTIVE) {
                turn = null
                pacer.reset()
            }
        }
        latest = state?.takeUnless { it.rerouting }
        if (state == null || state.rerouting) return
        val next = state.next ?: return
        // The road only: the server's whole sentence does not fit a cluster, read at a glance
        val key = TurnKey(next.kind, next.street, next.roundaboutExit, next.beginIndex, clockwise())
        if (key != turn) {
            turn = key
            sendTurn(key)
            // The new maneuver's own distance, not the last one's
            pacer.reset()
        }
        distanceSoon()
    }

    /**
     * The distance as often as the car allows, and when what it shows changes. A change that comes too soon goes
     * out once the interval has passed: dropped, the cluster would keep the old value while the car stands still
     * (no new state comes then).
     */
    private fun distanceSoon() {
        val state = latest ?: return
        val units = units()
        val shown = distance(state.toNextM, units.first, units.second)
        val time = System.currentTimeMillis()
        val wait = pacer.delayFor(shown.text, time)
        when {
            wait == 0L -> {
                pacer.sent(shown.text, time)
                sendDistance(state, shown, units)
            }
            wait > 0 && pendingDistance?.isActive != true -> pendingDistance = scope.launch {
                delay(wait)
                pendingDistance = null
                distanceSoon()
            }
        }
    }

    // The old turn event on purpose: older head units know only that one
    @Suppress("DEPRECATION")
    private fun sendTurn(key: TurnKey) {
        val event = NavigationNextTurnEvent.newBuilder()
            .setRoad(key.road.orEmpty())
            .setEvent(turnEvent(key.kind))
            .setTurnSide(turnSide(key.kind))
        key.exit?.let { event.setTurnNumber(it) }
        if (service.type == NavigationStatusService.InstrumentClusterType.IMAGE && service.hasImageOptions()) {
            val options = service.imageOptions
            val mirrored = key.kind == ManeuverKind.ROUNDABOUT && key.clockwise
            runCatching { event.setImage(ByteString.copyFrom(iconOf(key.kind).toPng(options.width, options.height, mirrored))) }
                .onFailure { EventLog.log("Navigation status: no arrow picture ($it)") }
        }
        @Suppress("DEPRECATION")
        send(NavigationStatusMessageId.INSTRUMENT_CLUSTER_NAVIGATION_TURN_EVENT_VALUE, event.build())

        val maneuver = NavigationManeuver.newBuilder().setType(maneuverType(key.kind, key.clockwise))
        key.exit?.let { maneuver.setRoundaboutExitNumber(it) }
        val step = NavigationStep.newBuilder().setManeuver(maneuver)
        key.road?.let {
            step.setRoad(NavigationRoad.newBuilder().setName(it))
            step.setCue(NavigationCue.newBuilder().addAlternateText(it))
        }
        val state = NavigationState.newBuilder().addSteps(step)
        // The destination by its name, for clusters that show where the trip goes
        Trip.places.value.lastOrNull()?.let { place ->
            state.addDestinations(NavigationDestination.newBuilder().setAddress(listOfNotNull(place.name, place.address).joinToString(", ")))
        }
        send(NavigationStatusMessageId.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state.build())
        EventLog.log("Navigation status: next ${key.kind.name.lowercase()}")
    }

    private fun sendDistance(state: NavState, shown: Shown, units: Pair<Boolean, Locale>) {
        val toTurnSeconds = (state.remainingS * state.toNextM / state.remainingM.coerceAtLeast(1.0)).roundToInt()
        @Suppress("DEPRECATION")
        send(
            NavigationStatusMessageId.INSTRUMENT_CLUSTER_NAVIGATION_DISTANCE_EVENT_VALUE,
            NavigationNextTurnDistanceEvent.newBuilder()
                .setDistanceMeters(state.toNextM.roundToInt())
                .setTimeToTurnSeconds(toTurnSeconds)
                .setDisplayDistanceE3(shown.e3)
                .setDisplayDistanceUnit(NavigationNextTurnDistanceEvent.DistanceUnits.forNumber(shown.unit.number))
                .build(),
        )
        val remaining = distance(state.remainingM, units.first, units.second)
        val arrival = LocalTime.now().plusSeconds(state.remainingS.toLong())
        val clock = DateTimeFormatter.ofPattern(if (AppLocales.is24Hours(context, units.second)) "HH:mm" else "h:mm a", units.second)
        send(
            NavigationStatusMessageId.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE,
            NavigationCurrentPosition.newBuilder()
                .setStepDistance(
                    NavigationStepDistance.newBuilder()
                        .setDistance(navigationDistance(state.toNextM, shown))
                        .setTimeToStepSeconds(toTurnSeconds.toLong()),
                )
                .addDestinationDistances(
                    NavigationDestinationDistance.newBuilder()
                        .setDistance(navigationDistance(state.remainingM, remaining))
                        .setEstimatedTimeAtArrival(arrival.format(clock))
                        .setTimeToArrivalSeconds(state.remainingS.toLong()),
                )
                .build(),
        )
    }

    /** Roundabouts turn clockwise where traffic keeps left: the steering wheel on the right says so. */
    private fun clockwise(): Boolean = LauncherSettings.get(context).driverSide.value.onRight(CarState.driverOnRight.value)

    /** Imperial or not, and the formats' locale (decimal comma or point), as the launcher shows them. */
    private fun units(): Pair<Boolean, Locale> {
        val settings = LauncherSettings.get(context)
        val formats = AppLocales.formats(context)
        val imperial = when (settings.unitSystem.value) {
            UnitSystem.METRIC -> false
            UnitSystem.IMPERIAL -> true
            else -> {
                val where = Locale.Builder().setRegion(PhoneCountry.of(context)).build()
                LocaleData.getMeasurementSystem(ULocale.forLocale(where)) != LocaleData.MeasurementSystem.SI
            }
        }
        return imperial to formats
    }

    override fun close() = scope.cancel()

    /** A distance as the cluster shows it: the words, the thousandths for the old events, and the unit. */
    internal data class Shown(val text: String, val e3: Int, val unit: NavigationDistance.DistanceUnits)

    companion object {
        /** Never more often than this, whatever the car allows: a cluster reads at a glance. */
        private const val MIN_INTERVAL_MS = 1000L

        /** Rounded as a driver reads it, as on our own card: 10 m steps near, 50 m farther, then tenths of a km. */
        internal fun distance(metres: Double, imperial: Boolean, locale: Locale): Shown {
            if (imperial) {
                val miles = metres / 1609.344
                return when {
                    miles < 0.1 -> {
                        val feet = ((metres * 3.28084 / 50).roundToInt() * 50).coerceAtLeast(50)
                        Shown(feet.toString(), feet * 1000, NavigationDistance.DistanceUnits.FEET)
                    }
                    miles < 10 -> {
                        val tenths = (miles * 10).roundToInt() / 10.0
                        Shown("%.1f".format(locale, tenths), (tenths * 1000).roundToInt(), NavigationDistance.DistanceUnits.MILES_P1)
                    }
                    else -> Shown(miles.roundToInt().toString(), miles.roundToInt() * 1000, NavigationDistance.DistanceUnits.MILES)
                }
            }
            return when {
                metres < 1000 -> {
                    val step = if (metres < 300) 10 else 50
                    val m = ((metres / step).roundToInt() * step).coerceAtLeast(step)
                    Shown(m.toString(), m * 1000, NavigationDistance.DistanceUnits.METERS)
                }
                metres < 10_000 -> {
                    val tenths = (metres / 100).roundToInt() / 10.0
                    Shown("%.1f".format(locale, tenths), (tenths * 1000).roundToInt(), NavigationDistance.DistanceUnits.KILOMETERS_P1)
                }
                else -> {
                    val km = (metres / 1000).roundToInt()
                    Shown(km.toString(), km * 1000, NavigationDistance.DistanceUnits.KILOMETERS)
                }
            }
        }

        private fun navigationDistance(metres: Double, shown: Shown): NavigationDistance =
            NavigationDistance.newBuilder().setMeters(metres.roundToInt()).setDisplayValue(shown.text).setDisplayUnits(shown.unit).build()

        /** Our maneuvers in the old events' words. */
        @Suppress("DEPRECATION")
        internal fun turnEvent(kind: ManeuverKind): NavigationNextTurnEvent.NextTurnEnum = when (kind) {
            ManeuverKind.STRAIGHT -> NavigationNextTurnEvent.NextTurnEnum.STRAIGHT
            ManeuverKind.SLIGHT_LEFT, ManeuverKind.SLIGHT_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.SLIGHT_TURN
            ManeuverKind.LEFT, ManeuverKind.RIGHT -> NavigationNextTurnEvent.NextTurnEnum.TURN
            ManeuverKind.SHARP_LEFT, ManeuverKind.SHARP_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.SHARP_TURN
            ManeuverKind.U_TURN -> NavigationNextTurnEvent.NextTurnEnum.U_TURN
            ManeuverKind.ROUNDABOUT -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_ENTER_AND_EXIT
            ManeuverKind.ARRIVE -> NavigationNextTurnEvent.NextTurnEnum.DESTINATION
        }

        @Suppress("DEPRECATION")
        internal fun turnSide(kind: ManeuverKind): NavigationNextTurnEvent.TurnSide = when (kind) {
            ManeuverKind.SLIGHT_LEFT, ManeuverKind.LEFT, ManeuverKind.SHARP_LEFT, ManeuverKind.U_TURN -> NavigationNextTurnEvent.TurnSide.LEFT
            ManeuverKind.SLIGHT_RIGHT, ManeuverKind.RIGHT, ManeuverKind.SHARP_RIGHT -> NavigationNextTurnEvent.TurnSide.RIGHT
            else -> NavigationNextTurnEvent.TurnSide.UNSPECIFIED
        }

        /** Our maneuvers in the newer steps' words; roundabouts [clockwise] where traffic keeps left. */
        internal fun maneuverType(kind: ManeuverKind, clockwise: Boolean = false): NavigationManeuver.NavigationType = when (kind) {
            ManeuverKind.STRAIGHT -> NavigationManeuver.NavigationType.STRAIGHT
            ManeuverKind.SLIGHT_LEFT -> NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT
            ManeuverKind.SLIGHT_RIGHT -> NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT
            ManeuverKind.LEFT -> NavigationManeuver.NavigationType.TURN_NORMAL_LEFT
            ManeuverKind.RIGHT -> NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT
            ManeuverKind.SHARP_LEFT -> NavigationManeuver.NavigationType.TURN_SHARP_LEFT
            ManeuverKind.SHARP_RIGHT -> NavigationManeuver.NavigationType.TURN_SHARP_RIGHT
            ManeuverKind.U_TURN -> NavigationManeuver.NavigationType.U_TURN_LEFT
            ManeuverKind.ROUNDABOUT ->
                if (clockwise) NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW else NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW
            ManeuverKind.ARRIVE -> NavigationManeuver.NavigationType.DESTINATION
        }

        /** The arrow as the cluster's picture: white lines on a transparent ground, centred at its size; [mirrored] left to right. */
        private fun ImageVector.toPng(width: Int, height: Int, mirrored: Boolean = false): ByteArray {
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (mirrored) canvas.scale(-1f, 1f, width / 2f, height / 2f)
            val side = minOf(width, height) * 0.85f
            canvas.translate((width - side) / 2, (height - side) / 2)
            canvas.scale(side / viewportWidth, side / viewportHeight)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.WHITE
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = android.graphics.Color.WHITE }
            fun draw(group: VectorGroup) {
                for (node in group) {
                    when (node) {
                        is VectorGroup -> draw(node)
                        is VectorPath -> canvas.drawPath(PathParser().addPathNodes(node.pathData).toPath().asAndroidPath(), if (node.fill != null) fill else stroke)
                    }
                }
            }
            draw(root)
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }
    }
}
