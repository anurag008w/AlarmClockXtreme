package com.sysadmindoc.alarmclock.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import com.sysadmindoc.alarmclock.ui.theme.SurfaceLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tiny thumbnail loader built on the OkHttp already in the app, so no new
 * dependency (and no new dependency-verification entries) is needed.
 *
 * Only https URLs are fetched, bodies over [MAX_BYTES] are rejected, and the
 * bitmap is down-sampled to roughly the size it is drawn at. Failures leave
 * the placeholder in place; a thumbnail is never worth an error state.
 */
private object ThumbnailLoader {
    const val MAX_BYTES = 2_000_000L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    val cache = object : LruCache<String, ImageBitmap>(48) {}

    fun load(url: String, targetPx: Int): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val request = Request.Builder().url(url).build()
        val bytes = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body
            if (body.contentLength() > MAX_BYTES) return null
            val source = body.source()
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) return null
            source.readByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val image = bitmap.asImageBitmap()
        cache.put(url, image)
        return image
    }
}

/** Square rounded thumbnail. Shows a flat placeholder until (or unless) the image loads. */
@Composable
fun RemoteThumbnail(
    url: String?,
    size: Dp = 72.dp,
    modifier: Modifier = Modifier,
) {
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { ThumbnailLoader.load(url, targetPx) }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceLight)
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
