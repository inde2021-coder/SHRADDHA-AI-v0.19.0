package com.Lia.assistant.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class LiaToolCatalogTest {
    @Test
    fun exposesDirectAkkpsRunnerAndPairedPcBrowserTools() {
        val declarations = LiaToolCatalog.declarations()
        val names = (0 until declarations.length()).mapNotNull { declarations.optJSONObject(it)?.optString("name") }
        assertTrue(names.contains("open_akkps_runner"))
        val runnerTool = (0 until declarations.length()).mapNotNull { declarations.optJSONObject(it) }
            .first { it.optString("name") == "open_akkps_runner" }
        assertTrue(runnerTool.optJSONObject("parameters")?.optJSONObject("properties")?.has("command") == true)
        assertTrue(names.contains("control_paired_pc"))
        val pcTool = (0 until declarations.length()).mapNotNull { declarations.optJSONObject(it) }
            .first { it.optString("name") == "control_paired_pc" }
        val description = pcTool.optString("description")
        assertTrue(description.contains("akkps_open"))
        assertTrue(description.contains("akkps_runner"))
        assertTrue(description.contains("akkps_inspect"))
        assertTrue(description.contains("akkps_module"))
    }
}
