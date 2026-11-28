package `in`.shraddha.ai.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shraddha.ai.future.OnlineAiConsent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLinkManagerConsentInstrumentedTest {
    @Test
    fun everyLegacyCloudAiEntryPointFailsBeforeNetworkWhenConsentIsOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = OnlineAiConsent.isAllowed(context)
        DeviceLinkManager.initialize(context)
        try {
            assertTrue(OnlineAiConsent.setAllowed(context, false))

            fun expectBlocked(name: String, request: ((Result<String>) -> Unit) -> Unit) {
                var result: Result<String>? = null
                request { result = it }
                val failure = result?.exceptionOrNull()
                assertNotNull("$name must fail synchronously while consent is off", failure)
                assertTrue(
                    "$name must be rejected by the global consent gate",
                    failure?.message.orEmpty().contains("Online AI permission is off")
                )
            }

            expectBlocked("legacy chat") {
                DeviceLinkManager.askChat("https://example.invalid", "token", "GEMINI", "private prompt", it)
            }
            expectBlocked("provider status") {
                DeviceLinkManager.providerStatus("https://example.invalid", "token", it)
            }
            expectBlocked("summarize") {
                DeviceLinkManager.summarize("https://example.invalid", "token", "gemini", "private text", it)
            }
            expectBlocked("task planner") {
                DeviceLinkManager.planTask("https://example.invalid", "token", "gemini", "private goal", it)
            }
            expectBlocked("studio work") {
                DeviceLinkManager.studioWork("https://example.invalid", "token", "gemini", "general", "create", "private request", it)
            }
            expectBlocked("expert assist") {
                DeviceLinkManager.expertAssist("https://example.invalid", "token", "gemini", "general", "learn", "private question", it)
            }
            expectBlocked("provider comparison") {
                DeviceLinkManager.compareProviders("https://example.invalid", "token", listOf("gemini", "openai"), "private prompt", it)
            }
            expectBlocked("visual analysis") {
                DeviceLinkManager.analyzeScreen("https://example.invalid", "token", "gemini", "describe", byteArrayOf(1), done = it)
            }
        } finally {
            OnlineAiConsent.setAllowed(context, previous)
            DeviceLinkManager.cancelAiRequests()
        }
    }
}
