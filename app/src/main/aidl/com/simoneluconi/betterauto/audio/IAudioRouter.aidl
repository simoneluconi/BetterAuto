package com.simoneluconi.betterauto.audio;

import android.view.Surface;
import com.simoneluconi.betterauto.audio.IAppDisplayListener;

/**
 * The Shizuku service that moves the phone's media audio (and a WiFi setting, Bluetooth answers, the display for
 * phone apps in the car), running as the shell user.
 */
interface IAudioRouter {
    /**
     * Starts routing audio away from the speaker on [route] (any id the app picks: one per stream, e.g.
     * the music and the navigation prompts, each with its own pipe); returns the read end of a pipe of
     * little-endian samples in [encoding]
     * (AudioFormat.ENCODING_PCM_FLOAT or _16BIT), or null if it cannot. Only these [usages]
     * (AudioAttributes.USAGE_*) from the apps with these [uids] are routed (any app if [uids] is empty).
     */
    ParcelFileDescriptor start(int route, int sampleRate, int channels, int encoding, in int[] uids, in int[] usages) = 1;

    /** Stops [route]: the phone plays that audio itself again. */
    void stop(int route) = 2;

    /** What the service did last, for the log. */
    String status() = 3;

    /**
     * Lets the phone stay on its WiFi while it joins the head unit's (STA+STA for local-only networks, which
     * some makers leave off), or gives the setting back to the phone's default. Returns what the command said.
     */
    String setDualWifi(boolean on) = 4;

    /** The phone's real Bluetooth address (apps only get a placeholder), or null. */
    String bluetoothAddress() = 5;

    /** Answers the pairing in progress with this device, as the user would in Android's dialog. */
    boolean confirmPairing(String address, boolean accept) = 6;

    /** Lets the car read contacts and messages, as the user would allow in the dialogs that follow a pairing. */
    boolean allowCarAccess(String address) = 7;

    /** Connects the phone to this paired device (calls, audio), as tapping it in the Bluetooth settings does. */
    boolean connectDevice(String address) = 8;

    /**
     * A binder of the app's own process: when that process dies (a crash, killed), every route stops and the
     * service ends, so the phone gets its sound back even if Shizuku keeps the service running.
     */
    void watchApp(IBinder app) = 9;

    /**
     * Has Android bind this notification listener (flattened component) again, as `cmd notification
     * allow_listener` does; refused unless its access is already granted, so nothing is ever allowed here.
     * Returns what the command said.
     */
    String bindNotificationListener(String component) = 10;

    /**
     * Creates the display that runs phone apps for the car, drawing into [surface] (replacing the one before):
     * a virtual device of ours, whose display keeps running with the phone locked or asleep. [listener] hears
     * what happens on it. Returns its display id, or -1 ([status] says why).
     */
    int createAppDisplay(int width, int height, int densityDpi, in Surface surface, IAppDisplayListener listener) = 11;

    /** Starts [packageName]'s main activity on the app display (back to front if it runs); why it could not, or null. */
    String launchOnAppDisplay(String packageName) = 12;

    /** A key (KeyEvent.KEYCODE_*, pressed or let go) for the app on the app display; [shift] held with it. */
    oneway void appDisplayKey(int keyCode, boolean down, boolean shift) = 13;

    /** A finger on the app display (MotionEvent.ACTION_DOWN, _MOVE or _UP), in its pixels. */
    oneway void appDisplayTouch(int pointerId, int action, float x, float y) = 14;

    /** Wakes the app display or puts it to sleep (its app pauses, as on a phone whose screen goes off). */
    void setAppDisplayOn(boolean on) = 15;

    /** Removes the app display and its virtual device; the apps on it are closed. */
    void releaseAppDisplay() = 17;

    /**
     * The app display draws into [surface] from now on, resized to [width] x [height] at [densityDpi] (a width of
     * 0 keeps its size); a null [surface] leaves it drawing nowhere, while the car shows something else.
     */
    void updateAppDisplay(int width, int height, int densityDpi, in Surface surface) = 18;

    /**
     * Stops every route and ends the process. Called by Shizuku when the app unbinds for good (fixed transaction
     * id from the Shizuku API), and by the app to end its own shell process (ShizukuShellProcess).
     */
    void destroy() = 16777114;
}
