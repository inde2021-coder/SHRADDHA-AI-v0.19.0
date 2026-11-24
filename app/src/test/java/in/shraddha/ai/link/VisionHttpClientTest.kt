package `in`.shraddha.ai.link

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class VisionHttpClientTest {
    @Test
    fun visionClientBoundsTotalCallTimeWithoutChangingSharedRelayClient() {
        val shared = OkHttpClient.Builder().build()
        val vision = buildVisionHttpClient(shared)

        assertEquals(0, shared.callTimeoutMillis)
        assertEquals(50_000, vision.callTimeoutMillis)
        assertEquals(shared.readTimeoutMillis, vision.readTimeoutMillis)
    }
}
