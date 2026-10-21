package com.ruterika.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the service up after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReceiverService.start(context)
    }
}
