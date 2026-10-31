package com.simoneluconi.betterauto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.Destinations
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.PlaceResult
import com.simoneluconi.betterauto.map.nav.PlaceSearch
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.map.TripChoice
import com.simoneluconi.betterauto.ui.map.TripPlaces
import com.simoneluconi.betterauto.ui.map.distanceText
import com.simoneluconi.betterauto.ui.smarthome.PhoneTextField
import com.simoneluconi.betterauto.ui.theme.CarTheme
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.vehicle.rememberUnits
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The trip on the phone: what another app shares (a Google Maps place, a link, an address in a message, a contact)
 * goes to the car; a search for addresses and places (shops, restaurants, cinemas) with the recent destinations;
 * and the trip itself, its stops moved, removed or added, for a passenger while the driver drives or for the
 * driver before leaving (the trip waits for the car). Opened by the app, a share or the car's "Search on the
 * phone". During a trip a place asks whether it is a stop or the new destination. A share that names no place
 * opens the search with its text, so there is always a way on.
 */
class DestinationActivity : ComponentActivity() {
    private var prefill by mutableStateOf("")
    private var choosing by mutableStateOf<Destination?>(null)

    /** Looking for what was shared: said at once, so the page is never an empty window that eats the taps. */
    private var resolving by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Trip.init(this)
        val shared = sharedText(intent)
        if (shared != null && savedInstanceState == null) {
            resolving = true
            showSearch()
            lifecycleScope.launch {
                val found = PlaceSearch.resolve(this@DestinationActivity, shared.first, shared.second)
                resolving = false
                if (found != null && Trip.places.value.isEmpty()) {
                    startTrip(this@DestinationActivity, found)
                    finish()
                } else if (found != null) {
                    choosing = found
                } else {
                    Toast.makeText(this@DestinationActivity, R.string.nav_share_not_found, Toast.LENGTH_SHORT).show()
                    prefill = shared.second ?: shared.first.lines().firstOrNull { !it.contains("://") }.orEmpty()
                }
            }
            return
        }
        showSearch()
    }

    private fun showSearch() {
        setContent {
            CarTheme(night = isSystemInDarkTheme(), album = null) {
                var query by remember(prefill) { mutableStateOf(prefill) }
                BackHandler(enabled = choosing != null) { choosing = null }
                Box {
                    TripPage(query, { query = it }, onBack = { finish() }) { place ->
                        if (Trip.places.value.isEmpty()) {
                            startTrip(this@DestinationActivity, place)
                            finish()
                        } else {
                            choosing = place
                        }
                    }
                    choosing?.let { place ->
                        TripChoice(
                            place, Trip.places.value.lastOrNull(),
                            onStop = {
                                val fix = MapLocation.fix.value
                                val added = Trip.addStop(place, fix?.latitude, fix?.longitude)
                                val said = getString(if (added) R.string.nav_stop_added else R.string.nav_stop_already, place.name)
                                Toast.makeText(this@DestinationActivity, said, Toast.LENGTH_SHORT).show()
                                choosing = null
                                // Back to the trip, with the new stop in it
                                query = ""
                            },
                            onNewDestination = {
                                startTrip(this@DestinationActivity, place)
                                finish()
                            },
                            onCancel = { choosing = null },
                            knob = false,
                        )
                    }
                    if (resolving) {
                        Box(Modifier.fillMaxSize().background(LocalCarColors.current.bg), contentAlignment = Alignment.Center) {
                            CarLabel(stringResource(R.string.nav_resolving), 18.sp, LocalCarColors.current.text2, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    /** The text (and a title, when the app gives one) of what was shared, or of a map link opened here. */
    private fun sharedText(intent: Intent?): Pair<String, String?>? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { it to title }
                    ?: streamText(intent)?.let { vcard -> PlaceSearch.vCardAddress(vcard)?.let { (name, address) -> address to name } }
            }
            Intent.ACTION_VIEW -> intent.dataString?.let { it to null }
            else -> null
        }
    }

    /** A shared contact card, as text. */
    @Suppress("DEPRECATION")
    private fun streamText(intent: Intent): String? {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        return runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
    }

    companion object {
        /** A new trip to [destination], and says so: guided now if the car screen is up, else at the next connection. */
        fun startTrip(context: Context, destination: Destination) {
            Trip.newDestination(destination)
            val connected = VehicleData.snapshot.value.connected
            val text = context.getString(if (connected) R.string.nav_sent_to_car else R.string.nav_sent_later, destination.name)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }

        /** The car's "Search on the phone": a notification that opens this search, the driver's phone in hand. */
        fun notifySearch(context: Context) {
            // The permission exists from Android 13; before it, asking for it says "denied" even when allowed
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                EventLog.log("Navigation: no notification permission for the search on the phone")
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.nav_channel), NotificationManager.IMPORTANCE_HIGH),
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, DestinationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.nav_notify_search_title))
                .setContentText(context.getString(R.string.nav_notify_search_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_NAVIGATION)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            EventLog.log("Navigation: search notification posted on the phone")
        }

        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 7301
    }
}

