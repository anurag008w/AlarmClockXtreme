package com.sysadmindoc.alarmclock.data.cloud

data class CloudAuthRequest(
    val email: String,
    val password: String
)

data class CloudUser(
    val id: String,
    val email: String,
    val createdAt: String? = null
)

data class CloudAuthResponse(
    val token: String,
    val user: CloudUser
)

data class CloudMeResponse(
    val user: CloudUser
)

data class CloudAlarmDto(
    val id: String,
    val payload: Map<String, Any?>,
    val version: Long = 1,
    val updatedAt: String,
    val deletedAt: String? = null
)

data class CloudAlarmListResponse(
    val alarms: List<CloudAlarmDto>,
    val cursor: String
)

data class CloudAlarmWriteRequest(
    val payload: Map<String, Any?>,
    val expectedVersion: Long = 0
)

data class CloudAlarmWriteResponse(
    val id: String,
    val payload: Map<String, Any?>,
    val version: Long,
    val updatedAt: String,
    val deletedAt: String? = null
)

data class CloudDeleteResponse(
    val id: String,
    val version: Long,
    val updatedAt: String,
    val deletedAt: String
)

data class CloudDeviceRequest(
    val deviceId: String,
    val platform: String = "android",
    val appVersion: String = "",
    val pushToken: String = ""
)

data class CloudAiRequest(
    val command: String
)

data class CloudAiResponse(
    val mode: String,
    val message: String? = null,
    val executed: List<Map<String, Any?>>? = null
)