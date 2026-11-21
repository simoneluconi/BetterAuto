package com.simoneluconi.betterauto.ui.apps

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.projection.AppDensity
import com.simoneluconi.betterauto.projection.NudgeStreak
import com.simoneluconi.betterauto.projection.PhoneAppDisplay
import com.simoneluconi.betterauto.projection.PointerMotion
import com.simoneluconi.betterauto.projection.PointerPosition
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.LocalLeaveToBar
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Holding the knob's center this long opens the panel over the app, as a long press elsewhere (CarFocus). */
private const val HOLD_MS = 600L

/**
 * A phone app opened from a tile on the screen (the Apps grid): once shown it takes the knob, which would otherwise
 * fall back to the bar with the tile gone. Opened from the bar, the knob stays there. Main thread.
 */
object PhoneAppOpening {
    var takeKnob = false
}

/**
 * The panel held open over a phone app, for the launcher's key handling (main thread): the press that opened it is
 * not a press in it, Back closes it, nudges stay inside it, and a knob move from outside lands on [first].
 */
object PhoneAppPanelShown {
    var value = false
        internal set

    /** The focus is inside the panel. */
    var hasFocus = false
        internal set

    /** The panel's first button. */
    var first: FocusRequester? = null
        internal set

    /** Closes it, the knob back on the app. */
    var close: (() -> Unit)? = null
        internal set

    /**
     * The knob's press that opened it is still held: its repeats and its release are not a press in the panel. Let go
     * by the first release of the press, wherever it lands.
     */
    var holdingPress = false

    internal fun reset() {
        value = false
        hasFocus = false
        first = null
        close = null
        holdingPress = false
    }
}

/**
 * A phone app ([PhoneAppDisplay]) inside the launcher's screen. Its picture takes the knob like a control, in one of
 * two ways chosen per app ([LauncherSettings.phonePointerApps]): as keys (turns are Tab / Shift+Tab, nudges arrows,
 * a press the d-pad's center), or with a pointer of ours for apps that ignore keys (nudges move it, turns scroll, a
 * press taps there). Back goes to the app either way; holding the press opens a panel to switch, go back to the bar
 * or close the app. Touches go to the app as they are. While the car moves (unless the driver allowed it), with a
 * window the app keeps off other screens, or without Shizuku, a message stands in its place, with the way back to
 * the apps.
 */
