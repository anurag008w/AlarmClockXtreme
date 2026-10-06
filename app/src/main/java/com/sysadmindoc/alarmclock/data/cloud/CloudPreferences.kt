package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

class CloudPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    fun getToken(): String = prefs.getString("token", "") ?: ""
    fun getEmail(): String = prefs.getString("email", "") ?: ""
    fun isLoggedIn(): Boolean = getToken().isNotBlank()

    fun saveSession(token: String, email: String) {
        val owner=prefs.getString("bound_email",prefs.getString("email","")) ?: ""
        require(owner.isBlank() || owner.equals(email,ignoreCase=true)) { "device_cloud_account_mismatch_use_separate_android_profile" }
        check(prefs.edit().putString("bound_email",email.lowercase()).putString("token", token).putString("email", email).commit())
    }

    fun clearSession() {
        prefs.edit()
            .remove("token")
            .remove("email")
            .remove("cursor")
            .remove("alarm_mapping")
            .remove("alarm_snapshots")
            .remove("alarm_versions")
            .remove("settings_snapshot")
            .remove("settings_version")
            .apply()
    }

    fun getCursor(): String =
        prefs.getString("cursor", "1970-01-01T00:00:00.000Z")
            ?: "1970-01-01T00:00:00.000Z"

    fun setCursor(cursor: String) {
        prefs.edit().putString("cursor", cursor).commit()
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

    fun saveAlarmMetadata(mapping: Map<String, Long>, snapshots: Map<String, String>, versions: Map<String, Long>) {
        val mapJson = JSONObject().apply { mapping.forEach { (key,value) -> put(key,value) } }
        val snapshotJson = JSONObject().apply { snapshots.forEach { (key,value) -> put(key,value) } }
        val versionJson = JSONObject().apply { versions.forEach { (key,value) -> put(key,value) } }
        check(prefs.edit()
            .putString("alarm_mapping",mapJson.toString())
            .putString("alarm_snapshots",snapshotJson.toString())
            .putString("alarm_versions",versionJson.toString())
            .commit()) { "alarm_sync_metadata_write_failed" }
    }

    fun getSettingsSnapshot(): String? = prefs.getString("settings_snapshot", null)
    fun getSettingsVersion(): Long = prefs.getLong("settings_version", 0L)
    fun saveSettingsMetadata(snapshot: String, version: Long) {
        check(prefs.edit().putString("settings_snapshot", snapshot).putLong("settings_version", version).commit()) { "settings_sync_metadata_write_failed" }
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

    fun getSnapshots(): Map<String, String> {
        val raw = prefs.getString("alarm_snapshots", "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it, "") }
        }.getOrDefault(emptyMap())
    }

    fun setSnapshots(snapshots: Map<String, String>) {
        val json = JSONObject()
        snapshots.forEach { (remoteId, snapshot) -> json.put(remoteId, snapshot) }
        prefs.edit().putString("alarm_snapshots", json.toString()).apply()
    }

    fun getVersions(): Map<String, Long> {
        val raw = prefs.getString("alarm_versions", "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optLong(it, 0L) }
        }.getOrDefault(emptyMap())
    }

    fun setVersions(versions: Map<String, Long>) {
        val json = JSONObject()
        versions.forEach { (remoteId, version) -> json.put(remoteId, version) }
        prefs.edit().putString("alarm_versions", json.toString()).apply()
    }
}