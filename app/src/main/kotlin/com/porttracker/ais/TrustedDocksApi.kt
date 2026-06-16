package com.porttracker.ais

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Simple HTTP client for the TrustedDocks Gateway API.
 * Used to fetch station configuration (broker URL, credentials, topic info)
 * given an API key provisioned via the TrustedDocks dashboard.
 */
object TrustedDocksApi {
    private const val TAG = "TrustedDocksApi"
    private const val BASE_URL = "https://www.trusteddocks.com"
    private const val CONNECT_TIMEOUT = 10000
    private const val READ_TIMEOUT = 10000

    /**
     * Parsed station configuration returned by the gateway API.
     */
    data class StationConfig(
        val userId: Int,
        val userName: String,
        val companyName: String,
        val portId: Int,
        val portName: String,
        val antennaId: Int,
        val brokerUrl: String,
        val mqttUsername: String,
        val mqttPassword: String,
        val topicPrefix: String,
        val qos: Int
    )

    /**
     * Fetch the station configuration for the given API key.
     *
     * @param apiKey The gateway API key (sent as X-API-Key header).
     * @return parsed [StationConfig], or null on error.
     */
    fun getStationConfig(apiKey: String): StationConfig? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/gateway/station-config")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                Log.e(TAG, "station-config returned HTTP $responseCode: $errorBody")
                return null
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(body)

            return StationConfig(
                userId = json.optInt("user_id", 0),
                userName = json.optString("user_name", ""),
                companyName = json.optString("company_name", ""),
                portId = json.optInt("port_id", 0),
                portName = json.optString("port_name", ""),
                antennaId = json.optInt("antenna_id", 0),
                brokerUrl = json.optString("broker_url", ""),
                mqttUsername = json.optString("mqtt_username", ""),
                mqttPassword = json.optString("mqtt_password", ""),
                topicPrefix = json.optString("topic_prefix", ""),
                qos = json.optInt("qos", 1)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching station config", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Simple health-check against the gateway API.
     *
     * @param apiKey The gateway API key.
     * @return true if the API reports a healthy status.
     */
    fun healthCheck(apiKey: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$BASE_URL/api/gateway/health")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Accept", "application/json")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Health check returned HTTP ${connection.responseCode}")
                return false
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(body)
            val status = json.optString("status", "")
            status.equals("ok", ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            false
        } finally {
            connection?.disconnect()
        }
    }
}
