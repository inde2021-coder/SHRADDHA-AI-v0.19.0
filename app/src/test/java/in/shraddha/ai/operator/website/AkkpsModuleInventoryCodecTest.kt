package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsModuleInventoryCodecTest {
    @Test fun roundTripPreservesObservedModuleAndCapabilityStates() {
        val entry = AkkpsModuleInventoryEntry(
            module = AkkpsRunnerModule.FARMERS.name,
            observedAtEpochMillis = System.currentTimeMillis(),
            capabilities = mapOf(
                WebsiteOperation.OBSERVE_PAGE.name to CapabilityState.AVAILABLE,
                WebsiteOperation.CREATE_RECORD.name to CapabilityState.AVAILABLE,
                WebsiteOperation.DELETE_RECORD.name to CapabilityState.UNKNOWN
            )
        )
        val restored = AkkpsModuleInventoryCodec.decode(
            AkkpsModuleInventoryCodec.encode(mapOf(entry.module to entry))
        )
        assertEquals(entry, restored[entry.module])
    }

    @Test fun rejectsUnknownAndDuplicateModules() {
        val unknown = """{"version":1,"modules":[{"module":"ADMIN","observedAt":${System.currentTimeMillis()},"capabilities":{}}]}"""
        assertTrue(runCatching { AkkpsModuleInventoryCodec.decode(unknown) }.isFailure)
        val duplicate = """{"version":1,"modules":[
            {"module":"FARMERS","observedAt":${System.currentTimeMillis()},"capabilities":{}},
            {"module":"FARMERS","observedAt":${System.currentTimeMillis()},"capabilities":{}}
        ]}"""
        assertTrue(runCatching { AkkpsModuleInventoryCodec.decode(duplicate) }.isFailure)
    }

    @Test fun rejectsUnsupportedOperationsAndInvalidStates() {
        val unsupported = """{"version":1,"modules":[{"module":"PLOTS","observedAt":${System.currentTimeMillis()},"capabilities":{"ROOT": "AVAILABLE"}}]}"""
        assertTrue(runCatching { AkkpsModuleInventoryCodec.decode(unsupported) }.isFailure)
        val invalidState = """{"version":1,"modules":[{"module":"PLOTS","observedAt":${System.currentTimeMillis()},"capabilities":{"VIEW_RECORD":"DONE"}}]}"""
        assertTrue(runCatching { AkkpsModuleInventoryCodec.decode(invalidState) }.isFailure)
    }

    @Test fun rejectsOversizedAndFutureDatedInventory() {
        assertTrue(runCatching { AkkpsModuleInventoryCodec.decode("x".repeat(50_001)) }.isFailure)
        val future = System.currentTimeMillis() + 3_600_000L
        val payload = """{"version":1,"modules":[{"module":"SYNC","observedAt":$future,"capabilities":{}}]}"""
        assertTrue(runCatching { AkkpsModuleInventoryCodec.decode(payload) }.isFailure)
    }
}
