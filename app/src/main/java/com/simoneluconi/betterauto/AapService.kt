package com.simoneluconi.betterauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.simoneluconi.betterauto.aap.AapSession
import com.simoneluconi.betterauto.aap.AapTls
import com.simoneluconi.betterauto.aap.ConnectionProblem
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.audio.ShizukuAudio
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.exlap.ExlapSettings
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.transport.TcpTransport
import com.simoneluconi.betterauto.transport.Transport
import com.simoneluconi.betterauto.transport.UsbAccessoryTransport
import com.simoneluconi.betterauto.transport.wireless.WirelessConnector
import com.simoneluconi.betterauto.transport.wireless.WirelessState
import com.simoneluconi.betterauto.transport.wireless.WirelessStep
import com.simoneluconi.betterauto.voice.Speech
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Owns the head unit session and keeps it alive in background.
 * Every accessory attach starts a fresh session: a new attach means the previous USB link is gone.
 */
class AapService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: AapSession? = null

    /** How the current session reaches the car: only a USB one ends when an accessory detaches. */
    private enum class Link { USB, NETWORK }
    private var link: Link? = null

    /** A wireless connection on its way (Bluetooth, WiFi, TCP); a USB attach wins over it. */
    private var connecting: WirelessConnector? = null
    private var connectingTo: String? = null

    /** The links under the wireless session now running, to know how it ended. */
    private var wirelessLink: WirelessConnector? = null
    private var wirelessDevice: String? = null
    private var wirelessSince = 0L
    /** Tries left before giving up: a dongle often drops the first session while it sets up the car. */
    private var retriesLeft = MAX_RETRIES
    private var pendingRetry: Runnable? = null
    private var stoppedByUser = false
    /** The session that got through the setup (service discovery), to time out one that does not. */
    private var establishedSession: AapSession? = null

    // Every startForeground call replaces the types, so keep the media projection type once granted
    private var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    // Unplugging normally fails the USB read, but not on every phone: the detach broadcast is the sure way out
    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            EventLog.log("Accessory detached")
            if (link != Link.USB) return
            endSession(session ?: return)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Media sessions are needed by audio (auto-play, pause on disconnect) before the screen is up
        NowPlaying.acquire(this)
        CarAudio.init(this)
        // The car's vendor channel reads the Exlap switch and credentials from here
        ExlapSettings.get(this)
        ShizukuAudio.init()
        CarAudio.onProjectionChanged = { main.post { refreshNotification() } }
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        ContextCompat.registerReceiver(this, detached, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Closing stops the encoder and the projection: off the main thread. The session thread then reports the end. */
    private fun endSession(ended: AapSession) {
        thread(name = "aap-close") { ended.close() }
    }

    private fun refreshNotification() {
        if (session != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_AUDIO_CONSENT) {
            startAudioCapture(intent)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes)

        if (intent?.action == ACTION_CONNECT_TCP) {
            connectTcp(intent.getStringExtra(EXTRA_HOST), intent.getIntExtra(EXTRA_PORT, DEFAULT_TCP_PORT))
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONNECT_WIRELESS) {
            val address = intent.getStringExtra(EXTRA_DEVICE)
            if (intent.getBooleanExtra(EXTRA_AUTO, false) && (session != null || connecting != null || pendingRetry != null)) {
                // The car came into range while the phone is already with it (or with another, by cable)
                // Nothing to do, but a retry may be waiting: the service must stay for it
                if (session == null && connecting == null && pendingRetry == null) stopSelf()
                return START_NOT_STICKY
            }
            cancelRetry()
            retriesLeft = MAX_RETRIES
            stoppedByUser = false
            connectWireless(address)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_WIRELESS) {
            stoppedByUser = true
            cancelRetry()
            cancelWireless("by the user")
            if (link == Link.NETWORK) session?.let {
                EventLog.log("Wireless: disconnected by the user")
                endSession(it)
            }
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        // The cable is plugged in: it wins over a wireless connection still on its way
        cancelRetry()
        cancelWireless("the phone is connected by cable")

        val accessory = intent?.accessoryExtra()
        val fd = accessory?.let { getSystemService(UsbManager::class.java).openAccessory(it) }
        if (fd == null) {
            EventLog.log("Cannot open the USB accessory")
            if (accessory != null) showProblem(this, ConnectionProblem.USB_NOT_OPENED, null, null)
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        startSession(UsbAccessoryTransport(fd), Link.USB, "${accessory.manufacturer} ${accessory.model}")
        return START_NOT_STICKY
    }

    /** A head unit listening on the network: connect off the main thread, then the same session as over USB. */
    private fun connectTcp(host: String?, port: Int) {
        if (host.isNullOrBlank()) {
            if (session == null) stopSelf()
            return
        }
        EventLog.log("Connecting to $host:$port over TCP")
        thread(name = "aap-tcp-connect") {
            val transport = try {
                TcpTransport.connect(host, port, TCP_CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                EventLog.log("TCP connection to $host:$port failed: $e")
                main.post { if (session == null) stopSelf() }
                return@thread
            }
            main.post { startSession(transport, Link.NETWORK, "$host:$port") }
        }
    }

    /** Wireless Android Auto: Bluetooth, the head unit's WiFi, then TCP, off the main thread. */
    private fun connectWireless(address: String?) {
        if (address.isNullOrBlank()) {
            if (session == null) stopSelf()
            return
        }
        // A second tap while it is on its way must not start over: that would cancel the attempt
        if (connecting != null && connectingTo == address) return
        connecting?.cancel()
        val connector = WirelessConnector(this)
        connecting = connector
        connectingTo = address
        thread(name = "aap-wireless-connect") {
            val transport = try {
                connector.connect(address)
            } catch (e: IOException) {
                EventLog.log("Wireless: ${e.message}")
                main.post {
                    // Only the attempt still current speaks: a cancelled one has already been replaced
                    if (connecting !== connector) return@post
                    connecting = null
                    connectingTo = null
                    // Missing permissions or Bluetooth off will not change by waiting
                    val reason = e.message.orEmpty()
                    val hopeless = reason.startsWith("missing permissions") || reason == "Bluetooth is off" ||
                        reason == "this phone has no Bluetooth"
                    if (!hopeless && session == null && scheduleRetry(address)) return@post
                    WirelessConnector.report(WirelessState.Failed(connector.step, e.message.orEmpty()))
                    if (session == null) stopSelf()
                }
                return@thread
            }
            main.post {
                // Cancelled meanwhile (a cable was plugged in): drop what we got
                if (connecting !== connector) {
                    transport.close()
                    return@post
                }
                connecting = null
                connectingTo = null
                wirelessLink = connector
                wirelessDevice = address
                wirelessSince = System.nanoTime()
                WirelessConnector.report(WirelessState.Connected)
                startSession(transport, Link.NETWORK, getString(R.string.wireless_title))
            }
        }
    }

    /** Tries the same head unit again in a moment; false when the tries are used up. */
    private fun scheduleRetry(address: String): Boolean {
        if (retriesLeft <= 0 || stoppedByUser) return false
        retriesLeft--
        val attempt = MAX_RETRIES - retriesLeft + 1
        EventLog.log("Wireless: trying again in ${RETRY_DELAY_MS / 1000} s (attempt $attempt of ${MAX_RETRIES + 1})")
        WirelessConnector.report(WirelessState.Connecting(WirelessStep.BLUETOOTH))
        val retry = Runnable {
            pendingRetry = null
            connectWireless(address)
        }
        pendingRetry = retry
        main.postDelayed(retry, RETRY_DELAY_MS)
        return true
    }

    private fun cancelRetry() {
        pendingRetry?.let {
            main.removeCallbacks(it)
            EventLog.log("Wireless: retry cancelled")
            WirelessConnector.report(WirelessState.Idle)
        }
        pendingRetry = null
    }

    private fun cancelWireless(why: String) {
        val connector = connecting ?: return
        EventLog.log("Wireless: cancelled, $why")
        connector.cancel()
        connecting = null
        connectingTo = null
        WirelessConnector.report(WirelessState.Idle)
    }

    /** The one way a session starts, whatever carries it: USB accessory or TCP. */
    private fun startSession(transport: Transport, newLink: Link, label: String) {
        val tls = try {
            AapTls.fromAssets(this)
        } catch (e: Exception) {
            EventLog.log("Phone certificate: ${e.message ?: e}")
            showProblem(this, ConnectionProblem.PHONE_CERTIFICATE, null, null)
            runCatching { transport.close() }
            if (session == null) stopSelf()
            return
        }
        session?.let {
            EventLog.log("Replacing the previous session")
            endSession(it)
        }
        EventLog.status = label
        link = newLink
        val newSession = AapSession(
            transport, tls, Projection(this),
            onEstablished = {
                main.post {
                    establishedSession = session
                    cancelProblem(this)
                }
            },
            onProblem = { problem, code ->
                val carMaker = tls.peerOrganization
                main.post { showProblem(this, problem, code, carMaker) }
            },
        ) {
            AppIcon.Sizes.mapNotNull { AppIcon.png(this, it) }.takeIf { it.size == AppIcon.Sizes.size }.orEmpty()
        }
        session = newSession
        // A dongle can take the session and never bring up its side to the car: give up and try again
        if (newLink == Link.NETWORK) {
            main.postDelayed({
                if (session === newSession && establishedSession !== newSession) {
                    EventLog.log("Wireless: the head unit did not finish the setup in ${SETUP_TIMEOUT_MS / 1000} s")
                    endSession(newSession)
                }
            }, SETUP_TIMEOUT_MS)
        }
        thread(name = "aap-session") {
            try {
                newSession.run()
            } catch (e: Exception) {
                EventLog.log("Session ended: $e")
            } finally {
                // Like Android Auto: pause on disconnect, before the phone volume comes back
                CarAudio.onDisconnected()
                NowPlaying.pause()
                newSession.close()
                main.post { onSessionEnded(newSession) }
            }
        }
    }

    /** Turns the user's capture consent into a MediaProjection that feeds the car's media channel. */
    private fun startAudioCapture(intent: Intent) {
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            if (session == null) stopSelf()
            return
        }
        if (session == null) {
            // The car went away while the consent dialog was up: a projection with nothing to feed
            // would only keep the capture indicator on and this service alive
            EventLog.log("Audio capture consent arrived after the car disconnected, ignored")
            stopSelf()
            return
        }
        // The service must already run as a media projection service when the projection is created
        foregroundTypes = foregroundTypes or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes)
        val projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data) ?: return
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                EventLog.log("Audio capture consent ended")
                CarAudio.setProjection(null)
            }
        }, main)
        EventLog.log("Audio capture consent granted")
        CarAudio.setProjection(projection)
    }

    private fun onSessionEnded(ended: AapSession) {
        // A newer session may already have replaced this one
        if (session !== ended) return
        session = null
        if (link == Link.NETWORK && WirelessConnector.status.value == WirelessState.Connected) {
            WirelessConnector.report(WirelessState.Idle)
        }
        val endedLink = link
        link = null
        EventLog.status = null
        CarState.reset()
        VehicleData.onDisconnected()
        Speech.release()
        if (endedLink == Link.NETWORK && retryAfterWirelessEnd()) return
        stopSelf()
    }

    /**
     * A wireless session that the head unit closed while its WiFi is still there (a dongle restarting its
     * link to the car) is tried again. Not when the WiFi went away (the car was switched off) or the user
     * stopped it. A session that ran for a while gets the full set of tries again.
     */
    private fun retryAfterWirelessEnd(): Boolean {
        val connector = wirelessLink ?: return false
        val address = wirelessDevice ?: return false
        wirelessLink = null
        wirelessDevice = null
        if (connector.wifiLost || stoppedByUser) return false
        if ((System.nanoTime() - wirelessSince) / 1_000_000 >= STABLE_SESSION_MS) retriesLeft = MAX_RETRIES
        return scheduleRetry(address)
    }

    override fun onDestroy() {
        cancelRetry()
        cancelWireless("the service stopped")
        if (WirelessConnector.status.value == WirelessState.Connected) WirelessConnector.report(WirelessState.Idle)
        unregisterReceiver(detached)
        CarAudio.onProjectionChanged = null
        CarAudio.setProjection(null)
        NowPlaying.release()
        session?.let { endSession(it) }
        session = null
        EventLog.status = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIFICATION_COLOR)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        // Moved through Shizuku or copied with the capture consent: either way the car has the sound
        if (CarAudio.hasProjection || CarAudio.movesAudio) {
            builder.setContentText(getString(R.string.notification_text))
        } else {
            // Without capture consent (declined, or not pre-approved via ADB) offer a way to grant it later
            val consent = PendingIntent.getActivity(
                this, 1, Intent(this, AudioConsentActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentText(getString(R.string.notification_audio_phone))
                .addAction(Notification.Action.Builder(null, getString(R.string.action_enable_car_audio), consent).build())
        }
        return builder.build()
    }

    companion object {
        const val ACTION_AUDIO_CONSENT = "com.simoneluconi.betterauto.AUDIO_CONSENT"

        /** Connect to a head unit over TCP ([EXTRA_HOST], [EXTRA_PORT]); used by the debug tools for now. */
        const val ACTION_CONNECT_TCP = "com.simoneluconi.betterauto.CONNECT_TCP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        /** Wireless Android Auto to the paired head unit or dongle with this Bluetooth address ([EXTRA_DEVICE]). */
        const val ACTION_CONNECT_WIRELESS = "com.simoneluconi.betterauto.CONNECT_WIRELESS"
        const val EXTRA_DEVICE = "device"
        /** Started by auto-connect (the car came into range): ignored if the phone is already connected. */
        const val EXTRA_AUTO = "auto"
        /** Stops a wireless connection on its way, or ends the wireless session. */
        const val ACTION_CANCEL_WIRELESS = "com.simoneluconi.betterauto.CANCEL_WIRELESS"
        /** Tries after the first, and the pause between them. */
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 3_000L
        /** A session that lasted this long was not a setup hiccup: its end gets fresh tries. */
        private const val STABLE_SESSION_MS = 60_000L
        /** From TCP to the head unit's service list: a few seconds normally (USB set up to the car included). */
        private const val SETUP_TIMEOUT_MS = 20_000L

        /** The port head units in wireless mode listen on. */
        const val DEFAULT_TCP_PORT = 5277
        private const val TCP_CONNECT_TIMEOUT_MS = 10_000
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "aap"
        private const val NOTIFICATION_ID = 1
        private const val PROBLEM_CHANNEL_ID = "problems"
        private const val PROBLEM_NOTIFICATION_ID = 2
        /** The blue of the app icon: the notification's car is drawn on it, as in the launcher. */
        private const val NOTIFICATION_COLOR = 0xFF0B63CE.toInt()

        /**
         * Tells the driver, in plain words, why the car did not connect. It outlives the session notification,
         * which goes away with the connection, and a newer problem replaces it.
         */
        fun showProblem(context: Context, problem: ConnectionProblem, code: Int?, carMaker: String?) {
            val (title, text) = when (problem) {
                ConnectionProblem.NO_COMPATIBLE_VERSION -> R.string.problem_version_title to R.string.problem_version_text
                ConnectionProblem.CERTIFICATE_REJECTED -> R.string.problem_certificate_title to R.string.problem_certificate_text
                ConnectionProblem.CERTIFICATE_DATES -> R.string.problem_dates_title to R.string.problem_dates_text
                ConnectionProblem.AUTH_FAILED -> R.string.problem_auth_title to R.string.problem_auth_text
                ConnectionProblem.NOT_SUPPORTED -> R.string.problem_not_supported_title to R.string.problem_not_supported_text
                ConnectionProblem.DEVICE_SWITCH -> R.string.problem_device_switch_title to R.string.problem_device_switch_text
                ConnectionProblem.DROPPED_DURING_SETUP -> R.string.problem_dropped_title to R.string.problem_dropped_text
                ConnectionProblem.USB_NOT_OPENED -> R.string.problem_usb_title to R.string.problem_usb_text
                ConnectionProblem.PHONE_CERTIFICATE -> R.string.problem_phone_certificate_title to R.string.problem_phone_certificate_text
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(PROBLEM_CHANNEL_ID, context.getString(R.string.problem_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val body = context.getString(text, code ?: 0)
            val builder = Notification.Builder(context, PROBLEM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(NOTIFICATION_COLOR)
                .setContentTitle(context.getString(title))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                )
            carMaker?.let(builder::setSubText)
            manager.notify(PROBLEM_NOTIFICATION_ID, builder.build())
        }

        /** The car connected after all: an old explanation would only confuse. */
        fun cancelProblem(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(PROBLEM_NOTIFICATION_ID)
        }
    }
}

internal fun Intent.accessoryExtra(): UsbAccessory? =
    IntentCompat.getParcelableExtra(this, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
