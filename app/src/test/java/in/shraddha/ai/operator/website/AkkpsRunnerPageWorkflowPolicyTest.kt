package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AkkpsRunnerPageWorkflowPolicyTest {
    @Test
    fun parsesOnlyExplicitRepeatedAwdRowCommands() {
        assertEquals("drying event dates", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add drying event"))
        assertEquals("re-flooding dates", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add another re-flooding date"))
        assertEquals("fertilizer application dates", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add fertilizer row"))
        assertEquals("other fertilizer rows", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add other fertilizer row"))
        assertEquals("field visits", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add field visit"))
        assertEquals("historical yield rows", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add historical yield"))
        assertEquals("water level at each visit", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add water level reading"))
        assertEquals("drying event dates", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("जोड़ें सूखने की तारीख"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("add farmer"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("delete drying event"))
    }

    @Test
    fun parsesExactUserNamedAutocompleteOptions() {
        assertEquals(
            AkkpsAutocompleteRequest("Village", "Rampur"),
            AkkpsRunnerPageWorkflowPolicy.autocompleteRequest("select village Rampur")
        )
        assertEquals(
            AkkpsAutocompleteRequest("Rice Variety", "Swarna"),
            AkkpsRunnerPageWorkflowPolicy.autocompleteRequest("choose rice variety Swarna")
        )
        assertEquals(
            AkkpsAutocompleteRequest("Linked Farmer", "Ravi Kumar"),
            AkkpsRunnerPageWorkflowPolicy.autocompleteRequest("select farmer Ravi Kumar")
        )
        assertEquals(
            AkkpsAutocompleteRequest("Linked Plot", "PLOT-22"),
            AkkpsRunnerPageWorkflowPolicy.autocompleteRequest("choose plot PLOT-22")
        )
        assertNull(AkkpsRunnerPageWorkflowPolicy.autocompleteRequest("select village"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.autocompleteRequest("select village " + "x".repeat(161)))
    }

    @Test
    fun parsesOnlyKnownDocumentPickerCommands() {
        assertEquals("Field Photos", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach field photo"))
        assertEquals("Farmer Photo", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("upload farmer photo"))
        assertEquals("Identity Document", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach Aadhaar"))
        assertEquals("Land Deed", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach land deed"))
        assertEquals("Agreement", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach agreement"))
        assertEquals("Signature", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach signature"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach random file"))
    }
}
