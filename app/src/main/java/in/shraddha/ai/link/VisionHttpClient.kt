package `in`.shraddha.ai.link

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal const val VISION_HTTP_CALL_TIMEOUT_SECONDS = 50L

/** A stalled vision request must release camera analysis instead of waiting for OkHttp's default timeout. */
internal fun buildVisionHttpClient(base: OkHttpClient): OkHttpClient =
    base.newBuilder()
        .callTimeout(VISION_HTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
