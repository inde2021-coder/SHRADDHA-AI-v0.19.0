package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsObservedModuleInventoryCodecTest {
    @Test fun roundTripsOnlyObservedRunnerModuleRoutesAndCapabilities() {
        val route = AkkpsObservedRunnerRoute("AWD Reports", "/runner/awd-reports")
        val entry = AkkpsObservedModuleInventoryEntry(
            route, 1_800_000_000_000L,
            mapOf("OBSERVE_PAGE" to CapabilityState.AVAILABLE, "SUBMIT_FORM" to CapabilityState.UNKNOWN)
        )
        val decoded = AkkpsObservedModuleInventoryCodec.decode(
            AkkpsObservedModuleInventoryCodec.encode(mapOf(route.path to entry))
        )
        assertEquals(route.path, decoded[route.path]?.route?.path)
        assertEquals(CapabilityState.UNKNOWN, decoded[route.path]?.capabilities?.get("SUBMIT_FORM"))
    }

    @Test fun rejectsUnobservedOrRestrictedPaths() {
        val bad = AkkpsObservedModuleInventoryEntry(
            AkkpsObservedRunnerRoute("Admin", "/runner/admin"), 1_800_000_000_000L,
            mapOf("OBSERVE_PAGE" to CapabilityState.AVAILABLE)
        )
        assertThrows(IllegalArgumentException::class.java) {
            AkkpsObservedModuleInventoryCodec.encode(mapOf(bad.route.path to bad))
        }
    }
}
