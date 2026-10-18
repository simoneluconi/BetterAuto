package com.simoneluconi.betterauto.audio;

/** The Shizuku service that moves the phone's media audio (and a WiFi setting), running as the shell user. */
interface IAudioRouter {
    /**
     * Starts routing media audio away from the speaker; returns the read end of a pipe of little-endian
     * samples in [encoding] (AudioFormat.ENCODING_PCM_FLOAT or _16BIT), or null if it cannot.
     * Only these [usages] (AudioAttributes.USAGE_*) from the apps with these [uids] are routed
     * (any app if [uids] is empty).
     */
    ParcelFileDescriptor start(int sampleRate, int channels, int encoding, in int[] uids, in int[] usages) = 1;

    /** Stops routing: the phone plays its own audio again. */
    void stop() = 2;

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

    /** Called by Shizuku when the app unbinds for good (fixed transaction id from the Shizuku API). */
    void destroy() = 16777114;
}
