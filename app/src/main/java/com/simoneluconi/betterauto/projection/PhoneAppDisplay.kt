package com.simoneluconi.betterauto.projection

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.IAppDisplayListener
import com.simoneluconi.betterauto.audio.ShizukuAudio
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phone apps in the car: they run on a display of the Shizuku shell service ([AppDisplayHost]) whose picture the
 * launcher shows inside its own screen (`ui/apps/PhoneAppScreen`), so the bar, calls and notifications stay around
 * it. The display is made the first time an app is shown and kept for the session: leaving the screen puts it to
 * sleep (its app pauses), coming back wakes it where it was. Closing the app removes it, made again for the next.
 *
 * Every call to the shell service goes through one worker thread, in order; keys and touches are one-way calls.
 */
object PhoneAppDisplay {
    /** Why an app can't be shown. */
    enum class Problem { NO_SHIZUKU, FAILED }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "phone-apps") }

    // Worker thread only
    private var connection: ShizukuAudio.Connection? = null
    private var death: Pair<IBinder, IBinder.DeathRecipient>? = null
    private var displayId = -1
    private var pending: String? = null
    private var lastShape: Triple<Int, Int, Int>? = null

    /** Written on the worker, read by the listener on binder threads. */
    @Volatile
    private var launchedAt = 0L

    private val _open = MutableStateFlow<String?>(null)
    /** The app on the display (the one opened, or another opened from it), null when none. */
    val open: StateFlow<String?> = _open

    private val _secure = MutableStateFlow(false)
    /** The app shows a window it forbids on other screens: black there, a message instead. */
    val secure: StateFlow<Boolean> = _secure

    private val _problem = MutableStateFlow<Problem?>(null)
    val problem: StateFlow<Problem?> = _problem

    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The app was left with Back past its first screen: the launcher goes back to the apps. */
    val closed: SharedFlow<Unit> = _closed

    /** The knob is on the app's picture: its keys go to the app, or move the pointer (main thread). */
    @Volatile
    var hasKnob = false

    /** Phone apps can be shown: Android 14 or later (virtual devices) and Shizuku ready. */
    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ShizukuAudio.state(context) == ShizukuAudio.State.READY

    /** Shows [packageName]: started on the display now, or once the screen gives it a surface. */
    fun open(packageName: String) {
        // The same app again keeps what is known of it: the host tells only changes, a protected window still up
        // would come back as a black picture without its message
        if (packageName != _open.value) _secure.value = false
        _open.value = packageName
        // Tried again: the screen shows the picture, which attaches anew
        _problem.value = null
        worker.execute {
            pending = packageName
            if (displayId >= 0) launchPending()
        }
    }

    /** The screen's picture is ready: the display draws there, made the first time ([dpi]: the apps' density). */
    fun attach(context: Context, surface: Surface, width: Int, height: Int, dpi: Int) = worker.execute {
        // Closed meanwhile: no display for nothing
        if (displayId < 0 && _open.value == null) return@execute
        // Shizuku going away between its own calls throws: an exception here would end the app, and the session
        val router = runCatching { connect(context.applicationContext) }
            .onFailure { EventLog.log("Phone apps: no Shizuku service: $it") }
            .getOrNull()
        if (router == null) {
            _problem.value = Problem.NO_SHIZUKU
            return@execute
        }
        try {
            if (displayId < 0) {
                displayId = router.createAppDisplay(width, height, dpi, surface, listener)
                EventLog.log("Phone apps: ${router.status()}")
                if (displayId < 0) {
                    _problem.value = Problem.FAILED
                    return@execute
                }
            } else {
                // Awake first, then drawn here: the frames of its waking came partly transparent, and the last one
                // stayed on screen as a gray veil until the app drew again
                router.setAppDisplayOn(true)
                router.updateAppDisplay(width, height, dpi, surface)
                // A new size or density (another car screen, the size setting) is worth a line
                if (Triple(width, height, dpi) != lastShape) EventLog.log("Phone apps: ${router.status()}")
            }
            lastShape = Triple(width, height, dpi)
            _problem.value = null
            launchPending()
        } catch (e: Exception) {
            EventLog.log("Phone apps: display not shown: $e")
            _problem.value = Problem.FAILED
        }
    }

    /**
     * The picture is gone (another section) or stopped (the car shows its own screen: the launcher's lifecycle
     * below STARTED, which the screen watches): the display sleeps and draws nowhere.
     */
    fun detach() = worker.execute {
        val router = connection?.router ?: return@execute
        if (displayId < 0) return@execute
        runCatching {
            router.updateAppDisplay(0, 0, 0, null)
            router.setAppDisplayOn(false)
        }
    }

    fun key(keyCode: Int, down: Boolean, shift: Boolean = false) = worker.execute {
        if (displayId >= 0) runCatching { connection?.router?.appDisplayKey(keyCode, down, shift) }
    }

    /** A finger (MotionEvent.ACTION_DOWN, _MOVE or _UP) in the display's pixels. */
    fun touch(pointerId: Int, action: Int, x: Float, y: Float) = worker.execute {
        if (displayId >= 0) runCatching { connection?.router?.appDisplayTouch(pointerId, action, x, y) }
    }

    /** Pointer mode's press: a short tap at [x], [y] (the display's pixels). */
    fun tap(x: Float, y: Float) = worker.execute {
        val router = connection?.router ?: return@execute
        if (displayId < 0) return@execute
        runCatching {
            router.appDisplayTouch(CURSOR_POINTER, MotionEvent.ACTION_DOWN, x, y)
            // A finger that lifts the instant it lands is not taken as a tap everywhere
            Thread.sleep(TAP_MS)
            router.appDisplayTouch(CURSOR_POINTER, MotionEvent.ACTION_UP, x, y)
        }
    }

    // Knob detents waiting to scroll, gathered while a swipe is under way (the knob turns faster than a swipe takes)
    private val scrollSteps = AtomicInteger(0)
    private val scrollQueued = AtomicBoolean(false)
    @Volatile
    private var scrollAt = floatArrayOf(0f, 0f, 0f, 0f)

    /**
     * Pointer mode's turn of the knob: [steps] detents (positive: forward, the app shows what is further down) at
     * [x], [y] on the [width] x [height] display, a short finger swipe there ([PointerMotion.swipe]).
     */
    fun scroll(x: Float, y: Float, width: Int, height: Int, steps: Int) {
        if (steps == 0) return
        scrollAt = floatArrayOf(x, y, width.toFloat(), height.toFloat())
        scrollSteps.addAndGet(steps)
        queueScroll()
    }

    private fun queueScroll() {
        if (scrollQueued.compareAndSet(false, true)) worker.execute(::runScroll)
    }

    private fun runScroll() {
        // Cleared first: a detent arriving during this swipe queues the next one
        scrollQueued.set(false)
        val wanted = scrollSteps.getAndSet(0)
        if (wanted == 0) return
        val steps = wanted.coerceIn(-PointerMotion.MAX_SCROLL_STEPS, PointerMotion.MAX_SCROLL_STEPS)
        scrollSteps.addAndGet(wanted - steps)
        val router = connection?.router
        if (router != null && displayId >= 0) {
            val (x, y, width, height) = scrollAt
            var at = 0L
            runCatching {
                for (touch in PointerMotion.swipe(x, y, width.toInt(), height.toInt(), steps)) {
                    if (touch.atMs > at) Thread.sleep(touch.atMs - at)
                    at = touch.atMs
                    val action = when (touch.phase) {
                        SwipeTouch.Phase.DOWN -> MotionEvent.ACTION_DOWN
                        SwipeTouch.Phase.MOVE -> MotionEvent.ACTION_MOVE
                        SwipeTouch.Phase.UP -> MotionEvent.ACTION_UP
                    }
                    router.appDisplayTouch(CURSOR_POINTER, action, touch.x, touch.y)
                }
            }
        } else {
            scrollSteps.set(0)
        }
        if (scrollSteps.get() != 0) queueScroll()
    }

    /**
     * The driver closes the app: the display goes, and the app's activities with it (a private display's content
     * is destroyed on removal); the Shizuku service stays, and the next app opened makes a new display.
     */
    fun closeApp() {
        val app = _open.value
        _open.value = null
        _secure.value = false
        _problem.value = null
        worker.execute {
            pending = null
            scrollSteps.set(0)
            if (displayId < 0) return@execute
            runCatching { connection?.router?.releaseAppDisplay() }
            displayId = -1
            lastShape = null
            EventLog.log("Phone apps: $app closed by the driver")
        }
    }

    /** The session ended: the display and its apps go, and the shell service is let go. */
    fun release() {
        _open.value = null
        _secure.value = false
        worker.execute {
            runCatching { connection?.router?.releaseAppDisplay() }
            forget()
            pending = null
            scrollSteps.set(0)
        }
    }

    private fun launchPending() {
        val packageName = pending ?: return
        pending = null
        val router = connection?.router ?: return
        launchedAt = SystemClock.elapsedRealtime()
        val problem = runCatching { router.launchOnAppDisplay(packageName) }.getOrElse { it.toString() }
        EventLog.log("Phone apps: $packageName ${problem ?: "shown"}")
        // A black picture holding the knob tells the driver nothing: the message, with the way back to the apps
        if (problem != null) _problem.value = Problem.FAILED
    }

    /** Lets the shell service go (closed, its death no longer watched); the display goes with its process. */
    private fun forget() {
        death?.let { (binder, recipient) -> runCatching { binder.unlinkToDeath(recipient, 0) } }
        death = null
        connection?.close()
        connection = null
        displayId = -1
        lastShape = null
    }

    /** The shell service, held while the display lives; its process gone, the display goes with it. */
    private fun connect(context: Context): com.simoneluconi.betterauto.audio.IAudioRouter? {
        connection?.let { if (it.router.asBinder().isBinderAlive) return it.router }
        // A dead one still holds its place in the shared binding: closed
        forget()
        val made = ShizukuAudio.connect(context, ROUTE) ?: return null
        connection = made
        val binder = made.router.asBinder()
        val recipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                EventLog.log("Phone apps: Shizuku service gone")
                worker.execute {
                    if (connection?.router?.asBinder() !== binder) return@execute
                    forget()
                    // Shown again, once there is a service, where it was
                    pending = _open.value
                    _problem.value = Problem.NO_SHIZUKU
                }
            }
        }
        runCatching {
            binder.linkToDeath(recipient, 0)
            death = binder to recipient
        }
        return made.router
    }

    /** An empty display reported right after a launch: closed after all if no app comes to the front meanwhile. */
    @Volatile
    private var emptySince = 0L

    private val main = Handler(Looper.getMainLooper())

    private val listener = object : IAppDisplayListener.Stub() {
        override fun onTopApp(packageName: String?) {
            emptySince = 0L
            if (packageName != null) _open.value = packageName
        }

        override fun onEmpty() {
            // A launch replaces what was there: an empty display right after it is not the driver leaving, unless it
            // stays empty (Back pressed on an app opened by mistake): looked at again once the launch has settled
            val sinceLaunch = SystemClock.elapsedRealtime() - launchedAt
            if (sinceLaunch < LAUNCH_SETTLE_MS) {
                val at = SystemClock.elapsedRealtime()
                emptySince = at
                main.postDelayed({ if (emptySince == at) appClosed() }, LAUNCH_SETTLE_MS - sinceLaunch)
                return
            }
            appClosed()
        }

        private fun appClosed() {
            emptySince = 0L
            if (_open.value == null) return
            EventLog.log("Phone apps: the app was closed")
            _open.value = null
            _secure.value = false
            _closed.tryEmit(Unit)
        }

        override fun onSecureWindow(shown: Boolean) {
            _secure.value = shown
        }
    }

    /** A route id of its own on the shared Shizuku binding: it stays up while the display does. */
    private const val ROUTE = 50
    private const val LAUNCH_SETTLE_MS = 1500L

    /** The pointer's finger: one at a time, as the driver's own on the picture. */
    private const val CURSOR_POINTER = 0
    private const val TAP_MS = 50L
}
