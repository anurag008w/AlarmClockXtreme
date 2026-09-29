package com.sysadmindoc.alarmclock.data.cloud

import com.squareup.moshi.Json

data class CloudAuthRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

data class CloudUser(
    @Json(name = "id") val id: String,
    @Json(name = "email") val email: String,
    @Json(name = "createdAt") val createdAt: String? = null
)

data class CloudAuthResponse(
    @Json(name = "token") val token: String,
    @Json(name = "user") val user: CloudUser
)

data class CloudMeResponse(
    @Json(name = "user") val user: CloudUser
)

data class CloudAlarmDto(
    @Json(name = "id") val id: String,
    @Json(name = "payload") val payload: Map<String, Any?>,
    @Json(name = "version") val version: Long = 1,
    @Json(name = "updatedAt") val updatedAt: String,
    @Json(name = "deletedAt") val deletedAt: String? = null
)

data class CloudAlarmListResponse(
    @Json(name = "alarms") val alarms: List<CloudAlarmDto>,
    @Json(name = "cursor") val cursor: String
)

data class CloudAlarmWriteRequest(
    @Json(name = "payload") val payload: Map<String, Any?>,
    @Json(name = "expectedVersion") val expectedVersion: Long = 0
)

data class CloudAlarmWriteResponse(
    @Json(name = "id") val id: String,
    @Json(name = "payload") val payload: Map<String, Any?>,
    @Json(name = "version") val version: Long,
    @Json(name = "updatedAt") val updatedAt: String,
    @Json(name = "deletedAt") val deletedAt: String? = null
)

data class CloudDeleteResponse(
    @Json(name = "id") val id: String,
    @Json(name = "version") val version: Long,
    @Json(name = "updatedAt") val updatedAt: String,
    @Json(name = "deletedAt") val deletedAt: String
)

data class CloudDeviceRequest(
    @Json(name = "deviceId") val deviceId: String,
    @Json(name = "platform") val platform: String = "android",
    @Json(name = "appVersion") val appVersion: String = "",
    @Json(name = "pushToken") val pushToken: String = ""
)

data class CloudAiRequest(
    @Json(name = "command") val command: String
)

data class CloudAiResponse(
    @Json(name = "mode") val mode: String,
    @Json(name = "message") val message: String? = null,
    @Json(name = "executed") val executed: List<Map<String, Any?>>? = null
)