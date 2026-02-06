package com.porttracker.ais

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Invisible activity to force-start the service via ADB.
 * Usage: adb shell am start -n com.porttracker.ais/.ForceStartActivity
 * 
 * This bypasses Android 12+ background start restrictions by using an Activity context.
 * It also enables the web viewer preference to ensure the AdminWebServer starts.
 */
class ForceStartActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "porttracker-service.ForceStart"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "Force-starting AisReceiverService via Activity...")
        
        // Ensure web viewer is enabled so AdminWebServer starts
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("webviewer_enabled", false)) {
            Log.i(TAG, "Enabling webviewer_enabled preference for force-start")
            prefs.edit().putBoolean("webviewer_enabled", true).apply()
        }
        
        val serviceIntent = Intent(this, AisReceiverService::class.java).apply {
            action = "FORCE_START"
        }
        
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.i(TAG, "Service start command sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
        
        // Close immediately - this is just a launcher
        finish()
    }
}
