package com.porttracker.ais

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Debug receiver to force-start the service via ADB.
 * Usage: adb shell am broadcast -a com.porttracker.ais.FORCE_START -n com.porttracker.ais/.DebugReceiver
 */
class DebugReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "porttracker-service.Debug"
        const val ACTION_FORCE_START = "com.porttracker.ais.FORCE_START"
        const val ACTION_FORCE_STOP = "com.porttracker.ais.FORCE_STOP"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            ACTION_FORCE_START -> {
                Log.i(TAG, "Force-starting AisReceiverService...")
                val serviceIntent = Intent(context, AisReceiverService::class.java).apply {
                    action = "FORCE_START"
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.i(TAG, "Service start command sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service", e)
                }
            }
            ACTION_FORCE_STOP -> {
                Log.i(TAG, "Force-stopping AisReceiverService...")
                val serviceIntent = Intent(context, AisReceiverService::class.java)
                context.stopService(serviceIntent)
                Log.i(TAG, "Service stop command sent")
            }
        }
    }
}
