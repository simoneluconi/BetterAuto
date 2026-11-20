package com.simoneluconi.betterauto.audio;

/** What happens on the display for phone apps, told back to the app by the shell service. */
oneway interface IAppDisplayListener {
    /** The app now in front on the display (another one may have been opened from it). */
    void onTopApp(String packageName);

    /** The last activity left the display: Back went past the app's first screen. */
    void onEmpty();

    /** A window that forbids being shown on other screens (DRM video, banking) came up or went away. */
    void onSecureWindow(boolean shown);
}
