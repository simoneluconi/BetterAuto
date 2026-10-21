package com.simoneluconi.betterauto.smarthome

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * A smart-home service (Home Assistant, and later others) as the rest of the app sees it. Everything
 * else (the device selection, the car screen, the arrival buttons, the widget card, the phone settings
 * section) works only on this interface and the canonical model ([SmartDevice], [DeviceKind],
 * [DeviceAction], [DeviceState], [SmartPlace]); nothing outside the provider's own package knows the
 * service.
 *
 * To add a service:
 * 1. a package `smarthome/<service>/` with a class implementing this interface: it maps the service's
 *    devices to [SmartDevice]s (keeping its own ids opaque in [SmartDevice.id]), its states to
 *    [DeviceState]s and the canonical [DeviceAction]s to its own commands;
 * 2. its linking card ([LinkCard]): how the driver connects it on the phone (never in the car);
 * 3. one line in [SmartHome.init] to register it;
 * 4. for an OAuth sign-in, its client id page on the project's GitHub Pages ([SmartHomeAuth]): the
 *    browser comes back to [SmartHomeAuth.redirectUri], routed here by id ([onSignInAnswer]).
 *
 * The provider keeps its credentials itself (encrypted, see [SecretStore]) and never logs them. It
 * talks to its service only between [follow] with devices and [follow] with none: [SmartHome] calls it
 * while a car session runs (or a smart-home screen is open), so the phone does no work otherwise.
 */
interface SmartHomeProvider {
    /** Stable id, used in saved settings ("homeassistant"). */
    val id: String

    /** Shown in the app bar and on the phone ("Home Assistant"). */
    val name: String

    /**
     * The service's own icon, e.g. its companion app's from the PackageManager: nothing is bundled.
     * Null draws our generic smart-home icon.
     */
    fun icon(context: Context): ImageBitmap?

    /** The service's own app is on the phone: the phone app then suggests linking it on its home page. */
    fun appInstalled(context: Context): Boolean = false

    /** Linked or not, and whether it is talking to its service now. */
    val link: StateFlow<LinkState>

    /** The home it is linked to, for the phone app ("Home"), when the service names it. */
    val homeName: StateFlow<String?>

    /** Areas, devices and places, last fetched with [refreshCatalog]; null until the first fetch. */
    val catalog: StateFlow<SmartCatalog?>

    /** Live states of the followed devices, by [SmartDevice.id]; the last known while not connected. */
    val states: StateFlow<Map<String, DeviceState>>

    /** Follows these devices live (connecting when needed); an empty set disconnects. */
    fun follow(deviceIds: Set<String>)

    /** Fetches the catalog again (phone app, after linking): true when it worked. */
    suspend fun refreshCatalog(): Boolean

    /** Asks the service for [action] on a device: true when the service accepted it. */
    suspend fun perform(deviceId: String, action: DeviceAction): Boolean

    /**
     * The browser came back from the service's sign-in (betterauto://auth/<id>?...), with the query
     * parameters: the provider checks them and finishes linking. For services that sign in elsewhere,
     * nothing to do.
     */
    fun onSignInAnswer(params: Map<String, String>) = Unit

    /** Forgets the credentials and the catalog. */
    fun unlink()

    /** The phone app's card to link this service (and to show the link once done). */
    @Composable
    fun LinkCard()
}

val SmartHomeProvider.linked: Boolean get() = link.value != LinkState.NOT_LINKED
