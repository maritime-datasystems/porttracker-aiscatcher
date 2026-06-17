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
        private const val DB_VERSION = 1
        const val TABLE = "vessel_static"

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
        Log.i(TAG, "Vessel cache DB created (v$DB_VERSION)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 — nothing to migrate yet. Additive migrations go here later.
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
}

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