/** The search, and under it the trip (when there is one) and the recent places, or what the search found. */
@Composable
private fun TripPage(query: String, onQuery: (String) -> Unit, onBack: () -> Unit, onChoose: (Destination) -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<PlaceResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val recents by Destinations.recents.collectAsStateWithLifecycle()
    val places by Trip.places.collectAsStateWithLifecycle()

    // Searched as the driver types, once the typing pauses
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        searching = true
        // Typing on (or clearing the field) cancels it: never left "searching" for a search nobody waits for
        try {
            results = PlaceSearch.search(context, query)
        } finally {
            searching = false
        }
    }

    // Laid out as the app's other pages: back arrow and title, then the content
    Column(
        Modifier.fillMaxSize().background(colors.bg).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            CarLabel(stringResource(R.string.nav_card_title), 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold, shrinkToFit = true)
        }
        PhoneTextField(query, onQuery, stringResource(R.string.nav_search_hint), keyboard = KeyboardType.Text)
        val shown = results
        // The places found are OpenStreetMap's, through Photon: credited as the licence asks
        if (shown != null) CarLabel(stringResource(R.string.nav_search_attribution), 12.sp, colors.text2, maxLines = 1)
        when {
            searching && shown == null -> CarLabel(stringResource(R.string.nav_searching), 16.sp, colors.text2)
            shown != null && shown.isEmpty() -> CarLabel(stringResource(R.string.nav_search_none), 16.sp, colors.text2)
            shown != null -> PlaceList(shown.map { it.destination to it.distanceM }, onChoose)
            else -> PlaceList(
                recents.map { it to null }, onChoose,
                header = {
                    if (places.isNotEmpty()) TripCard(places)
                    CarLabel(
                        stringResource(if (recents.isEmpty()) R.string.nav_no_recents else R.string.nav_recents), 18.sp, colors.text2,
                        weight = if (recents.isEmpty()) FontWeight.Normal else FontWeight.Bold, maxLines = 2,
                    )
                },
            )
        }
    }
}

/** The trip being driven or waiting for the car: its places to move or remove, and the way to end it. */
@Composable
private fun TripCard(places: List<Destination>) {
    val colors = LocalCarColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CarLabel(stringResource(R.string.nav_trip), 21.sp, colors.text, weight = FontWeight.Bold)
        if (places.size > 1) CarLabel(stringResource(R.string.nav_trip_hint), 15.sp, colors.text2, maxLines = 4)
        TripPlaces(places, editable = true, knob = false)
        // Its own colour: the card is already the colour of a plain pill
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(colors.surface2)
                .clickable { Navigation.end() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            CarLabel(stringResource(R.string.nav_stop), 16.sp, colors.text, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlaceList(places: List<Pair<Destination, Double?>>, onChoose: (Destination) -> Unit, header: (@Composable () -> Unit)? = null) {
    val colors = LocalCarColors.current
    val units = rememberUnits()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        header?.let { item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { it() } } }
        items(places) { (place, distance) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .carClickable(RoundedCornerShape(20.dp), place.name) { onChoose(place) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CarIcon(CarIcons.Map, colors.text2, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    CarLabel(place.name, 17.sp, colors.text, weight = FontWeight.Bold, maxLines = 2)
                    place.address?.let { CarLabel(it, 14.sp, colors.text2, maxLines = 2) }
                }
                distance?.let { CarLabel(distanceText(it, units), 14.sp, colors.text2, weight = FontWeight.Bold) }
            }
        }
    }
}

private const val SEARCH_DELAY_MS = 450L
