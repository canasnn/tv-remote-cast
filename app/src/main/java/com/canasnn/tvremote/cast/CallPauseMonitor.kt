package com.canasnn.tvremote.cast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.ruterika.protocol.PlayerState
import timber.log.Timber

/**
 * Pause on incoming call: while casting, pauses the TV when the phone
 * rings or a call starts, and (optionally) resumes it once the call is over.
 * Needs READ_PHONE_STATE; uses [TelephonyCallback] on API 31+.
 */
class CallPauseMonitor(private val context: Context) {

    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)
    private var callback: Any? = null
    private var pausedByCall = false

    /** Listens while [enabled] and the permission is granted; stops otherwise. */
    fun update(enabled: Boolean) {
        val listen = enabled && hasPermission(context)
        if (listen && callback == null) register()
        else if (!listen && callback != null) unregister()
    }

    fun release() = unregister()

    private fun onCallState(state: Int) {
        val settings = CastHub.settings.value
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                val playerState = CastHub.status.value.state
                val playing = playerState == PlayerState.PLAYING || playerState == PlayerState.BUFFERING
                val session = CastHub.session.value
                if (settings.pauseOnCall && playing && session != null && !session.ended && !session.isPhotos) {
                    if (CastHub.pause()) pausedByCall = true
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (pausedByCall && settings.resumeAfterCall && CastHub.status.value.state == PlayerState.PAUSED) CastHub.resume()
                pausedByCall = false
            }
        }
    }

    private fun register() {
        val tm = telephony ?: return
        runCatching {
            callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                CallStateCallback().also { tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), it) }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                listener
            }
        }.onFailure { Timber.w(it, "[Cast] Could not listen to call state") }
    }

    @Suppress("DEPRECATION")
    private fun unregister() {
        val tm = telephony
        val cb = callback ?: return
        callback = null
        pausedByCall = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) tm?.unregisterTelephonyCallback(cb)
            else if (cb is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm?.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class CallStateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onCallState(state)
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
}
