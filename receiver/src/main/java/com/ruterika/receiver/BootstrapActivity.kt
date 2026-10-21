package com.ruterika.receiver

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.ruterika.protocol.Protocol

/**
 * Invisible trampoline opened by the phone through the TV remote protocol.
 *
 * - `start?t=TOKEN`: authorizes the phone and (re)starts the service. The very
 *   first one also takes the app out of the "stopped" state so boot
 *   broadcasts reach us from then on.
 * - `launch?c=pkg/cls`: opens another TV app. We are in the foreground here,
 *   so starting it is allowed without ADB.
 */
class BootstrapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        when (uri.host) {
            Protocol.HOST_START -> {
                uri.getQueryParameter(Protocol.PARAM_TOKEN)?.let { ReceiverPrefs(this).addToken(it) }
                ReceiverService.start(this)
            }
            Protocol.HOST_LAUNCH -> {
                ReceiverService.start(this)
                uri.getQueryParameter(Protocol.PARAM_COMPONENT)?.let(::launchApp)
            }
        }
    }

    private fun launchApp(component: String) {
        val name = ComponentName.unflattenFromString(component)
        val launch = name?.let {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(it)
        } ?: packageManager.getLeanbackLaunchIntentForPackage(component.substringBefore('/'))
        if (launch == null) {
            Log.w(TAG, "Nothing to launch for $component")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            // Some apps only export a plain launcher activity.
            val fallback = packageManager.getLaunchIntentForPackage(component.substringBefore('/'))
            if (fallback != null) runCatching { startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else Log.w(TAG, "Could not launch $component", e)
        }
    }

    private companion object {
        const val TAG = "Bootstrap"
    }
}
