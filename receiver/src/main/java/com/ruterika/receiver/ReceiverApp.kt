package com.ruterika.receiver

import android.app.Application

class ReceiverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Any process start (boot, deep link, restart after being killed)
        // brings the service up, so the phone can always reach us.
        ReceiverService.start(this)
    }
}