@Composable
fun PhoneAppScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val open by PhoneAppDisplay.open.collectAsStateWithLifecycle()
    val secure by PhoneAppDisplay.secure.collectAsStateWithLifecycle()
    val problem by PhoneAppDisplay.problem.collectAsStateWithLifecycle()
    val whileMoving by settings.phoneAppsWhileMoving.collectAsStateWithLifecycle()
    val motion by CarMotionState.state.collectAsStateWithLifecycle()
    val size by settings.phoneAppSize.collectAsStateWithLifecycle()
    val pointerApps by settings.phonePointerApps.collectAsStateWithLifecycle()
    // The pointer is for cars driven by the knob alone: with a touchscreen the finger does that job
    val touchscreen by CarState.touchscreen.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { PhoneAppDisplay.closed.collect { settings.setSection(Section.APPS) } }
    val app = open
    if (app == null) {
        // Nothing to show (a new session, the app closed): the apps instead
        LaunchedEffect(Unit) { settings.setSection(Section.APPS) }
        return
    }
    val blocked = !whileMoving && motion == CarMotion.MOVING
    val leave = LocalLeaveToBar.current
    val pictureFocus = remember { FocusRequester() }
    var panel by remember { mutableStateOf(false) }
    var panelOpens by remember { mutableIntStateOf(0) }
    var refocusPicture by remember { mutableIntStateOf(0) }
    // A message had the knob when it went: the picture back in its place takes it again
    var messageKnob by remember { mutableStateOf(false) }

    fun showPanel(byKey: Boolean) {
        panel = true
        panelOpens++
        PhoneAppPanelShown.value = true
        PhoneAppPanelShown.holdingPress = byKey
    }

    fun closePanel(refocus: Boolean) {
        panel = false
        PhoneAppPanelShown.value = false
        PhoneAppPanelShown.holdingPress = false
        if (refocus) refocusPicture++
    }

    SideEffect { PhoneAppPanelShown.close = { closePanel(refocus = PhoneAppPanelShown.hasFocus) } }
    DisposableEffect(Unit) { onDispose { PhoneAppPanelShown.reset() } }
    // Once the picture takes the knob again (the panel gone in the same frame)
    LaunchedEffect(refocusPicture) {
        if (refocusPicture == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { pictureFocus.requestFocus() }
    }
    val pictureShown = !blocked && problem == null && !secure
    LaunchedEffect(pictureShown) {
        if (!pictureShown && panel) closePanel(refocus = false)
        if (pictureShown && messageKnob) {
            messageKnob = false
            refocusPicture++
        }
    }
    val onMessageKnob = { has: Boolean -> messageKnob = has }
    // Opened from a tile: the picture (or the message in its place) takes the knob, once the launcher has let the
    // tile's focus go (two frames, as the map brought forward)
    var takeKnobOnOpen by remember { mutableIntStateOf(0) }
    LaunchedEffect(app) {
        if (!PhoneAppOpening.takeKnob) return@LaunchedEffect
        PhoneAppOpening.takeKnob = false
        withFrameNanos { }
        withFrameNanos { }
        takeKnobOnOpen++
    }
    LaunchedEffect(takeKnobOnOpen) {
        if (takeKnobOnOpen != 0 && pictureShown) runCatching { pictureFocus.requestFocus() }
    }

    fun closeTheApp() {
        closePanel(refocus = false)
        // The knob waits on the bar, not lost with the picture
        leave?.invoke()
        PhoneAppDisplay.closeApp()
        settings.setSection(Section.APPS)
    }

    // The app fills the screen edge to edge, with tighter corners than a card: those would cut what apps put there
    val shape = RoundedCornerShape(if (!blocked && problem == null) PICTURE_CORNER else 24.dp)
    Box(modifier.clip(shape).background(colors.surface, shape)) {
        when {
            blocked -> Message(
                CarIcons.Car, stringResource(R.string.phone_app_parked_title), stringResource(R.string.phone_app_parked_desc),
                settings, messageKnob, onMessageKnob, takeKnobOnOpen,
            )
            problem != null -> Message(
                CarIcons.Smartphone, stringResource(R.string.phone_app_problem_title),
                stringResource(if (problem == PhoneAppDisplay.Problem.NO_SHIZUKU) R.string.phone_app_problem_shizuku else R.string.phone_app_problem_failed),
                settings, messageKnob, onMessageKnob, takeKnobOnOpen,
            )
            else -> {
                val pointer = app in pointerApps && !touchscreen
                AppPicture(
                    app, size.scale.toFloat(), pointer,
                    // A protected window: black under the message, which takes the knob and the touches instead
                    focusable = !secure,
                    input = !secure && !panel,
                    focus = pictureFocus,
                    onHold = { showPanel(byKey = true) },
                )
                if (secure) {
                    Box(Modifier.fillMaxSize().background(colors.surface).pointerInput(Unit) { detectTapGestures { } }) {
                        // The app can't be seen, but it can still be stepped back in (a film's player left with Back)
                        // or closed: never stuck behind the message
                        Message(
                            CarIcons.Smartphone, stringResource(R.string.phone_app_secure_title), stringResource(R.string.phone_app_secure_desc),
                            settings, messageKnob, onMessageKnob, takeKnobOnOpen,
                            actions = listOf(
                                stringResource(R.string.phone_app_back_in_app) to {
                                    PhoneAppDisplay.key(KeyEvent.KEYCODE_BACK, true)
                                    PhoneAppDisplay.key(KeyEvent.KEYCODE_BACK, false)
                                },
                                stringResource(R.string.phone_app_close) to { closeTheApp() },
                            ),
                        )
                    }
                }
                // Behind the panel: the app dims, nothing reaches it, and a tap there closes the panel
                if (panel) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(colors.bg.copy(alpha = 0.4f))
                            .pointerInput(Unit) { detectTapGestures { closePanel(refocus = PhoneAppPanelShown.hasFocus) } },
                    )
                }
                AnimatedVisibility(
                    visible = panel,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    val context = LocalContext.current
                    val name = remember(app) { MediaApps.info(context, app)?.label ?: app }
                    HoldPanel(
                        name, pointer, offerPointer = !touchscreen, opened = panelOpens,
                        onMode = {
                            settings.togglePhonePointer(app)
                            closePanel(refocus = true)
                        },
                        onBar = {
                            closePanel(refocus = false)
                            leave?.invoke()
                        },
                        onCloseApp = { closeTheApp() },
                    )
                }
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun AppPicture(
    packageName: String,
    scale: Float,
    pointer: Boolean,
    focusable: Boolean,
    input: Boolean,
    focus: FocusRequester,
    onHold: () -> Unit,
) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val launcherDensity = LocalDensity.current.density
    val pictureListener = remember { PictureListener(context, launcherDensity, scale) }
    // A new size choice, or the launcher drawn at another scale (another screen shape), redraws the app at once
    LaunchedEffect(scale, launcherDensity) { pictureListener.rescale(scale, launcherDensity) }
    // The car showing its own screen stops the launcher without removing the picture: the app sleeps meanwhile
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Bumped when the launcher stops: a press or nudge held then never gets its release here
    var stops by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> pictureListener.resume()
                Lifecycle.Event.ON_STOP -> {
                    pictureListener.pause()
                    stops++
                }
                else -> Unit
            }
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) pictureListener.pause()
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val inputOn by rememberUpdatedState(input)
    var focused by remember { mutableStateOf(false) }
    // The knob's press: a hold timer, and whether this press already opened the panel
    var hold by remember { mutableStateOf<Job?>(null) }
    var heldLong by remember { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    // Pointer mode: our cursor in the picture's pixels, which are the display's
    var pictureSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf<PointerPosition?>(null) }
    val streak = remember { NudgeStreak() }
    var glide by remember { mutableStateOf<Job?>(null) }
    var glideKey by remember { mutableStateOf<Key?>(null) }
    val name = remember(packageName) { MediaApps.info(context, packageName)?.label ?: packageName }
    val description = stringResource(if (pointer) R.string.phone_app_screen_pointer else R.string.phone_app_screen, name)
    DisposableEffect(Unit) { onDispose { PhoneAppDisplay.hasKnob = false } }

    // Whatever was held when the knob came or went: a release that went elsewhere must not leave the next press lost
    fun resetPress() {
        hold?.cancel()
        hold = null
        heldLong = false
        pressing = false
        glide?.cancel()
        glide = null
        glideKey = null
        streak.reset()
    }

    // The car took the screen mid-press: the glide stops and the hold timer can't open the panel meanwhile
    LaunchedEffect(stops) {
        if (stops == 0) return@LaunchedEffect
        resetPress()
        PhoneAppPanelShown.holdingPress = false
    }

    // Once per hold, from the timer or from the car's own long-press flag, whichever comes first
    fun held() {
        if (heldLong) return
        heldLong = true
        hold?.cancel()
        hold = null
        pressing = false
        onHold()
    }

    fun cursorNow(): PointerPosition? {
        val size = pictureSize
        if (size.width <= 0 || size.height <= 0) return null
        return cursor ?: PointerMotion.center(size.width, size.height)
    }

    fun moveCursor(dx: Int, dy: Int, share: Float) {
        val from = cursorNow() ?: return
        cursor = PointerMotion.move(from, dx, dy, share, pictureSize.width, pictureSize.height)
    }

    // A nudge moves the cursor one step at once (quick ones in a row a bit more), held it glides faster and faster
    fun nudge(key: Key, down: Boolean, native: KeyEvent, dx: Int, dy: Int) {
        if (!down) {
            if (glideKey == key) {
                glide?.cancel()
                glide = null
                glideKey = null
            }
            return
        }
        // The car repeating the held nudge, or flagging it long: the glide is already on it
        if (glideKey == key && (native.isLongPress || native.repeatCount > 0)) return
        glide?.cancel()
        val pressedAt = SystemClock.uptimeMillis()
        moveCursor(dx, dy, PointerMotion.nudgeStep(streak.press(dx, dy, pressedAt)))
        glideKey = key
        glide = scope.launch {
            delay(PointerMotion.HOLD_DELAY_MS)
            while (true) {
                moveCursor(dx, dy, PointerMotion.heldStep(SystemClock.uptimeMillis() - pressedAt))
                delay(PointerMotion.REPEAT_MS)
            }
        }
    }

    fun press() {
        if (pointer) {
            val at = cursorNow() ?: return
            PhoneAppDisplay.tap(at.x, at.y)
        } else {
            PhoneAppDisplay.key(KeyEvent.KEYCODE_DPAD_CENTER, true)
            PhoneAppDisplay.key(KeyEvent.KEYCODE_DPAD_CENTER, false)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Under the picture: a frame that comes partly transparent (a display waking) shows dark, not the card
            .background(colors.appBackdrop)
            .onSizeChanged { new ->
                cursor = cursor?.let { PointerMotion.rescale(it, pictureSize.width, pictureSize.height, new.width, new.height) }
                pictureSize = new
            }
            .drawWithContent {
                drawContent()
                if (!focused) return@drawWithContent
                // The knob is on the app: a ring along its edge, the app's own focus shows within
                val stroke = 3.dp.toPx()
                drawRoundRect(
                    colors.focus,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(PICTURE_CORNER.toPx() - stroke / 2),
                    style = Stroke(stroke),
                )
                if (!pointer || !inputOn) return@drawWithContent
                // Our pointer: a focus-colored ring outlined in the surface color, seen over light and dark apps alike
                val at = cursor ?: PointerPosition(size.width / 2, size.height / 2)
                val center = Offset(at.x, at.y)
                val radius = CURSOR_RADIUS.toPx()
                drawCircle(colors.surface, radius, center, style = Stroke(7.dp.toPx()))
                drawCircle(colors.focus, radius, center, style = Stroke(3.5.dp.toPx()))
                if (pressing) drawCircle(colors.focus.copy(alpha = 0.45f), radius, center)
                drawCircle(colors.surface, 4.5.dp.toPx(), center)
                drawCircle(colors.focus, 2.5.dp.toPx(), center)
            }
            .semantics { contentDescription = description }
            .onPreviewKeyEvent { event ->
                // The panel or a message over the app: nothing goes to it
                if (!inputOn) return@onPreviewKeyEvent false
                val down = event.type == KeyEventType.KeyDown
                val native = event.nativeKeyEvent
                when (event.key) {
                    // Pointer: one detent, one scroll step (pressed only: the knob sends a press and a release)
                    Key.Tab -> if (!pointer) {
                        PhoneAppDisplay.key(KeyEvent.KEYCODE_TAB, down, shift = event.isShiftPressed)
                    } else if (down) {
                        cursorNow()?.let { PhoneAppDisplay.scroll(it.x, it.y, pictureSize.width, pictureSize.height, if (event.isShiftPressed) -1 else 1) }
                    }
                    Key.DirectionUp -> if (pointer) nudge(event.key, down, native, 0, -1) else PhoneAppDisplay.key(KeyEvent.KEYCODE_DPAD_UP, down)
                    Key.DirectionDown -> if (pointer) nudge(event.key, down, native, 0, 1) else PhoneAppDisplay.key(KeyEvent.KEYCODE_DPAD_DOWN, down)
                    Key.DirectionLeft -> if (pointer) nudge(event.key, down, native, -1, 0) else PhoneAppDisplay.key(KeyEvent.KEYCODE_DPAD_LEFT, down)
                    Key.DirectionRight -> if (pointer) nudge(event.key, down, native, 1, 0) else PhoneAppDisplay.key(KeyEvent.KEYCODE_DPAD_RIGHT, down)
                    Key.Back, Key.Escape -> PhoneAppDisplay.key(KeyEvent.KEYCODE_BACK, down)
                    // Sent on release, so that a held press can open the panel instead
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (down) {
                        val fresh = native.repeatCount == 0 && !native.isLongPress
                        if (fresh && hold == null) {
                            heldLong = false
                            pressing = true
                            hold = scope.launch {
                                delay(HOLD_MS)
                                held()
                            }
                        }
                        // A car that flags the long press itself, or repeats the held key
                        if (!fresh) held()
                    } else {
                        // Only the release of a press seen here: one that began elsewhere (the knob just arrived) is not one
                        val pressed = pressing && !heldLong
                        hold?.cancel()
                        hold = null
                        pressing = false
                        PhoneAppPanelShown.holdingPress = false
                        if (pressed) press()
                        heldLong = false
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusRequester(focus)
            .contentFocus(focusable)
            .onFocusChanged {
                if (it.isFocused != focused) resetPress()
                focused = it.isFocused
                PhoneAppDisplay.hasKnob = it.isFocused
            }
            .focusable(focusable),
    ) {
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    // With the phone locked the display's picture comes with some transparency: the card showed through
                    isOpaque = true
                    surfaceTextureListener = pictureListener
                    setOnTouchListener { _, event ->
                        // A finger already down is always let go: the app must not keep it pressed
                        if (inputOn || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            forwardTouch(event)
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val PICTURE_CORNER = 16.dp
private val CURSOR_RADIUS = 15.dp

/**
 * The panel a held press opens over the app: how the knob drives this app (the mode now, and a button for the
 * other, only on a car without a touchscreen: [offerPointer]), back to the bar, or close the app. It takes the knob
 * on its first button, every time it is [opened], and keeps it inside.
 */
@Composable
private fun HoldPanel(
    appName: String,
    pointer: Boolean,
    offerPointer: Boolean,
    opened: Int,
    onMode: () -> Unit,
    onBar: () -> Unit,
    onCloseApp: () -> Unit,
) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(28.dp)
    val first = remember { FocusRequester() }
    DisposableEffect(first) {
        PhoneAppPanelShown.first = first
        onDispose {
            if (PhoneAppPanelShown.first === first) PhoneAppPanelShown.first = null
            PhoneAppPanelShown.hasFocus = false
        }
    }
    // Opened again during its closing animation the content stays the same: keyed on each opening
    LaunchedEffect(opened) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BoxWithConstraints {
        // Three buttons side by side need a wide picture; on a narrow one they stack
        val wide = maxWidth >= 900.dp
        Column(
            Modifier
                .shadow(28.dp, shape)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.line, shape)
                // A tap on the panel itself is not a tap outside
                .pointerInput(Unit) { detectTapGestures { } }
                .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
                .onFocusChanged { PhoneAppPanelShown.hasFocus = it.hasFocus }
                .focusGroup()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CarLabel(appName, 21.sp, colors.text, weight = FontWeight.Bold, shrinkToFit = true)
                CarLabel(
                    stringResource(if (pointer) R.string.phone_app_mode_pointer else R.string.phone_app_mode_focus),
                    18.sp, colors.text2, maxLines = 2,
                )
            }
            val buttons = @Composable { buttonModifier: Modifier ->
                if (offerPointer) {
                    PanelButton(
                        if (pointer) CarIcons.Controls else CarIcons.Pointer,
                        stringResource(if (pointer) R.string.phone_app_use_focus else R.string.phone_app_use_pointer),
                        buttonModifier.focusRequester(first), onMode,
                    )
                }
                PanelButton(
                    CarIcons.Back, stringResource(R.string.phone_app_to_bar),
                    if (offerPointer) buttonModifier else buttonModifier.focusRequester(first), onBar,
                )
                PanelButton(CarIcons.Close, stringResource(R.string.phone_app_close), buttonModifier, onCloseApp)
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons(Modifier) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { buttons(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier
            .carClickable(pill, label, onClick = onClick)
            .background(colors.surface2, pill)
            .height(56.dp)
            .padding(start = 18.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarIcon(icon, colors.text, Modifier.size(24.dp))
        CarLabel(label, 19.sp, colors.text, weight = FontWeight.Bold, shrinkToFit = true)
    }
}

/**
 * The display draws into this view's surface while it is there and the launcher runs; gone or stopped, the display
 * sleeps. Its density follows the picture's size on this car's screen ([AppDensity]).
 */
private class PictureListener(
    private val context: android.content.Context,
    private var launcherDensity: Float,
    private var scale: Float,
) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var active = true

    fun rescale(value: Float, density: Float) {
        if (value == scale && density == launcherDensity) return
        scale = value
        launcherDensity = density
        attach()
    }

    /** The launcher stopped (the car shows its own screen): the display sleeps, its surface kept for later. */
    fun pause() {
        if (!active) return
        active = false
        if (surface != null) PhoneAppDisplay.detach()
    }

    fun resume() {
        if (active) return
        active = true
        attach()
    }

    private fun attach() {
        val current = surface ?: return
        if (!active) return
        PhoneAppDisplay.attach(context, current, width, height, AppDensity.dpi(width, height, launcherDensity, scale))
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(width, height)
        surface = Surface(texture)
        this.width = width
        this.height = height
        attach()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(width, height)
        this.width = width
        this.height = height
        attach()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        PhoneAppDisplay.detach()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

/** The view's pixels are the display's: each finger goes on as it is, by its own id. */
private fun forwardTouch(event: MotionEvent) {
    fun send(index: Int, action: Int) = PhoneAppDisplay.touch(event.getPointerId(index), action, event.getX(index), event.getY(index))
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(event.actionIndex, MotionEvent.ACTION_DOWN)
        MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) send(i, MotionEvent.ACTION_MOVE)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(event.actionIndex, MotionEvent.ACTION_UP)
        // A cancel needs a palm on a virtual touchscreen: every finger lifted instead
        MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) send(i, MotionEvent.ACTION_UP)
    }
}

/**
 * In the status bar while a phone app is open, on every screen: its icon with the phone badge and its name, so that
 * it is never taken for a screen of BetterAuto. Away from the app it takes the driver back there in one press; its X,
 * always there, closes the app (from inside it, the apps come instead).
 */
@Composable
fun PhoneAppChip(settings: LauncherSettings, section: Section) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val open by PhoneAppDisplay.open.collectAsStateWithLifecycle()
    val app = remember(open) { open?.let { MediaApps.info(context, it) } } ?: return
    val leave = LocalLeaveToBar.current
    val inApp = section == Section.PHONE_APP
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.background(colors.surface, pill).height(52.dp).padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The name alone: the phone badge on the icon already says where it comes from
        val name = app.label
        // Inside the app it says only which app this is; elsewhere it is the way back to it
        val body = if (inApp) Modifier else Modifier.carClickable(pill, stringResource(R.string.phone_app_back_to, app.label)) {
            settings.setSection(Section.PHONE_APP)
        }
        Row(
            body.widthIn(max = 380.dp).padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(app.icon, phone = true, size = 34)
            CarLabel(name, 19.sp, colors.text, weight = FontWeight.Bold, shrinkToFit = true)
        }
        var closeHasKnob by remember { mutableStateOf(false) }
        Box(
            Modifier
                .onFocusChanged { closeHasKnob = it.hasFocus }
                .carClickable(CircleShape, stringResource(R.string.phone_app_close_named, app.label)) {
                    // The knob waits on the bar, not lost with the chip (a touch drops the focus first)
                    if (closeHasKnob) leave?.invoke()
                    PhoneAppPanelShown.close?.invoke()
                    PhoneAppDisplay.closeApp()
                    if (inApp) settings.setSection(Section.APPS)
                }
                .background(colors.surface2, CircleShape)
                .size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Close, colors.text, Modifier.size(20.dp))
        }
    }
}

/**
 * A message in the app's place, with [actions] (label to action, the first one the main control) and the way back to
 * the apps. When the knob was on the picture it replaces, or on the message before it ([knobWaiting]), it lands on
 * the first button: never left on a picture that no longer takes it. [onKnob] says whether the knob is here, kept
 * true when the message goes away with it, so that the picture coming back takes it. A change of [knobRequest] (the
 * app opened from a tile) gives it the knob too.
 */
@Composable
private fun Message(
    icon: ImageVector,
    title: String,
    text: String,
    settings: LauncherSettings,
    knobWaiting: Boolean,
    onKnob: (Boolean) -> Unit,
    knobRequest: Int,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val colors = LocalCarColors.current
    // Read as the message comes, before the picture (or its panel) lets the knob go
    val takeKnob = remember { knobWaiting || PhoneAppDisplay.hasKnob || PhoneAppPanelShown.hasFocus }
    val button = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (!takeKnob) return@LaunchedEffect
        withFrameNanos { }
        runCatching { button.requestFocus() }
    }
    LaunchedEffect(knobRequest) {
        if (knobRequest != 0) runCatching { button.requestFocus() }
    }
    val all = actions + (stringResource(R.string.phone_app_back) to { settings.setSection(Section.APPS) })
    Column(
        Modifier
            .fillMaxSize()
            .onFocusChanged { state ->
                if (state.hasFocus) {
                    onKnob(true)
                } else {
                    // Looked at a frame later: a message that went away with the knob cancels this (its scope ends),
                    // the knob moved elsewhere while it stays does not
                    scope.launch {
                        withFrameNanos { }
                        onKnob(false)
                    }
                }
            }
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        CarIcon(icon, colors.text2, Modifier.size(48.dp))
        CarLabel(title, 26.sp, colors.text, weight = FontWeight.ExtraBold, maxLines = 2, textAlign = TextAlign.Center)
        CarLabel(text, 19.sp, colors.text2, maxLines = 3, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            all.forEachIndexed { i, (label, action) ->
                MessageButton(label, if (i == 0) Modifier.focusRequester(button).contentFocus() else Modifier, action)
            }
        }
    }
}

@Composable
private fun MessageButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val pill = RoundedCornerShape(50)
    Box(
        modifier
            .carClickable(pill, label, onClick = onClick)
            .background(colors.surface2, pill)
            .height(56.dp)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(label, 19.sp, colors.text, weight = FontWeight.Bold)
    }
}
