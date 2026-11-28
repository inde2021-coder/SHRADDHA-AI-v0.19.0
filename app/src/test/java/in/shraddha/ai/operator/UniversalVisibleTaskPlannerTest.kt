package `in`.shraddha.ai.operator

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test

class UniversalVisibleTaskPlannerTest {
    private val snapshot = ScreenSnapshot(
        packageName = "com.android.chrome",
        activityName = "ChromeActivity",
        capturedAt = 1L,
        elements = listOf(
            ScreenElement("search", "Search or type URL", "", "EditText", Rect(0,0,100,40), false, true, false, false, hintText = "Search or type URL"),
            ScreenElement("go", "Search", "", "Button", Rect(0,50,100,90), true, false, false, false)
        )
    )
    @Test fun acceptsOnlyVisibleUniqueTargetsAndLiteralUserValues() {
        val json = """{"steps":[{"action":"TYPE_TEXT","target":"Search or type URL","value":"weather in Bhopal"},{"action":"CLICK_TEXT","target":"Search"}]}"""
        val result = UniversalVisibleTaskPlanner.parseAndValidate(json, "type weather in Bhopal and press Search", snapshot)
        assertNull(result.error)
        assertEquals(2, result.steps.size)
    }
    @Test fun rejectsInventedValuesAndUnknownTargets() {
        val invented = """{"steps":[{"action":"TYPE_TEXT","target":"Search or type URL","value":"my bank password"}]}"""
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(invented, "open Chrome", snapshot).error)
        val missing = """{"steps":[{"action":"CLICK_TEXT","target":"Pay now"}]}"""
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(missing, "press Pay now", snapshot).error)
    }
    @Test fun rejectsAmbiguousAndPasswordFields() {
        val ambiguous = snapshot.copy(elements = snapshot.elements + snapshot.elements.first().copy(id = "search2"))
        val json = """{"steps":[{"action":"TYPE_TEXT","target":"Search or type URL","value":"hello"}]}"""
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(json, "type hello", ambiguous).error)
        val password = snapshot.copy(elements = snapshot.elements.map { if (it.id == "search") it.copy(password = true) else it })
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(json, "type hello", password).error)
    }
    @Test fun blocksHighImpactButtonsEvenWhenTheyAreVisible() {
        val withDelete = snapshot.copy(elements = snapshot.elements + ScreenElement(
            "delete", "Delete", "", "Button", Rect(0,100,100,140), true, false, false, false
        ))
        val json = """{"steps":[{"action":"CLICK_TEXT","target":"Delete"}]}"""
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(json, "delete this item", withDelete).error)
    }
    @Test fun rejectsOversizedPlansAndUnsupportedActions() {
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate("x".repeat(20001), "do this", snapshot).error)
        val bad = """{"steps":[{"action":"DELETE","target":"file"}]}"""
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(bad, "delete file", snapshot).error)
    }
    @Test fun permitsExplicitSingleRecipientSendOnlyWithExactTypedMessage() {
        val messagingScreen = snapshot.copy(elements = snapshot.elements + listOf(
            ScreenElement("rahul", "Rahul", "", "TextView", Rect(0,100,100,140), true, false, false, false),
            ScreenElement("message", "", "Message", "EditText", Rect(0,150,100,190), false, true, false, false, hintText = "Message"),
            ScreenElement("send", "Send", "", "Button", Rect(0,200,100,240), true, false, false, false)
        ))
        val json = """{"steps":[
            {"action":"CLICK_TEXT","target":"Rahul"},
            {"action":"TYPE_TEXT","target":"Message","value":"I will reach in 10 minutes"},
            {"action":"CLICK_TEXT","target":"Send","expectedVisibleText":"I will reach in 10 minutes"}
        ]}"""
        val result = UniversalVisibleTaskPlanner.parseAndValidate(
            json, "WhatsApp पर Rahul को I will reach in 10 minutes भेजो", messagingScreen
        )
        assertNull(result.error)
        assertTrue(result.steps.last().explicitlyAuthorizedByUser)
    }

    @Test fun rejectsSendWithoutExplicitRecipientOrExactMessage() {
        val messagingScreen = snapshot.copy(elements = snapshot.elements + listOf(
            ScreenElement("message", "", "Message", "EditText", Rect(0,150,100,190), false, true, false, false, hintText = "Message"),
            ScreenElement("send", "Send", "", "Button", Rect(0,200,100,240), true, false, false, false)
        ))
        val json = """{"steps":[
            {"action":"TYPE_TEXT","target":"Message","value":"Hello"},
            {"action":"CLICK_TEXT","target":"Send","expectedVisibleText":"Hello"}
        ]}"""
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(json, "send Hello", messagingScreen).error)
    }

    @Test fun allowsTargetsOnlyAfterAVisibleScreenTransitionAndRechecksAtExecution() {
        val json = """{"steps":[
            {"action":"OPEN_APP","target":"WhatsApp"},
            {"action":"CLICK_TEXT","target":"Chats"},
            {"action":"TYPE_TEXT","target":"Search","value":"Rahul"}
        ]}"""
        val result = UniversalVisibleTaskPlanner.parseAndValidate(
            json, "WhatsApp खोलो और Rahul को खोजो", snapshot
        )
        assertNull(result.error)
        assertTrue(result.steps[1].requiresRuntimeTargetResolution)
        assertTrue(result.steps[2].requiresRuntimeTargetResolution)
    }

    @Test fun rejectsTrailingContentAfterAValidPlan() {
        val valid = """{"steps":[{"action":"CLICK_TEXT","target":"Search"}]}"""
        assertNotNull(
            UniversalVisibleTaskPlanner.parseAndValidate(
                valid + " unexpected", "press Search", snapshot
            ).error
        )
    }

    @Test fun acceptsWhitespaceAfterAValidPlan() {
        val valid = """{"steps":[{"action":"CLICK_TEXT","target":"Search"}]}"""
        assertNull(
            UniversalVisibleTaskPlanner.parseAndValidate(
                valid + "   \n\t", "press Search", snapshot
            ).error
        )
    }

}
