package com.simoneluconi.betterauto.projection

import android.companion.CompanionDeviceManager
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.RequiresApi
import com.simoneluconi.betterauto.audio.IAppDisplayListener
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Runs in the shell process ([com.simoneluconi.betterauto.audio.AudioRouterService]): the display where phone apps
 * run for the car. It belongs to a virtual device of ours (VirtualDeviceManager, system APIs by reflection), and a
 * device's displays have a display group of their own, so their own power: they keep running with the phone locked
 * or asleep (a plain virtual display sleeps with the phone), and LOCK_STATE_ALWAYS_UNLOCKED keeps the keyguard off
 * them. Arrows and touches reach the app through the device's own d-pad and touchscreen, other keys are injected on
 * the display (no virtual keyboard: the phone's on-screen one shows there, see [key]).
 *
 * A device needs a companion association of the caller's package with an app-streaming profile: the shell user
 * makes one for itself the first time (`cmd companiondevice associate`), and the device is asked for under the
 * shell package's name, which is how the system looks the association up.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AppDisplayHost(context: Context) {
    private val shell: Context = shellIdentity(context)
    private var device: Any? = null
    private var display: VirtualDisplay? = null
    private var dpad: Any? = null
    private var touchscreen: Any? = null
    private var listener: IAppDisplayListener? = null
    private var width = 0
    private var height = 0
    private var dpi = 0

    /** What happened last, for the log. */
    @Volatile
    var status = "no app display"
        private set

    /** Makes the device and its display, drawing into [surface]; the display id, or -1. */
    @Synchronized
    fun create(width: Int, height: Int, densityDpi: Int, surface: Surface, listener: IAppDisplayListener?): Int {
        release()
        return try {
            val newDevice = createDevice(association())
            device = newDevice
            this.listener = listener
            watchActivities(newDevice)
            // Trusted: apps may start there and the display joins the device's own group. Public: the phone's keyboard
            // (another app) may put its window there, a private display refuses it ("does not have access"); its own
            // content only, so nothing is mirrored onto it
            val flags = displayFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1 shl 10) or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val makeDisplay = newDevice.javaClass
                .getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java, Executor::class.java, VirtualDisplay.Callback::class.java)
            fun displayWith(flags: Int): VirtualDisplay? = makeDisplay.invoke(
                newDevice, VirtualDisplayConfig.Builder(DISPLAY_NAME, width, height, densityDpi).setSurface(surface).setFlags(flags).build(), null, null,
            ) as VirtualDisplay?
            var public = true
            val newDisplay = runCatching { displayWith(flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) }.getOrNull()
                ?: displayWith(flags).also { public = false }
                ?: error("the device gave no display")
            display = newDisplay
            this.width = width
            this.height = height
            dpi = densityDpi
            val id = newDisplay.display.displayId
            dpad = inputDevice(newDevice, "VirtualDpadConfig", "createVirtualDpad", id, "BetterAuto knob")
            touchscreen = touchscreen(newDevice, id, width, height)
            val ime = keyboardHere(newDevice, id)
            status = "app display $id: ${width}x$height at $densityDpi dpi, ${if (public) "public" else "private"}, keyboard $ime"
            id
        } catch (e: Throwable) {
            release()
            status = "app display not made: ${e.cause ?: e}"
            -1
        }
    }

    /**
     * Draws into [surface] (none: nowhere) at [newWidth] x [newHeight] (0: as it is). The touchscreen is made again
     * for a new size: its coordinates are its own size's.
     */
    @Synchronized
    fun update(newWidth: Int, newHeight: Int, densityDpi: Int, surface: Surface?) {
        val current = display ?: return
        runCatching {
            if (newWidth > 0 && newHeight > 0 && (newWidth != width || newHeight != height || densityDpi != dpi)) {
                current.resize(newWidth, newHeight, densityDpi)
                dpi = densityDpi
                val resized = newWidth != width || newHeight != height
                width = newWidth
                height = newHeight
                val owner = device
                if (owner != null && resized) {
                    touchscreen?.let { close(it) }
                    touchscreen = touchscreen(owner, current.display.displayId, newWidth, newHeight)
                }
            }
            current.surface = surface
            status = "app display ${current.display.displayId}: ${width}x$height at $dpi dpi, ${if (surface == null) "not drawn" else "drawn"}"
        }.onFailure { status = "app display not updated: ${it.cause ?: it}" }
    }

    /** Starts [packageName]'s launcher activity there; null when done, otherwise why not. */
    @Synchronized
    fun launch(packageName: String): String? {
        val id = display?.display?.displayId ?: return "no app display"
        val component = shell.packageManager.getLaunchIntentForPackage(packageName)?.component
            ?: return "$packageName has no launcher activity"
        val (exit, output) = run("am", "start", "--display", id.toString(), "-n", component.flattenToShortString())
        status = "launch ${component.flattenToShortString()} on $id: exit $exit"
        return if (exit == 0 && !output.contains("Error")) null else output.ifEmpty { "exit $exit" }
    }

    /**
     * Arrows, center and back go through the d-pad (all it takes); any other key (Tab, a knob turned back as
     * Shift+Tab) is injected on the display. Not through a virtual keyboard: with one attached the phone takes it for
     * a physical keyboard and keeps its own on-screen one hidden, and the app could not be typed in.
     */
    @Synchronized
    fun key(keyCode: Int, down: Boolean, shift: Boolean = false) {
        if (keyCode in DPAD_KEYS) sendKey(dpad, keyCode, down) else injectKey(keyCode, down, shift)
    }

    private fun injectKey(keyCode: Int, down: Boolean, shift: Boolean) {
        val id = display?.display?.displayId ?: return
        runCatching {
            val now = SystemClock.uptimeMillis()
            val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            val event = KeyEvent(
                now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
            )
            injection.getOrThrow().invoke(event, id)
        }.onFailure { status = "key $keyCode not injected: ${it.cause ?: it}" }
    }

    /** Puts a key event on a display, through the system's input manager (the shell may inject). Looked up once. */
    private val injection: Result<(KeyEvent, Int) -> Unit> by lazy {
        runCatching {
            val setDisplay = KeyEvent::class.java.getMethod("setDisplayId", INT)
            val global = Class.forName("android.hardware.input.InputManagerGlobal")
            val manager = global.getMethod("getInstance").invoke(null)!!
            val injectMethod = global.getMethod("injectInputEvent", android.view.InputEvent::class.java, INT)
            val send: (KeyEvent, Int) -> Unit = { event, displayId ->
                setDisplay.invoke(event, displayId)
                injectMethod.invoke(manager, event, INJECT_ASYNC)
            }
            send
        }
    }

    private fun sendKey(target: Any?, keyCode: Int, down: Boolean) {
        target ?: return
        runCatching {
            val type = keyEvents.getOrThrow()
            val event = type.build(
                "setKeyCode" to keyCode,
                "setAction" to if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            )
            sender(target, "sendKeyEvent", type.eventClass).invoke(target, event)
        }.onFailure { status = "key $keyCode not sent: ${it.cause ?: it}" }
    }

    @Synchronized
    fun touch(pointerId: Int, action: Int, x: Float, y: Float) {
        val target = touchscreen ?: return
        runCatching {
            val type = touchEvents.getOrThrow()
            val event = type.build(
                "setPointerId" to pointerId,
                "setToolType" to MotionEvent.TOOL_TYPE_FINGER,
                "setAction" to action,
                "setX" to x.coerceIn(0f, (width - 1).toFloat()),
                "setY" to y.coerceIn(0f, (height - 1).toFloat()),
                "setPressure" to if (action == MotionEvent.ACTION_UP) 0f else 1f,
                "setMajorAxisSize" to 1f,
            )
            sender(target, "sendTouchEvent", type.eventClass).invoke(target, event)
        }.onFailure { status = "touch not sent: ${it.cause ?: it}" }
    }

    // Every key and touch looked these up again (Class.forName, getMethod): now once, the first time they are needed.
    // A failure is kept and reported by each event, never thrown in the shell process
    private val keyEvents by lazy { runCatching { EventType("VirtualKeyEvent", "setKeyCode" to INT, "setAction" to INT) } }
    private val touchEvents by lazy {
        runCatching {
            EventType(
                "VirtualTouchEvent", "setPointerId" to INT, "setToolType" to INT, "setAction" to INT, "setX" to FLOAT,
                "setY" to FLOAT, "setPressure" to FLOAT, "setMajorAxisSize" to FLOAT,
            )
        }
    }

    /** An input device's send method, per its class (the d-pad's, the touchscreen's). */
    private val senders = HashMap<Class<*>, Method>()

    private fun sender(target: Any, name: String, eventClass: Class<*>): Method =
        senders.getOrPut(target.javaClass) { target.javaClass.getMethod(name, eventClass) }

    /** A system input event (android.hardware.input.[name]) and its builder's setters, looked up once. */
    private class EventType(name: String, vararg setters: Pair<String, Class<*>>) {
        val eventClass: Class<*> = Class.forName("android.hardware.input.$name")
        private val builderClass = Class.forName("android.hardware.input.$name\$Builder")
        private val constructor: Constructor<*> = builderClass.getConstructor()
        private val methods: Map<String, Method> = setters.associate { (setter, type) -> setter to builderClass.getMethod(setter, type) }
        private val build: Method = builderClass.getMethod("build")

        fun build(vararg values: Pair<String, Any>): Any {
            val builder = constructor.newInstance()
            for ((setter, value) in values) methods.getValue(setter).invoke(builder, value)
            return build.invoke(builder) ?: error("$eventClass not built")
        }
    }

    /** The device's own power, apart from the phone's screen. */
    @Synchronized
    fun setOn(on: Boolean) {
        val current = device ?: return
        status = runCatching {
            current.javaClass.getMethod(if (on) "wakeUp" else "goToSleep").invoke(current)
            "app display ${if (on) "on" else "asleep"}"
        }.getOrElse { "app display not ${if (on) "woken" else "put to sleep"}: ${it.cause ?: it}" }
    }

    @Synchronized
    fun release() {
        // First: the display emptied by its removal is not the driver closing the app
        listener = null
        for (input in listOf(dpad, touchscreen)) input?.let { close(it) }
        display?.let { runCatching { it.release() } }
        device?.let { close(it) }
        dpad = null
        touchscreen = null
        display = null
        device = null
    }

    private fun close(closeable: Any) {
        runCatching { closeable.javaClass.getMethod("close").invoke(closeable) }
    }

    private fun createDevice(association: Int): Any {
        val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setName", String::class.java).invoke(builder, DEVICE_NAME)
        builderClass.getMethod("setLockState", INT).invoke(builder, LOCK_STATE_ALWAYS_UNLOCKED)
        val params = builderClass.getMethod("build").invoke(builder)!!
        val manager = manager(shell, "android.companion.virtual.VirtualDeviceManager", "virtualdevice", "android.companion.virtual.IVirtualDeviceManager")
        return manager.javaClass.getMethod("createVirtualDevice", INT, params.javaClass).invoke(manager, association, params)
            ?: error("no virtual device")
    }

    /**
     * Tells the app what happens on the display, through the device's activity listener (an interface of system
     * APIs, answered by a proxy): the app in front, the display left empty, protected windows.
     */
    private fun watchActivities(device: Any) {
        val type = Class.forName("android.companion.virtual.VirtualDeviceManager\$ActivityListener")
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "onTopActivityChanged" -> (args?.getOrNull(1) as? ComponentName)?.let { tell { onTopApp(it.packageName) } }
                "onDisplayEmpty" -> tell { onEmpty() }
                "onSecureWindowShown" -> tell { onSecureWindow(true) }
                "onSecureWindowHidden" -> tell { onSecureWindow(false) }
                "hashCode" -> return@newProxyInstance System.identityHashCode(self)
                "equals" -> return@newProxyInstance self === args?.getOrNull(0)
                "toString" -> return@newProxyInstance "AppDisplayHost listener"
            }
            null
        }
        runCatching {
            device.javaClass.getMethod("addActivityListener", Executor::class.java, type).invoke(device, Executor { it.run() }, proxy)
        }.onFailure { status = "no activity listener: ${it.cause ?: it}" }
    }

    private inline fun tell(event: IAppDisplayListener.() -> Unit) {
        listener?.let { runCatching { it.event() } }
    }

    /** The shell's app-streaming association, made the first time. */
    private fun association(): Int {
        appStreaming()?.let { return it }
        val (exit, output) = run(
            "cmd", "companiondevice", "associate", (Process.myUid() / PER_USER_RANGE).toString(),
            SHELL_PACKAGE, ASSOCIATION_ADDRESS, APP_STREAMING,
        )
        return appStreaming() ?: error("no association made (exit $exit${if (output.isNotEmpty()) ", $output" else ""})")
    }

    private fun appStreaming(): Int? =
        (manager(shell, CompanionDeviceManager::class.java.name, "companiondevice", "android.companion.ICompanionDeviceManager") as CompanionDeviceManager)
            .myAssociations
            .firstOrNull { it.deviceProfile == APP_STREAMING && !it.isSelfManaged }
            ?.id

    private fun touchscreen(device: Any, displayId: Int, width: Int, height: Int): Any =
        inputDevice(device, "VirtualTouchscreenConfig", "createVirtualTouchscreen", displayId, "BetterAuto touch", width, height)

    /** One of the device's input devices, tied to display [displayId]. [size]: a touchscreen's width and height. */
    private fun inputDevice(device: Any, config: String, create: String, displayId: Int, name: String, vararg size: Int): Any {
        val builderClass = Class.forName("android.hardware.input.$config\$Builder")
        val builder = if (size.isEmpty()) {
            builderClass.getConstructor().newInstance()
        } else {
            builderClass.getConstructor(INT, INT).newInstance(size[0], size[1])
        }
        // Declared on the generic base builder
        builderClass.getMethod("setAssociatedDisplayId", INT).apply { isAccessible = true }.invoke(builder, displayId)
        builderClass.getMethod("setInputDeviceName", String::class.java).apply { isAccessible = true }.invoke(builder, name)
        val built = builderClass.getMethod("build").invoke(builder)!!
        return device.javaClass.getMethod(create, built.javaClass).invoke(device, built) ?: error("no $config device")
    }

    /**
     * The phone's on-screen keyboard shows on this display, inside the app's picture in the car, instead of on the
     * phone (where it would be no use, or not shown at all with the phone locked). Through the device where the
     * system has it, else the window manager; says which, or why neither.
     */
    private fun keyboardHere(device: Any, displayId: Int): String {
        val viaDevice = runCatching {
            device.javaClass.getMethod("setDisplayImePolicy", INT, INT).invoke(device, displayId, DISPLAY_IME_POLICY_LOCAL)
        }
        if (viaDevice.isSuccess) return "here (device)"
        val viaWindows = runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "window") as IBinder? ?: error("no window service")
            val windows = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            windows.javaClass.getMethod("setDisplayImePolicy", INT, INT).invoke(windows, displayId, DISPLAY_IME_POLICY_LOCAL)
        }
        if (viaWindows.isSuccess) return "here (window manager)"
        val why = viaWindows.exceptionOrNull()?.let { it.cause ?: it }
        return "on the phone ($why)"
    }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to output
    }

    private companion object {
        val INT: Class<*> = Int::class.javaPrimitiveType!!
        val FLOAT: Class<*> = Float::class.javaPrimitiveType!!

        const val SHELL_PACKAGE = "com.android.shell"
        const val APP_STREAMING = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
        /** Locally administered and made up: the association needs an address, no device has this one. */
        const val ASSOCIATION_ADDRESS = "02:42:41:55:54:4f"
        const val PER_USER_RANGE = 100000
        const val DEVICE_NAME = "BetterAuto"
        const val DISPLAY_NAME = "BetterAuto apps"

        /** VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED (system API). */
        const val LOCK_STATE_ALWAYS_UNLOCKED = 1

        /** WindowManager.DISPLAY_IME_POLICY_LOCAL (hidden): the keyboard shows on the display that asked for it. */
        const val DISPLAY_IME_POLICY_LOCAL = 0

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC (hidden): don't wait for the app to take the key. */
        const val INJECT_ASYNC = 0

        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
        )

        /** A hidden DisplayManager flag, read where the system has it. */
        fun displayFlag(name: String, fallback: Int): Int =
            runCatching { DisplayManager::class.java.getField(name).getInt(null) }.getOrDefault(fallback)

        /**
         * The context the system must see: the shell user under the shell package's name, which owns the association.
         * A package context would not do: it keeps the name of the context it was made from (ours, or "android").
         */
        fun shellIdentity(context: Context): Context = object : ContextWrapper(context) {
            override fun getOpPackageName() = SHELL_PACKAGE

            // The virtual device's client takes its identity from the application context
            override fun getApplicationContext(): Context = this

            override fun getAttributionSource(): AttributionSource =
                AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).setPid(Process.myPid()).build()
        }

        /**
         * A system service's manager built on [context] (its hidden (service, context) constructor): the one
         * getSystemService gives is tied to the base context, and so to its package name.
         */
        fun manager(context: Context, managerClass: String, serviceName: String, serviceInterface: String): Any {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, serviceName) as IBinder? ?: error("no $serviceName service")
            val service = Class.forName("$serviceInterface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val constructor = Class.forName(managerClass).declaredConstructors
                .first { it.parameterTypes.size == 2 && it.parameterTypes[1] == Context::class.java }
            constructor.isAccessible = true
            return constructor.newInstance(service, context)
        }
    }
}
