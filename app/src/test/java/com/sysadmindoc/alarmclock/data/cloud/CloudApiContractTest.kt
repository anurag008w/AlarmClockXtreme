package com.sysadmindoc.alarmclock.data.cloud

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Uses real Retrofit parsing, not a mocked CloudApi, to catch JVM signature bugs. */
class CloudApiContractTest {
    @Test
    fun allEndpointsParseAndMapBodiesSerializeWithoutWildcards() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            bodies += buffer.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://contract.invalid/")
            .client(client).validateEagerly(true)
            .addConverterFactory(MoshiConverterFactory.create(
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            )).build().create(CloudApi::class.java)
        val body: Map<String, Any?> = mapOf("payload" to mapOf("seconds" to 180, "label" to "Contract test"))
        api.putUtilitySnapshot("Bearer test", "device", body)
        api.acknowledgeUtility("Bearer test", "device", body)
        api.putDashboardSnapshot("Bearer test", "device", body)
        assertEquals(listOf("/api/utilities/device/snapshot", "/api/utilities/device/ack", "/api/dashboard/device"), paths)
        assertEquals(3, bodies.size)
        bodies.forEach { assertEquals("{\"payload\":{\"seconds\":180,\"label\":\"Contract test\"}}", it) }
    }
}
