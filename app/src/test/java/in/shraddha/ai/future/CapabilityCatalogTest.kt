package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCatalogTest {
    @Test
    fun capabilityIdsAreUniqueAndNonBlank() {
        val ids = CapabilityCatalog.all.map { it.id }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals("Capability IDs must be unique for stable navigation and UI keys", ids.size, ids.distinct().size)
    }

    @Test
    fun roadmapFeaturesAreNotAdvertisedAsAvailable() {
        val plugins = CapabilityCatalog.all.single { it.id == "plugins" }
        assertEquals(CapabilityState.ROADMAP, plugins.state)
        assertTrue(plugins.detail.contains("not shipped", ignoreCase = true))

        val advancedSocial = CapabilityCatalog.all.single { it.id == "social_media_advanced" }
        assertEquals(CapabilityState.ROADMAP, advancedSocial.state)
    }

    @Test
    fun currentlySupportedTextPublishingHasSeparateCapability() {
        val social = CapabilityCatalog.all.single { it.id == "social_publish" }
        assertEquals(CapabilityState.CONFIGURATION_REQUIRED, social.state)
        assertTrue(social.detail.contains("exact preview approval", ignoreCase = true))
    }

    @Test
    fun everyCapabilityHasUserFacingDescription() {
        CapabilityCatalog.all.forEach { capability ->
            assertNotNull(capability.group)
            assertTrue(capability.title.isNotBlank())
            assertTrue(capability.detail.isNotBlank())
        }
    }
}
