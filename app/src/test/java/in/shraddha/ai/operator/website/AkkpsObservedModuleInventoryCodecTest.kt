package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsObservedModuleInventoryCodecTest {
    @Test fun roundTripsOnlyObservedRunnerModuleRoutesAndCapabilities() {
        val route = AkkpsObservedRunnerRoute("AWD Reports", "/runner/awd-reports")
        val entry = AkkpsObservedModuleInventoryEntry(
            route, 1_795_824_000_000L,
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
            AkkpsObservedRunnerRoute("Admin", "/runner/admin"), 1_795_824_000_000L,
            mapOf("OBSERVE_PAGE" to CapabilityState.AVAILABLE)
        )
        assertThrows(IllegalArgumentException::class.java) {
            AkkpsObservedModuleInventoryCodec.encode(mapOf(bad.route.path to bad))
        }
    }
    @Test fun capsNewlyObservedRoutesWithoutCrashingOrDroppingExistingEntries() {
        val existingRoute = AkkpsObservedRunnerRoute("Farmers", "/runner/farmers")
        val existing = AkkpsObservedModuleInventoryEntry(
            existingRoute, 1_795_824_000_000L,
            mapOf("OBSERVE_PAGE" to CapabilityState.AVAILABLE)
        )
        val candidates = (1..40).map { index ->
            val suffix = if (index <= 26) ('A'.code + index - 1).toChar().toString()
                else "A${('A'.code + index - 27).toChar()}"
            AkkpsObservedRunnerRoute("Module $suffix", "/runner/module-$index")
        }
        val merged = AkkpsObservedModuleInventoryCodec.mergeObserved(
            mapOf(existingRoute.path to existing), candidates, 1_795_824_000_000L
        )
        assertEquals(30, merged.size)
        assertTrue(merged.containsKey(existingRoute.path))
        assertNotNull(AkkpsObservedModuleInventoryCodec.decode(AkkpsObservedModuleInventoryCodec.encode(merged)))
    }

    @Test fun refusesToPersistCapabilitiesOutsideRunnerDataEntryScope() {
        val route = AkkpsObservedRunnerRoute("AWD Reports", "/runner/awd-reports")
        val unsafe = AkkpsObservedModuleInventoryEntry(
            route, 1_795_824_000_000L,
            mapOf("DELETE_RECORD" to CapabilityState.AVAILABLE)
        )
        assertThrows(IllegalArgumentException::class.java) {
            AkkpsObservedModuleInventoryCodec.encode(mapOf(route.path to unsafe))
        }
        val safe = unsafe.copy(capabilities = mapOf("DELETE_RECORD" to CapabilityState.UNAVAILABLE))
        assertEquals(
            CapabilityState.UNAVAILABLE,
            AkkpsObservedModuleInventoryCodec.decode(
                AkkpsObservedModuleInventoryCodec.encode(mapOf(route.path to safe))
            )[route.path]?.capabilities?.get("DELETE_RECORD")
        )
    }

    @Test fun rejectsDecodedInventoryThatGrantsBlockedOperations() {
        val unsafe = """{"version":1,"modules":[{"label":"AWD Reports","path":"/runner/awd-reports","active":false,"observedAt":1795824000000,"capabilities":{"DELETE_RECORD":"AVAILABLE"}}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            AkkpsObservedModuleInventoryCodec.decode(unsafe)
        }
    }

}
