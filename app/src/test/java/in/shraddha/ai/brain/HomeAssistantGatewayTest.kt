package `in`.shraddha.ai.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeAssistantGatewayTest {
    @Test fun onlyHttpsRootUrlsAreAccepted() {
        assertEquals("https://ha.example.com", HomeAssistantGateway.normalizeBaseUrl("https://ha.example.com/"))
        assertEquals("https://ha.example.com:8123", HomeAssistantGateway.normalizeBaseUrl("https://ha.example.com:8123"))
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantGateway.normalizeBaseUrl("http://ha.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantGateway.normalizeBaseUrl("https://user:pass@ha.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantGateway.normalizeBaseUrl("https://ha.example.com/api")
        }
    }
}
