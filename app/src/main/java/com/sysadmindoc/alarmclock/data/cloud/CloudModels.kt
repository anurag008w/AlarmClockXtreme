package com.sysadmindoc.alarmclock.data.cloud

import com.squareup.moshi.Json

data class CloudAuthRequest(
    @field:Json(name = "email") val email: String,
    @field:Json(name = "password") val password: String
)

data class CloudUser(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "email") val email: String,
    @field:Json(name = "createdAt") val createdAt: String? = null
)

data class CloudAuthResponse(
    @field:Json(name = "token") val token: String,
    @field:Json(name = "user") val user: CloudUser
)

data class CloudMeResponse(
    @field:Json(name = "user") val user: CloudUser
)

data class CloudAlarmDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "payload") val payload: Map<String, Any?>,
    @field:Json(name = "version") val version: Long = 1,
    @field:Json(name = "updatedAt") val updatedAt: String,
    @field:Json(name = "deletedAt") val deletedAt: String? = null
)

data class CloudAlarmListResponse(
    @field:Json(name = "alarms") val alarms: List<CloudAlarmDto>,
    @field:Json(name = "cursor") val cursor: String
)

data class CloudAlarmWriteRequest(
    @field:Json(name = "payload") val payload: Map<String, Any?>,
    @field:Json(name = "expectedVersion") val expectedVersion: Long = 0
)

data class CloudAlarmWriteResponse(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "payload") val payload: Map<String, Any?>,
    @field:Json(name = "version") val version: Long,
    @field:Json(name = "updatedAt") val updatedAt: String,
    @field:Json(name = "deletedAt") val deletedAt: String? = null
)

data class CloudDeleteResponse(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "version") val version: Long,
    @field:Json(name = "updatedAt") val updatedAt: String,
    @field:Json(name = "deletedAt") val deletedAt: String
)

data class CloudDeviceRequest(
    @field:Json(name = "deviceId") val deviceId: String,
    @field:Json(name = "platform") val platform: String = "android",
    @field:Json(name = "appVersion") val appVersion: String = "",
    @field:Json(name = "pushToken") val pushToken: String = ""
)

data class CloudAiRequest(
    @field:Json(name = "command") val command: String
)

data class CloudAiResponse(
    @field:Json(name = "mode") val mode: String,
    @field:Json(name = "message") val message: String? = null,
    @field:Json(name = "executed") val executed: List<Map<String, Any?>>? = null
)