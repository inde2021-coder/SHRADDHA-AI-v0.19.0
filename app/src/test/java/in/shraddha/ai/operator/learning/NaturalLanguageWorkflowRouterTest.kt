package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalLanguageWorkflowRouterTest {
    private val profile = AppTrainingProfile(
        packageName = "com.example.crm",
        displayName = "Example CRM",
        workflows = mapOf(
            "Delete customer" to listOf(LearnedStep(action = "CLICK", targetLabel = "Delete")),
            "Update customer" to listOf(LearnedStep(action = "CLICK", targetLabel = "Edit")),
            "Send invoice" to listOf(LearnedStep(action = "CLICK", targetLabel = "Send")),
            "Download report" to listOf(LearnedStep(action = "CLICK", targetLabel = "Download")),
            "Save customer" to listOf(LearnedStep(action = "CLICK", targetLabel = "Save")),
            "Upload document" to listOf(LearnedStep(action = "SELECT_FILE", inputTemplate = "document"))
        )
    )

    @Test fun hindiDeleteRequiresExplicitConfirmation() {
        val result = NaturalLanguageWorkflowRouter.route("ग्राहक हटाओ", profile)
        assertNull(result.route)
        assertTrue(result.message.contains("high-impact"))
    }

    @Test fun hindiUpdateMatchesTrainedUpdateWorkflow() {
        val result = NaturalLanguageWorkflowRouter.route("ग्राहक अपडेट करो", profile)
        assertNotNull(result.route)
        assertEquals("Update customer", result.route?.workflowName)
    }

    @Test fun hindiSendMatchesTrainedSendWorkflow() {
        val result = NaturalLanguageWorkflowRouter.route("इनवॉइस भेजो", profile)
        assertNotNull(result.route)
        assertEquals("Send invoice", result.route?.workflowName)
    }

    @Test fun hindiDownloadMatchesTrainedDownloadWorkflow() {
        val result = NaturalLanguageWorkflowRouter.route("रिपोर्ट डाउनलोड करो", profile)
        assertNotNull(result.route)
        assertEquals("Download report", result.route?.workflowName)
    }

    @Test fun hindiSaveMatchesTrainedSaveWorkflow() {
        val result = NaturalLanguageWorkflowRouter.route("ग्राहक सेव करो", profile)
        assertNotNull(result.route)
        assertEquals("Save customer", result.route?.workflowName)
    }

    @Test fun hindiUploadMatchesTrainedUploadWorkflow() {
        val result = NaturalLanguageWorkflowRouter.route("दस्तावेज अपलोड करो", profile)
        assertNotNull(result.route)
        assertEquals("Upload document", result.route?.workflowName)
    }
}
