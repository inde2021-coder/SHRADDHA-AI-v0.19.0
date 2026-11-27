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
    @Test fun rejectsOversizedPlansAndUnsupportedActions() {
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate("x".repeat(20001), "do this", snapshot).error)
        val bad = """{"steps":[{"action":"DELETE","target":"file"}]}"""
        assertNotNull(UniversalVisibleTaskPlanner.parseAndValidate(bad, "delete file", snapshot).error)
    }
}
