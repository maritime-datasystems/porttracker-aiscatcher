package com.porttracker.ais

import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList

/**
 * Simple in-memory log buffer for displaying debug logs in the UI.
 */
object InternalLog {
    private val logs = LinkedList<String>()
    
    fun log(message: String) {
        synchronized(logs) {
            try {
                // Add timestamp
                val timestamp = SimpleDateFormat("HH:mm:ss").format(Date())
                logs.addLast("$timestamp: $message")
                
                // Keep last 500 lines
                if (logs.size > 500) {
                    logs.removeFirst()
                }
            } catch (e: Exception) {
                // Ignore errors
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
