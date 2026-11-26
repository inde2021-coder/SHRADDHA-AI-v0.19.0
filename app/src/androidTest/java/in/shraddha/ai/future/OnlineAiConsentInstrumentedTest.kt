package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineAiConsentInstrumentedTest {
    @Test
    fun consentDefaultsOffAndPersistsRevocationSynchronously() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("shraddha_network_ai", Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("allow_online_ai", false)
        try {
            prefs.edit().remove("allow_online_ai").commit()
            assertFalse(OnlineAiConsent.isAllowed(context))
            try {
                OnlineAiConsent.requireAllowed(context)
                throw AssertionError("Cloud requests must be rejected while consent is off")
            } catch (_: IllegalStateException) {
                // Expected fail-closed behavior.
            }

            assertTrue(OnlineAiConsent.setAllowed(context, true))
            assertTrue(OnlineAiConsent.isAllowed(context))
            OnlineAiConsent.requireAllowed(context)
            assertTrue(prefs.getBoolean("allow_online_ai", false))

            assertTrue(OnlineAiConsent.setAllowed(context, false))
            assertFalse(OnlineAiConsent.isAllowed(context))
            assertFalse(prefs.getBoolean("allow_online_ai", true))
        } finally {
            prefs.edit().putBoolean("allow_online_ai", previous).commit()
        }
    }
}
