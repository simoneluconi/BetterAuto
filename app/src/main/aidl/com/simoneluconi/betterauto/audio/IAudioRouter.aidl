package com.simoneluconi.betterauto.audio;

/** The Shizuku service that moves the phone's media audio, running as the shell user. */
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

    /** Called by Shizuku when the app unbinds for good (fixed transaction id from the Shizuku API). */
    void destroy() = 16777114;
}
