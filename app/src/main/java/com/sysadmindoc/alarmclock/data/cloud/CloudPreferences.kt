package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

class CloudPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    fun getToken(): String = prefs.getString("token", "") ?: ""
    fun getEmail(): String = prefs.getString("email", "") ?: ""
    fun isLoggedIn(): Boolean = getToken().isNotBlank()

    fun saveSession(token: String, email: String) {
        prefs.edit().putString("token", token).putString("email", email).apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove("token")
            .remove("email")
            .remove("cursor")
            .remove("alarm_mapping")
            .apply()
    }

    fun getCursor(): String =
        prefs.getString("cursor", "1970-01-01T00:00:00.000Z")
            ?: "1970-01-01T00:00:00.000Z"

    fun setCursor(cursor: String) {
        prefs.edit().putString("cursor", cursor).apply()
    }

    fun getDeviceId(): String {
        val existing = prefs.getString("device_id", "")
        if (!existing.isNullOrBlank()) return existing
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val id = androidId ?: UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    fun getMapping(): Map<String, Long> {
        val raw = prefs.getString("alarm_mapping", "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optLong(it) }
        }.getOrDefault(emptyMap())
    }

    fun setMapping(mapping: Map<String, Long>) {
        val json = JSONObject()
        mapping.forEach { (remoteId, localId) -> json.put(remoteId, localId) }
        prefs.edit().putString("alarm_mapping", json.toString()).apply()
    }
}