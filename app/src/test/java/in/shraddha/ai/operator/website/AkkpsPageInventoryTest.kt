package `in`.shraddha.ai.operator.website

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsPageInventoryTest {
    @Test
    fun summaryContainsOnlyStructureCountsAndModuleTitle() {
        val page = JSONObject()
            .put("title", "FARMERS")
            .put("modules", JSONArray().put(JSONObject().put("label", "Farmers").put("path", "/runner/farmers")))
            .put("actions", JSONArray().put(JSONObject().put("label", "Create")))
            .put("forms", JSONArray().put(JSONObject().put("index", 0)))
            .put("tables", JSONArray().put(JSONObject().put("index", 0)))
            .put("fields", JSONArray()
                .put(JSONObject().put("label", "Farmer Name").put("required", true))
                .put(JSONObject().put("label", "Village").put("required", false)))

        val summary = AkkpsPageInventory.summary(page)
        assertTrue(summary.contains("FARMERS"))
        assertTrue(summary.contains("Modules: 1"))
        assertTrue(summary.contains("Forms: 1"))
        assertTrue(summary.contains("Tables: 1"))
        assertTrue(summary.contains("Visible fields: 2"))
        assertTrue(summary.contains("Required: 1"))
        assertFalse(summary.contains("Ram"))
    }
}
