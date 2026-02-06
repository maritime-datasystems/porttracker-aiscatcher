package com.porttracker.ais

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Scans for connected USB SDR devices
 */
object UsbDeviceScanner {
    private const val TAG = "porttracker-service.Scanner"
    
    // Known RTL-SDR vendor/product IDs
    private val RTL_SDR_DEVICES = listOf(
        Pair(0x0BDA, 0x2832), // RTL2832U
        Pair(0x0BDA, 0x2838), // RTL2838UHIDIR
        Pair(0x0FCE, 0x6A34), // Sony
        Pair(0x1F4D, 0xB803), // GTek
        Pair(0x1F4D, 0xC803), // Lifeview
        Pair(0x1F4D, 0xD286), // MyGica
        Pair(0x1B80, 0xD3A4), // Zadig
        Pair(0x1D19, 0x1101), // Dexatek
        Pair(0x1D19, 0x1102), // Dexatek
        Pair(0x1D19, 0x1103), // Dexatek
        Pair(0x0458, 0x707F), // Genius
        Pair(0x1B80, 0xD393), // SVEON
    )
    
    // Known AirSpy vendor/product IDs  
    private val AIRSPY_DEVICES = listOf(
        Pair(0x1D50, 0x60A1), // AirSpy
        Pair(0x1D50, 0x60A6), // AirSpy HF+
    )
    
    data class SdrDeviceInfo(
        val found: Boolean,
        val deviceName: String,
        val manufacturer: String,
        val productId: Int,
        val vendorId: Int,
        val deviceType: String,
        val isUsable: Boolean,
        val usbDevice: UsbDevice? = null
    )
    
    /**
     * Check if a vendor/product ID pair is a supported SDR device
     */
    fun isSupportedDevice(vendorId: Int, productId: Int): Boolean {
        return RTL_SDR_DEVICES.any { it.first == vendorId && it.second == productId } ||
               AIRSPY_DEVICES.any { it.first == vendorId && it.second == productId }
    }
    
    /**
     * Scan for connected SDR devices
     */
    fun scanForDevices(context: Context): SdrDeviceInfo {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            
            Log.d(TAG, "Scanning ${deviceList.size} USB devices")
            
            for ((_, device) in deviceList) {
                val vendorId = device.vendorId
                val productId = device.productId
                
                // Check RTL-SDR
                if (RTL_SDR_DEVICES.any { it.first == vendorId && it.second == productId }) {
                    val hasPermission = usbManager.hasPermission(device)
                    return SdrDeviceInfo(
                        found = true,
                        deviceName = device.productName ?: "RTL-SDR",
                        manufacturer = device.manufacturerName ?: "Unknown",
                        productId = productId,
                        vendorId = vendorId,
                        deviceType = "RTL-SDR",
                        isUsable = hasPermission,
                        usbDevice = device
                    )
                }
                
                // Check AirSpy
                if (AIRSPY_DEVICES.any { it.first == vendorId && it.second == productId }) {
                    val hasPermission = usbManager.hasPermission(device)
                    val isHfPlus = productId == 0x60A6
                    return SdrDeviceInfo(
                        found = true,
                        deviceName = device.productName ?: if (isHfPlus) "AirSpy HF+" else "AirSpy",
                        manufacturer = device.manufacturerName ?: "AirSpy",
                        productId = productId,
                        vendorId = vendorId,
                        deviceType = if (isHfPlus) "AirSpy HF+" else "AirSpy",
                        isUsable = hasPermission,
                        usbDevice = device
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB devices", e)
        }
        
        return SdrDeviceInfo(
            found = false,
            deviceName = "No device found",
            manufacturer = "",
            productId = 0,
            vendorId = 0,
            deviceType = "",
            isUsable = false
        )
    }
    
    /**
     * Get status text for display
     */
    fun getStatusText(info: SdrDeviceInfo): String {
        return when {
            !info.found -> "No SDR device connected"
            !info.isUsable -> "${info.deviceName} (Permission needed)"
            else -> "${info.deviceName} (Ready)"
        }
    }
    
    /**
     * Get all connected USB devices with SDR devices highlighted
     */
    fun getAllUsbDevices(context: Context): String {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            
            if (deviceList.isEmpty()) {
                return "No USB devices connected"
            }
            
            val lines = mutableListOf<String>()
            for ((_, device) in deviceList) {
                val vendorId = device.vendorId
                val productId = device.productId
                val name = device.productName ?: "Unknown Device"
                
                // Check if it's a known SDR device
                val isSdr = RTL_SDR_DEVICES.any { it.first == vendorId && it.second == productId } ||
                           AIRSPY_DEVICES.any { it.first == vendorId && it.second == productId }
                
                val prefix = if (isSdr) "📡 " else "   "
                val suffix = if (isSdr) " [SDR]" else ""
                lines.add("$prefix$name (${String.format("%04X", vendorId)}:${String.format("%04X", productId)})$suffix")
            }
            
            return lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error listing USB devices", e)
            return "Error scanning USB"
        }
    }
}
