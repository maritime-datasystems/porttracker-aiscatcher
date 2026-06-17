package com.porttracker.ais

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Polls the native engine's decoded ship list (`/api/ships.json` on the internal
 * web port) and upserts static vessel data into [VesselDatabase].
 *
 * This consumes the engine's already-decoded JSON (name/imo/callsign/dimensions
 * cleaned by AIS-catcher), so no AIS bit-parsing happens here. Active only while
 * the "Write to internal DB" setting (`internal_db_enabled`) is on.
 */
class VesselCacheWriter(
    context: Context,
    private val internalApiPort: Int
) {
    companion object {
        private const val TAG = "porttracker-service.DBWriter"
        private const val POLL_INTERVAL_SEC = 30L

        // Status surface for the UI / health endpoint.
        @Volatile var isRunning = false
            private set
        @Volatile var lastPollMs = 0L
            private set
        @Volatile var lastPollVessels = 0
            private set
        @Volatile var totalUpserts = 0L
            private set
    }

    private val appContext = context.applicationContext
    private val db = VesselDatabase.getInstance(appContext)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "VesselCacheWriter").apply { isDaemon = true }
        }
        executor?.scheduleWithFixedDelay({ safePoll() }, 5, POLL_INTERVAL_SEC, TimeUnit.SECONDS)
        isRunning = true
        Log.i(TAG, "Vessel cache writer started (poll every ${POLL_INTERVAL_SEC}s)")
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        isRunning = false
        Log.i(TAG, "Vessel cache writer stopped")
    }

    private fun safePoll() {
        try {
            poll()
        } catch (e: Exception) {
            // Engine web server may not be up yet (web-only mode / restart) — retry next cycle.
            Log.d(TAG, "poll skipped: ${e.message}")
        }
    }

    private fun poll() {
        val body = fetch("http://127.0.0.1:$internalApiPort/api/ships.json")
        val root = JSONObject(body)
        val ships = root.optJSONArray("ships") ?: return

        val records = ArrayList<VesselRecord>(ships.length())
        for (i in 0 until ships.length()) {
            val s = ships.optJSONObject(i) ?: continue
            val mmsi = s.optLong("mmsi", 0L)
            if (mmsi <= 0L) continue
            records.add(
                VesselRecord(
                    mmsi = mmsi,
                    imo = s.optInt("imo", 0).takeIf { it > 0 },
                    name = s.optString("shipname", "").cleaned(),
                    callsign = s.optString("callsign", "").cleaned(),
                    shipType = s.optInt("shiptype", 0).takeIf { it > 0 },
                    toBow = s.optInt("to_bow", -1).takeIf { it >= 0 },
                    toStern = s.optInt("to_stern", -1).takeIf { it >= 0 },
                    toPort = s.optInt("to_port", -1).takeIf { it >= 0 },
                    toStarboard = s.optInt("to_starboard", -1).takeIf { it >= 0 },
                    destination = s.optString("destination", "").cleaned(),
                    draught = s.optDouble("draught", 0.0).takeIf { it > 0.0 },
                    eta = formatEta(s),
                    country = s.optString("country", "").cleaned()
                )
            )
        }

        db.upsertAll(records, System.currentTimeMillis())
        lastPollMs = System.currentTimeMillis()
        lastPollVessels = records.size
        totalUpserts += records.size
        Log.d(TAG, "polled ${records.size} vessels; db now has ${db.count()} rows")
    }

    private fun fetch(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 6000
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** AIS eta is broadcast as month/day/hour/minute; compose a compact string. */
    private fun formatEta(s: JSONObject): String? {
        val mo = s.optInt("eta_month", 0)
        val day = s.optInt("eta_day", 0)
        val hr = s.optInt("eta_hour", 24)
        val min = s.optInt("eta_minute", 60)
        if (mo == 0 && day == 0) return null
        return String.format("%02d-%02d %02d:%02d", mo, day, hr % 24, min % 60)
    }

    /** Engine returns "" for unknown strings — normalise to null. */
    private fun String.cleaned(): String? = trim().ifEmpty { null }
}
