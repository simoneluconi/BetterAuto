package com.simoneluconi.betterauto

import android.app.Application

/** Starts what every entry point needs before anything logs: the service, the app, the listeners. */
class BetterAutoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}
