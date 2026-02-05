package com.porttracker.ais

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts AIS Receiver Service on device boot if enabled in settings
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "porttracker-service.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, checking auto-start settings...")
            
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val autoStart = prefs.getBoolean("auto_start_boot", false)
            
            if (autoStart) {
                Log.i(TAG, "Auto-start enabled, starting AIS Receiver Service...")
                val serviceIntent = Intent(context, AisReceiverService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                Log.i(TAG, "Auto-start disabled, not starting service")
            }
        }
    }
}
