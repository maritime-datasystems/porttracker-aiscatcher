@file:JvmName("AisCatcherJava")
package com.jvdegithub.aiscatcher

import android.util.Log

/**
 * JNI bridge class that matches the expected native method signatures.
 * The native library expects methods in com.jvdegithub.aiscatcher.AisCatcherJava
 */
object AisCatcherJava {
    private const val TAG = "porttracker-service.JNI"
    
    var isLibraryLoaded = false
        private set
    
    init {
        try {
            System.loadLibrary("AIScatcherNDK")
            Log.i(TAG, "Native library loaded successfully")
            isLibraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
            isLibraryLoaded = false
        }
    }
    
    // Statistics inner class for JNI callbacks
    object Statistics {
        @JvmField var DataB: Int = 0
        @JvmField var DataGB: Int = 0
        @JvmField var Total: Int = 0
        @JvmField var ChA: Int = 0
        @JvmField var ChB: Int = 0
        @JvmField var Msg123: Int = 0
        @JvmField var Msg5: Int = 0
        @JvmField var Msg1819: Int = 0
        @JvmField var Msg24: Int = 0
        @JvmField var MsgOther: Int = 0
        
        @JvmStatic
        external fun Init()
        
        @JvmStatic
        external fun Reset()
    }
    
    // Callbacks from native code
    @JvmStatic
    fun onNMEA(nmea: String) {
        Log.d(TAG, "NMEA: $nmea")
        com.porttracker.ais.AisReceiverService.onNMEA(nmea)
    }
    
    @JvmStatic
    fun onStatus(status: String) {
        Log.d(TAG, "Status: $status")
        com.porttracker.ais.AisReceiverService.onStatus(status)
    }
    
    @JvmStatic
    fun onMessage(message: String) {
        Log.d(TAG, "Message: $message")
    }
    
    @JvmStatic
    fun onError(error: String) {
        Log.e(TAG, "Error: $error")
        com.porttracker.ais.AisReceiverService.onError(error)
    }
    
    @JvmStatic
    fun onUpdate() {
        Log.d(TAG, "Update received")
    }
    
    // Native methods
    @JvmStatic
    external fun InitNative(webServerPort: Int, bindAddress: String): Int
    
    @JvmStatic
    external fun isStreaming(): Boolean
    
    @JvmStatic
    external fun applySetting(device: String, setting: String, value: String): Int
    
    @JvmStatic
    external fun Run(): Int
    
    @JvmStatic
    external fun Close(): Int
    
    @JvmStatic
    external fun forceStop(): Int
    
    @JvmStatic
    external fun createReceiver(source: Int, fd: Int, cgfWide: Int, modelType: Int, fpds: Int): Int
    
    @JvmStatic
    external fun createUDP(host: String, port: String, json: Boolean): Int
    
    @JvmStatic
    external fun createWebViewer(port: String): Int
    
    @JvmStatic
    external fun createTCPlistener(port: String): Int
    
    @JvmStatic
    external fun createSharing(enabled: Boolean, key: String): Int
    
    @JvmStatic
    external fun getSampleRate(): Int
    
    @JvmStatic
    external fun setLatLon(lat: Float, lon: Float)
    
    @JvmStatic
    external fun getLibraryVersion(): String
    
    @JvmStatic
    external fun setDeviceDescription(product: String, vendor: String, serial: String)
    
    @JvmStatic
    external fun getRateDescription(): String
}
