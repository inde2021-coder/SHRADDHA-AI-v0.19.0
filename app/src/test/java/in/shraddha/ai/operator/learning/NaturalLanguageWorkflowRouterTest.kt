package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            "Upload document" to listOf(LearnedStep(action = "SELECT_FILE", inputTemplate = "document"))
        )
    )

    @Test fun hindiDeleteMatchesTrainedDeleteWorkflow() {
        val result = NaturalLanguageWorkflowRouter.route("ग्राहक हटाओ", profile)
        assertNotNull(result.route)
        assertEquals("Delete customer", result.route?.workflowName)
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
}
