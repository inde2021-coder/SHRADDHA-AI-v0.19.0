package `in`.shraddha.ai.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginActionPolicyTest {
    private fun response(type: String = "create_note") = JSONObject()
        .put("ok", true).put("verified", true).put("execution", "client-local-allowlisted")
        .put("codeExecution", false).put("requiresLocalExecution", true)
        .put("pluginId", "sample.notes").put("pluginName", "Sample Notes")
        .put("actionIndex", 0).put("capabilities", JSONArray().put(type))
        .put("action", JSONObject().put("type", type).put("title", "Hello").put("body", "Safe body"))

    @Test fun acceptsOnlyVerifiedAllowlistedActionsWithDeclaredCapability() {
        assertEquals("create_note", PluginActionPolicy.parse(response()).type)
        assertEquals("show_notification", PluginActionPolicy.parse(response("show_notification")).type)
    }

    @Test fun rejectsUnverifiedExecutableAndUnsupportedActions() {
        assertThrows(IllegalArgumentException::class.java) { PluginActionPolicy.parse(response().put("verified", false)) }
        assertThrows(IllegalArgumentException::class.java) { PluginActionPolicy.parse(response().put("codeExecution", true)) }
        assertThrows(IllegalArgumentException::class.java) { PluginActionPolicy.parse(response().put("action", JSONObject().put("type", "shell_exec"))) }
        assertThrows(IllegalArgumentException::class.java) { PluginActionPolicy.parse(response().put("capabilities", JSONArray())) }
    }

    @Test fun rejectsInvalidIdentityAndActionBounds() {
        assertThrows(IllegalArgumentException::class.java) { PluginActionPolicy.parse(response().put("pluginId", "../bad")) }
        assertThrows(IllegalArgumentException::class.java) { PluginActionPolicy.parse(response().put("actionIndex", -1)) }
        assertThrows(IllegalArgumentException::class.java) {
            PluginActionPolicy.parse(response().put("action", JSONObject().put("type", "create_note").put("title", "").put("body", "x")))
        }
    }
}
