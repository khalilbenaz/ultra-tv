package com.ultratv.tv.nativeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Triggered by ACTION_BOOT_COMPLETED when "Launch at boot" is enabled in
 * preferences. Launches MainActivity as a new task; if the user disabled the
 * pref, the receiver returns immediately and does nothing.
 *
 * Android TV launchers don't auto-launch foreground apps; this BootReceiver is
 * what gives Ultra TV the "always be the first thing on screen" behavior.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: UserPreferencesStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val launch = prefs.flow.first().launchAtBoot
                if (launch) {
                    val start = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(start)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
