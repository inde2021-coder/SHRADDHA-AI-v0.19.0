package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AkkpsRunnerPageWorkflowPolicyTest {
    @Test
    fun parsesPlotIdAndBoundaryVoiceActions() {
        assertEquals("PLOT-123", AkkpsRunnerPageWorkflowPolicy.plotId("set plot id PLOT-123"))
        assertEquals("PLOT_44", AkkpsRunnerPageWorkflowPolicy.plotId("प्लॉट आईडी सेट करें PLOT_44"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.plotId("set plot id PLOT/123"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.plotId("plot id PLOT-123"))
        assertEquals(AkkpsBoundaryVoiceAction.INSPECT_MAP, AkkpsRunnerPageWorkflowPolicy.boundaryAction("inspect live map"))
        assertEquals(AkkpsBoundaryVoiceAction.VERIFY_UPLOAD, AkkpsRunnerPageWorkflowPolicy.boundaryAction("verify boundary upload"))
        assertEquals(AkkpsBoundaryVoiceAction.PREPARE_UPLOAD, AkkpsRunnerPageWorkflowPolicy.boundaryAction("prepare boundary for upload"))
        assertEquals(AkkpsBoundaryVoiceAction.IMPORT_SATELLITE, AkkpsRunnerPageWorkflowPolicy.boundaryAction("import satellite candidate"))
        assertEquals(AkkpsBoundaryVoiceAction.START_GPS_WALK, AkkpsRunnerPageWorkflowPolicy.boundaryAction("start gps walk"))
        assertEquals(AkkpsBoundaryVoiceAction.STOP_GPS_WALK, AkkpsRunnerPageWorkflowPolicy.boundaryAction("stop gps walk"))
        assertEquals(AkkpsBoundaryVoiceAction.SAVE_LOCAL, AkkpsRunnerPageWorkflowPolicy.boundaryAction("save local boundary"))
        assertEquals(AkkpsBoundaryVoiceAction.REOPEN_LOCAL, AkkpsRunnerPageWorkflowPolicy.boundaryAction("reopen saved boundary"))
        assertEquals(AkkpsBoundaryVoiceAction.IMPORT_KML, AkkpsRunnerPageWorkflowPolicy.boundaryAction("import boundary kml"))
        assertEquals(AkkpsBoundaryVoiceAction.EXPORT_KML, AkkpsRunnerPageWorkflowPolicy.boundaryAction("export boundary kml"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.boundaryAction("delete boundary"))
    }

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
        assertEquals("drying event dates", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("सूखने की तारीख जोड़ें"))
        assertEquals("water level at each visit", AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection("जल स्तर रीडिंग जोड़ें"))
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
            AkkpsAutocompleteRequest("Village", "Rampur"),
            AkkpsRunnerPageWorkflowPolicy.autocompleteRequest("Rampur गाँव चुनें")
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
        assertEquals("Field Photos", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("खेत की फोटो जोड़ें"))
        assertEquals("Farmer Photo", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("upload farmer photo"))
        assertEquals("Identity Document", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach Aadhaar"))
        assertEquals("Land Deed", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach land deed"))
        assertEquals("Agreement", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach agreement"))
        assertEquals("Signature", AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach signature"))
        assertNull(AkkpsRunnerPageWorkflowPolicy.documentFieldLabel("attach random file"))
    }
}
