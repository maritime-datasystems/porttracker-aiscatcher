package com.porttracker.ais

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Simple in-memory log buffer for displaying debug logs in the UI.
 */
object InternalLog {
    private val logs = LinkedList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    
    fun log(message: String) {
        synchronized(logs) {
            try {
                // Add timestamp
                val timestamp = dateFormat.format(Date())
                logs.addLast("$timestamp: $message")
                
                // Keep last 500 lines
                while (logs.size > 500) {
                    logs.removeFirst()
                }
                Unit
            } catch (e: Exception) {
                Log.w("InternalLog", "Logging failed", e)
            }
        }
    }
    
    fun getLogs(): String {
        synchronized(logs) {
            return logs.joinToString("\n")
        }
    }
    
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
