package com.sysadmindoc.alarmclock.data.cloud

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Path

// Retrofit rejects Kotlin-generated wildcard types in request bodies.
@JvmSuppressWildcards
interface CloudApi {

    @POST("api/auth/login")
    suspend fun login(@Body request: CloudAuthRequest): CloudAuthResponse

    @POST("api/auth/register")
    suspend fun register(@Body request: CloudAuthRequest): CloudAuthResponse

    @GET("api/me")
    suspend fun me(@Header("Authorization") authorization: String): CloudMeResponse

    @POST("api/devices/register")
    suspend fun registerDevice(
        @Header("Authorization") authorization: String,
        @Body request: CloudDeviceRequest
    ): Map<String, Any?>

    @GET("api/alarms")
    suspend fun getAlarms(
        @Header("Authorization") authorization: String,
        @Query("since") since: String
    ): CloudAlarmListResponse

    @PUT("api/alarms/{id}")
    suspend fun putAlarm(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body request: CloudAlarmWriteRequest
    ): CloudAlarmWriteResponse

    @DELETE("api/alarms/{id}")
    suspend fun deleteAlarm(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Query("expectedVersion") expectedVersion: Long = 0
    ): CloudDeleteResponse

    @GET("api/settings")
    suspend fun getSettings(@Header("Authorization") authorization: String): CloudSettingsResponse

    @PUT("api/settings")
    suspend fun putSettings(
        @Header("Authorization") authorization: String,
        @Body request: CloudAlarmWriteRequest
    ): CloudSettingsResponse

    @GET("api/utilities/{deviceId}")
    suspend fun getUtilities(@Header("Authorization") authorization: String, @Path("deviceId") deviceId: String): CloudUtilitiesResponse

    @PUT("api/utilities/{deviceId}/snapshot")
    suspend fun putUtilitySnapshot(@Header("Authorization") authorization: String, @Path("deviceId") deviceId: String, @Body body: Map<String, Any?>): Map<String, Any?>

    @POST("api/utilities/{deviceId}/ack")
    suspend fun acknowledgeUtility(@Header("Authorization") authorization: String, @Path("deviceId") deviceId: String, @Body body: Map<String, Any?>): Map<String, Any?>

    @PUT("api/dashboard/{deviceId}")
    suspend fun putDashboardSnapshot(@Header("Authorization") authorization: String, @Path("deviceId") deviceId: String, @Body body: Map<String, Any?>): Map<String, Any?>

    @POST("api/ai/command")
    suspend fun aiCommand(
        @Header("Authorization") authorization: String,
        @Body request: CloudAiRequest
    ): CloudAiResponse
}
