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

    @POST("api/ai/command")
    suspend fun aiCommand(
        @Header("Authorization") authorization: String,
        @Body request: CloudAiRequest
    ): CloudAiResponse
}