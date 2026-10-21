package com.simoneluconi.betterauto.transport.wireless

import aap_protobuf.aaw.WifiSecurityModeOuterClass.WifiSecurityMode
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import com.simoneluconi.betterauto.EventLog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Joins the head unit's (or dongle's) WiFi for this app only, without internet and without touching the
 * phone's own WiFi settings: Android asks the driver once per network, then remembers. [release] gives
 * the network back; [onLost] is called if it goes away during the session.
 */
class WifiJoin(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var onLost: (() -> Unit)? = null

    fun join(endpoint: WifiEndpoint, timeoutMs: Long): Network {
        // Dongles often hide their network: without asking for it by name the phone never finds it
        val specifier = WifiNetworkSpecifier.Builder().setSsid(endpoint.ssid).setIsHiddenSsid(true).apply {
            runCatching { MacAddress.fromString(endpoint.bssid) }.getOrNull()?.let(::setBssid)
            when (endpoint.security) {
                WifiSecurityMode.OPEN -> Unit
                WifiSecurityMode.WPA_PERSONAL, WifiSecurityMode.WPA2_PERSONAL, WifiSecurityMode.WPA_WPA2_PERSONAL,
                WifiSecurityMode.UNKNOWN_SECURITY_MODE -> setWpa2Passphrase(endpoint.password)
                else -> throw IOException("WiFi security ${endpoint.security} is not supported")
            }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val ready = CountDownLatch(1)
        var network: Network? = null
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                network = available
                ready.countDown()
            }

            override fun onUnavailable() = ready.countDown()

            override fun onLost(lost: Network) {
                if (lost == network) {
                    EventLog.log("Wireless: the head unit's WiFi was lost")
                    onLost?.invoke()
                }
            }
        }
        callback = newCallback
        connectivity.requestNetwork(request, newCallback, timeoutMs.toInt())
        if (!ready.await(timeoutMs + 1000, TimeUnit.MILLISECONDS) || network == null) {
            release()
            throw IOException("could not join the WiFi \"${endpoint.ssid}\"")
        }
        return network
    }

    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }
}
