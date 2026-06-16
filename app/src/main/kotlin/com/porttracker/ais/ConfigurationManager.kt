package com.porttracker.ais

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class ConfigurationManager(private val context: Context) {

    companion object {
        private const val TAG = "porttracker-service.Config"
    }

    private val prefs: SharedPreferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

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
                is Set<*> -> json.put(key, JSONArray(value))
                else -> Log.w(TAG, "getAllConfig: skipping key '$key' with unsupported type ${value?.javaClass}")
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
                    // Existing pref is Long
                    existingValue is Long -> editor.putLong(key, jsonValue.toString().toLongOrNull() ?: 0L)
                    // Existing pref is Float
                    existingValue is Float -> editor.putFloat(key, jsonValue.toString().toFloatOrNull() ?: 0f)
                    // Existing pref is Set<String>
                    existingValue is Set<*> -> {
                        val jsonArray = jsonObject.optJSONArray(key)
                        if (jsonArray != null) {
                            val set = mutableSetOf<String>()
                            for (i in 0 until jsonArray.length()) {
                                set.add(jsonArray.getString(i))
                            }
                            editor.putStringSet(key, set)
                        } else {
                            Log.w(TAG, "updateConfig: expected JSONArray for Set key '$key', got ${jsonValue.javaClass.simpleName}")
                        }
                    }
                    // No existing pref — infer storage type from JSON type
                    jsonValue is Boolean -> editor.putBoolean(key, jsonValue)
                    jsonValue is Int     -> editor.putString(key, jsonValue.toString())
                    jsonValue is Double  -> editor.putString(key, jsonValue.toString())
                    jsonValue is String  -> editor.putString(key, jsonValue)
                    else -> Log.w(TAG, "updateConfig: unhandled key '$key' with type ${jsonValue.javaClass.simpleName}")
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update config", e)
            false
        }
    }
}
