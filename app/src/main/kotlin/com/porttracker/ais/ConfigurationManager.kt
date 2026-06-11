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
            // Read existing types FIRST so we can honour them and avoid stomping a Boolean
            // pref with a String when the JSON sends 0/1 instead of false/true.
            val existing = prefs.all
            val iterator = jsonObject.keys()

            while (iterator.hasNext()) {
                val key = iterator.next()
                val jsonValue = jsonObject.get(key)
                val existingValue = existing[key]

                when {
                    // Existing pref is Boolean — coerce any incoming type to Boolean
                    existingValue is Boolean -> editor.putBoolean(key, when (jsonValue) {
                        is Boolean -> jsonValue
                        is Int     -> jsonValue != 0
                        is String  -> jsonValue.equals("true", ignoreCase = true)
                        else       -> false
                    })
                    // Existing pref is String — always store as String
                    existingValue is String -> editor.putString(key, jsonValue.toString())
                    // Existing pref is Int
                    existingValue is Int -> editor.putInt(key, jsonValue.toString().toIntOrNull() ?: 0)
                    // No existing pref — infer storage type from JSON type
                    jsonValue is Boolean -> editor.putBoolean(key, jsonValue)
                    jsonValue is Int     -> editor.putString(key, jsonValue.toString())
                    jsonValue is Double  -> editor.putString(key, jsonValue.toString())
                    jsonValue is String  -> editor.putString(key, jsonValue)
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
