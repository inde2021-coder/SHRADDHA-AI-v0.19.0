package `in`.shraddha.ai.operator

import org.junit.Assert.*
import org.junit.Test

class UniversalCommandAdapterRegistryTest {
    private class FakeAdapter(
        override val id: String,
        override val supportedActions: Set<OperatorActionType>,
        private val result: Boolean
    ) : UniversalCommandAdapter {
        override val platform = UniversalCommandPlatform.ANDROID_ACCESSIBILITY
        var calls = 0
        override fun execute(step: OperatorStep, confirmed: Boolean): Boolean {
            calls++
            return result
        }
    }

    @Test fun dispatchesOnlyToOneAdapterThatAdvertisesTheAction() {
        val registry = UniversalCommandAdapterRegistry()
        val adapter = FakeAdapter("android_test", setOf(OperatorActionType.CLICK_TEXT), true)
        registry.register(adapter)
        assertTrue(registry.execute(OperatorStep(OperatorActionType.CLICK_TEXT, target = "Open"), false))
        assertEquals(1, adapter.calls)
        assertNull(registry.resolve(OperatorActionType.TYPE_TEXT))
    }

    @Test fun rejectsDuplicateAdapterIdsAndAmbiguousActionRoutes() {
        val registry = UniversalCommandAdapterRegistry()
        registry.register(FakeAdapter("android_one", setOf(OperatorActionType.BACK), true))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(FakeAdapter("android_one", setOf(OperatorActionType.HOME), true))
        }
        registry.register(FakeAdapter("android_two", setOf(OperatorActionType.BACK), true))
        assertFalse(registry.execute(OperatorStep(OperatorActionType.BACK), false))
    }

    @Test fun doesNotClaimUnsupportedActions() {
        val registry = UniversalCommandAdapterRegistry()
        registry.register(FakeAdapter("android_test", setOf(OperatorActionType.OPEN_APP), true))
        assertFalse(registry.execute(OperatorStep(OperatorActionType.TYPE_TEXT, target = "Message", value = "Hi"), false))
    }
    @Test fun androidAdapterExecutesThroughTheRegisteredCallback() {
        var observed = ""
        val registry = UniversalCommandAdapterRegistry()
        registry.register(AndroidAccessibilityCommandAdapter { step, confirmed ->
            observed = "${step.action}:${step.target}:$confirmed"
            true
        })
        assertTrue(registry.execute(OperatorStep(OperatorActionType.SELECT_FILE, target = "report.pdf"), true))
        assertEquals("SELECT_FILE:report.pdf:true", observed)
        assertEquals(1, registry.capabilities().size)
        assertTrue(OperatorActionType.SCROLL_BACKWARD in registry.capabilities().single().actions)
    }

}
