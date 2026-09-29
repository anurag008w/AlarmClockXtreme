package com.sysadmindoc.alarmclock.data.cloud

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CloudAuthRequest(
    val email: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class CloudUser(
    val id: String,
    val email: String,
    val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CloudAuthResponse(
    val token: String,
    val user: CloudUser
)

@JsonClass(generateAdapter = true)
data class CloudMeResponse(
    val user: CloudUser
)

@JsonClass(generateAdapter = true)
data class CloudAlarmDto(
    val id: String,
    val payload: Map<String, Any?>,
    val version: Long = 1,
    val updatedAt: String,
    val deletedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CloudAlarmListResponse(
    val alarms: List<CloudAlarmDto>,
    val cursor: String
)

@JsonClass(generateAdapter = true)
data class CloudAlarmWriteRequest(
    val payload: Map<String, Any?>,
    val expectedVersion: Long = 0
)

@JsonClass(generateAdapter = true)
data class CloudAlarmWriteResponse(
    val id: String,
    val payload: Map<String, Any?>,
    val version: Long,
    val updatedAt: String,
    val deletedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CloudDeleteResponse(
    val id: String,
    val version: Long,
    val updatedAt: String,
    val deletedAt: String
)

@JsonClass(generateAdapter = true)
data class CloudDeviceRequest(
    val deviceId: String,
    val platform: String = "android",
    val appVersion: String = "",
    val pushToken: String = ""
)

@JsonClass(generateAdapter = true)
data class CloudAiRequest(
    val command: String
)

@JsonClass(generateAdapter = true)
data class CloudAiResponse(
    val mode: String,
    val message: String? = null,
    val executed: List<Map<String, Any?>>? = null
)