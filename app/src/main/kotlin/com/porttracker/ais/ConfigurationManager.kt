package com.porttracker.ais

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject

class ConfigurationManager(private val context: Context) {

    private val prefs: SharedPreferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()

    fun getAllConfig(): JSONObject {
        val json = JSONObject()
        val allEntries = prefs.all

        for ((key, value) in allEntries) {
            when (value) {
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value)
                is String -> json.put(key, value)
                // Sets are trickier, ignoring for now or converting to array
                is Set<*> -> json.put(key, JSONArray(value))
            }
        }
        return json
    }

    fun updateConfig(jsonString: String): Boolean {
        return try {
            val jsonObject = JSONObject(jsonString)
            val editor = prefs.edit()
            val iterator = jsonObject.keys()

            while (iterator.hasNext()) {
                val key = iterator.next()
                val value = jsonObject.get(key)

                // Basic type inference based on JSON type
                // Note: robustness depends on matching SharedPreferences expected types
                // Ideally we check existing type, but for now we trust the admin input matches
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putString(key, value.toString()) // AIS-catcher tends to use Strings for numbers in some prefs
                    is Double -> editor.putString(key, value.toString()) // JSON uses Double for floats
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
