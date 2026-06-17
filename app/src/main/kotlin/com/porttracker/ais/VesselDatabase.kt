package com.porttracker.ais

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent SQLite store for static vessel data.
 *
 * Keyed by MMSI (the only identifier present in every AIS message — IMO is
 * Type-5-only and often absent, so it is an indexed attribute, not the key).
 * Survives app restarts, giving immediate vessel identification before the next
 * static frame arrives.
 *
 * Fed by [VesselCacheWriter], which polls the engine's decoded ship list. Reads
 * are served to the admin "DB" page via [AdminWebServer].
 */
class VesselDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "porttracker-service.DB"
        private const val DB_NAME = "vessel_cache.db"
        private const val DB_VERSION = 2
        const val TABLE = "vessel_static"
        const val POS_TABLE = "vessel_position"

        @Volatile private var instance: VesselDatabase? = null
        fun getInstance(context: Context): VesselDatabase =
            instance ?: synchronized(this) {
                instance ?: VesselDatabase(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                mmsi               INTEGER PRIMARY KEY,
                imo                INTEGER,
                name               TEXT,
                callsign           TEXT,
                ship_type          INTEGER,
                to_bow             INTEGER,
                to_stern           INTEGER,
                to_port            INTEGER,
                to_starboard       INTEGER,
                destination        TEXT,
                draught            REAL,
                eta                TEXT,
                country            TEXT,
                first_seen         INTEGER NOT NULL,
                last_static_update INTEGER NOT NULL,
                last_seen          INTEGER,
                msg_count          INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_vessel_imo ON $TABLE(imo)")
        db.execSQL("CREATE INDEX idx_vessel_name ON $TABLE(name)")
        createPositionTable(db)
        Log.i(TAG, "Vessel cache DB created (v$DB_VERSION)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL: concurrent reads (track/stats APIs) while the writer inserts.
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createPositionTable(db)
            Log.i(TAG, "Migrated vessel DB to v2 (position history)")
        }
    }

    private fun createPositionTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $POS_TABLE (
                mmsi        INTEGER NOT NULL,
                ts          INTEGER NOT NULL,
                lat         REAL NOT NULL,
                lon         REAL NOT NULL,
                sog         REAL,
                cog         REAL,
                heading     INTEGER,
                draught     REAL,
                nav_status  INTEGER,
                resolution  INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (mmsi, ts)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pos_ts ON $POS_TABLE(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pos_mmsi_ts ON $POS_TABLE(mmsi, ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pos_res_ts ON $POS_TABLE(resolution, ts)")
    }

    /**
     * Upsert a batch of vessels in one transaction.
     *
     * Partial-update rules:
     *  - only overwrite a column when the incoming value is non-null/non-empty
     *    (never erase a known name because a later frame omitted it),
     *  - never downgrade a known IMO to null,
     *  - bump last_static_update only when a static field actually changed.
     */
    fun upsertAll(vessels: List<VesselRecord>, nowMs: Long) {
        if (vessels.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (v in vessels) upsertOne(db, v, nowMs)
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "upsertAll failed", e)
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertOne(db: SQLiteDatabase, v: VesselRecord, nowMs: Long) {
        val existing = db.query(
            TABLE, null, "mmsi = ?", arrayOf(v.mmsi.toString()), null, null, null
        )
        existing.use { c ->
            if (!c.moveToFirst()) {
                // New vessel.
                val cv = ContentValues().apply {
                    put("mmsi", v.mmsi)
                    v.imo?.let { put("imo", it) }
                    v.name?.let { put("name", it) }
                    v.callsign?.let { put("callsign", it) }
                    v.shipType?.let { put("ship_type", it) }
                    v.toBow?.let { put("to_bow", it) }
                    v.toStern?.let { put("to_stern", it) }
                    v.toPort?.let { put("to_port", it) }
                    v.toStarboard?.let { put("to_starboard", it) }
                    v.destination?.let { put("destination", it) }
                    v.draught?.let { put("draught", it) }
                    v.eta?.let { put("eta", it) }
                    v.country?.let { put("country", it) }
                    put("first_seen", nowMs)
                    put("last_static_update", nowMs)
                    put("last_seen", nowMs)
                    put("msg_count", 1)
                }
                db.insert(TABLE, null, cv)
                return
            }

            // Existing vessel — merge, keeping known values when incoming is null.
            fun str(col: String) = c.getString(c.getColumnIndexOrThrow(col))
            fun int(col: String) = if (c.isNull(c.getColumnIndexOrThrow(col))) null else c.getInt(c.getColumnIndexOrThrow(col))
            fun dbl(col: String) = if (c.isNull(c.getColumnIndexOrThrow(col))) null else c.getDouble(c.getColumnIndexOrThrow(col))

            val mImo = v.imo ?: int("imo")
            val mName = v.name ?: str("name")
            val mCall = v.callsign ?: str("callsign")
            val mType = v.shipType ?: int("ship_type")
            val mBow = v.toBow ?: int("to_bow")
            val mStern = v.toStern ?: int("to_stern")
            val mPort = v.toPort ?: int("to_port")
            val mStar = v.toStarboard ?: int("to_starboard")
            val mDest = v.destination ?: str("destination")
            val mDraught = v.draught ?: dbl("draught")
            val mEta = v.eta ?: str("eta")
            val mCountry = v.country ?: str("country")

            val staticChanged =
                mImo != int("imo") || mName != str("name") || mCall != str("callsign") ||
                mType != int("ship_type") || mDest != str("destination") ||
                mDraught != dbl("draught") || mEta != str("eta")

            val staticUpdate =
                if (staticChanged) nowMs else c.getLong(c.getColumnIndexOrThrow("last_static_update"))
            val msgCount = c.getLong(c.getColumnIndexOrThrow("msg_count")) + 1

            val cv = ContentValues().apply {
                put("imo", mImo); put("name", mName); put("callsign", mCall)
                put("ship_type", mType); put("to_bow", mBow); put("to_stern", mStern)
                put("to_port", mPort); put("to_starboard", mStar); put("destination", mDest)
                put("draught", mDraught); put("eta", mEta); put("country", mCountry)
                put("last_seen", nowMs); put("last_static_update", staticUpdate)
                put("msg_count", msgCount)
            }
            db.update(TABLE, cv, "mmsi = ?", arrayOf(v.mmsi.toString()))
        }
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Query rows as a JSON array (newest activity first). Optional case-insensitive
     * search across name / callsign / mmsi / imo / destination.
     */
    fun queryJson(limit: Int, offset: Int, q: String?): JSONArray {
        val args = ArrayList<String>()
        var where = ""
        if (!q.isNullOrBlank()) {
            val like = "%${q.trim()}%"
            where = "WHERE name LIKE ? OR callsign LIKE ? OR destination LIKE ? " +
                    "OR CAST(mmsi AS TEXT) LIKE ? OR CAST(imo AS TEXT) LIKE ?"
            repeat(5) { args.add(like) }
        }
        val sql = "SELECT * FROM $TABLE $where ORDER BY last_seen DESC LIMIT ? OFFSET ?"
        args.add(limit.toString()); args.add(offset.toString())

        val out = JSONArray()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.put(rowToJson(c))
        }
        return out
    }

    /** Full stored record for a single vessel, or null if not in the cache. */
    fun queryByMmsi(mmsi: Long): JSONObject? {
        readableDatabase.rawQuery("SELECT * FROM $TABLE WHERE mmsi = ?", arrayOf(mmsi.toString())).use { c ->
            return if (c.moveToFirst()) rowToJson(c) else null
        }
    }

    private fun rowToJson(c: android.database.Cursor): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            if (c.isNull(i)) { o.put(c.getColumnName(i), JSONObject.NULL); continue }
            when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                else -> o.put(c.getColumnName(i), c.getString(i))
            }
        }
        return o
    }

    /** Export the whole table as CSV. */
    fun exportCsv(): String {
        val sb = StringBuilder()
        readableDatabase.rawQuery("SELECT * FROM $TABLE ORDER BY mmsi", null).use { c ->
            val cols = c.columnNames
            sb.append(cols.joinToString(",")).append("\n")
            while (c.moveToNext()) {
                sb.append(cols.indices.joinToString(",") { i ->
                    if (c.isNull(i)) "" else csvField(c.getString(i))
                }).append("\n")
            }
        }
        return sb.toString()
    }

    private fun csvField(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---- Position history ----

    /** Batch-insert raw position points. PK (mmsi, ts) collisions are ignored. */
    fun insertPositions(points: List<PositionRecord>) {
        if (points.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in points) {
                val cv = ContentValues().apply {
                    put("mmsi", p.mmsi); put("ts", p.ts)
                    put("lat", p.lat); put("lon", p.lon)
                    p.sog?.let { put("sog", it) }
                    p.cog?.let { put("cog", it) }
                    p.heading?.let { put("heading", it) }
                    p.draught?.let { put("draught", it) }
                    p.navStatus?.let { put("nav_status", it) }
                    put("resolution", 0)
                }
                db.insertWithOnConflict(POS_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "insertPositions failed", e)
        } finally {
            db.endTransaction()
        }
    }

    /** Row counts per resolution + total + time span, for the stats endpoint. */
    fun positionStats(): JSONObject {
        val o = JSONObject()
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(ts), MAX(ts), " +
                "SUM(resolution=0), SUM(resolution=1), SUM(resolution=2) FROM $POS_TABLE", null
        ).use { c ->
            if (c.moveToFirst()) {
                o.put("total", c.getLong(0))
                o.put("oldest_ts", if (c.isNull(1)) JSONObject.NULL else c.getLong(1))
                o.put("newest_ts", if (c.isNull(2)) JSONObject.NULL else c.getLong(2))
                o.put("raw", c.getLong(3))
                o.put("hourly", c.getLong(4))
                o.put("daily", c.getLong(5))
            }
        }
        o.put("distinct_vessels", run {
            readableDatabase.rawQuery("SELECT COUNT(DISTINCT mmsi) FROM $POS_TABLE", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        })
        return o
    }

    /** Track points for one vessel, oldest→newest, optionally filtered by time/resolution. */
    fun queryTrack(mmsi: Long, from: Long?, to: Long?, res: Int?, limit: Int): JSONArray {
        val where = StringBuilder("mmsi = ?")
        val args = ArrayList<String>(); args.add(mmsi.toString())
        if (from != null) { where.append(" AND ts >= ?"); args.add(from.toString()) }
        if (to != null) { where.append(" AND ts <= ?"); args.add(to.toString()) }
        if (res != null) { where.append(" AND resolution = ?"); args.add(res.toString()) }
        val sql = "SELECT ts,lat,lon,sog,cog,heading,draught,nav_status,resolution FROM $POS_TABLE " +
            "WHERE $where ORDER BY ts ASC LIMIT ?"
        args.add(limit.toString())

        val out = JSONArray()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.put(rowToJson(c))
        }
        return out
    }

    /**
     * Tiered downsampling + retention.
     *  - raw (res 0) older than [rawRetentionMs]  → keep last point per (mmsi, hour) as hourly (res 1), delete the rest.
     *  - hourly (res 1) older than [hourlyRetentionMs] → keep last per (mmsi, day) as daily (res 2), delete the rest.
     *
     * Decimation keeps a real reported position (the last in the bucket), no
     * synthetic averaging. Works without SQLite window functions (minSdk 23).
     * Returns post-run [positionStats].
     */
    fun runMaintenance(nowMs: Long, rawRetentionMs: Long, hourlyRetentionMs: Long): JSONObject {
        val db = writableDatabase
        val rawCutoff = nowMs - rawRetentionMs
        val hourlyCutoff = nowMs - hourlyRetentionMs
        val hourMs = 3_600_000L
        val dayMs = 86_400_000L
        db.beginTransaction()
        try {
            // raw → hourly: promote the last point per (mmsi, hour) past the cutoff.
            db.execSQL(
                "UPDATE $POS_TABLE SET resolution=1 WHERE resolution=0 AND ts < ? " +
                    "AND ts = (SELECT MAX(ts) FROM $POS_TABLE v2 WHERE v2.mmsi=$POS_TABLE.mmsi " +
                    "AND v2.resolution=0 AND v2.ts < ? AND v2.ts/$hourMs = $POS_TABLE.ts/$hourMs)",
                arrayOf<Any>(rawCutoff, rawCutoff)
            )
            db.execSQL("DELETE FROM $POS_TABLE WHERE resolution=0 AND ts < ?", arrayOf<Any>(rawCutoff))

            // hourly → daily: promote the last point per (mmsi, day) past the cutoff.
            db.execSQL(
                "UPDATE $POS_TABLE SET resolution=2 WHERE resolution=1 AND ts < ? " +
                    "AND ts = (SELECT MAX(ts) FROM $POS_TABLE v2 WHERE v2.mmsi=$POS_TABLE.mmsi " +
                    "AND v2.resolution=1 AND v2.ts < ? AND v2.ts/$dayMs = $POS_TABLE.ts/$dayMs)",
                arrayOf<Any>(hourlyCutoff, hourlyCutoff)
            )
            db.execSQL("DELETE FROM $POS_TABLE WHERE resolution=1 AND ts < ?", arrayOf<Any>(hourlyCutoff))

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "runMaintenance failed", e)
        } finally {
            db.endTransaction()
        }
        return positionStats()
    }
}

/** A single dynamic position report to persist. */
data class PositionRecord(
    val mmsi: Long,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val sog: Double? = null,
    val cog: Double? = null,
    val heading: Int? = null,
    val draught: Double? = null,
    val navStatus: Int? = null
)

/** Static fields parsed from a decoded vessel; nulls mean "not known from this source". */
data class VesselRecord(
    val mmsi: Long,
    val imo: Int? = null,
    val name: String? = null,
    val callsign: String? = null,
    val shipType: Int? = null,
    val toBow: Int? = null,
    val toStern: Int? = null,
    val toPort: Int? = null,
    val toStarboard: Int? = null,
    val destination: String? = null,
    val draught: Double? = null,
    val eta: String? = null,
    val country: String? = null
)
