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
            "Save customer" to listOf(LearnedStep(action = "CLICK", targetLabel = "Save")),
            "Upload document" to listOf(LearnedStep(action = "SELECT_FILE", inputTemplate = "document"))
        )
    )

    @Test fun hindiDeleteRequiresExplicitConfirmation() {
        val result = NaturalLanguageWorkflowRouter.route("ग्राहक हटाओ", profile)
        assertEquals(null, result.route)
        org.junit.Assert.assertTrue(result.message.contains("high-impact"))
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

    @Test fun paymentAndPublishAreNotAutomaticallyRouted() {
        assertEquals(null, NaturalLanguageWorkflowRouter.route("pay invoice", profile).route)
        assertEquals(null, NaturalLanguageWorkflowRouter.route("publish report", profile).route)
    }

    @Test fun mixedActionInstructionRequiresSeparateSteps() {
        val result = NaturalLanguageWorkflowRouter.route("search customer and send invoice", profile)
        assertEquals(null, result.route)
        org.junit.Assert.assertTrue(result.message.contains("कई अलग काम"))
    }

    @Test fun commonHindiDeleteInflectionsAndHinglishPhrasesAreBlocked() {
        listOf("ग्राहक हटा दो", "ग्राहक हटा दें", "ग्राहक मिटा देना", "customer hata do", "customer mita do")
            .forEach { command ->
                val result = NaturalLanguageWorkflowRouter.route(command, profile)
                assertEquals(command, null, result.route)
                org.junit.Assert.assertTrue(command, result.message.contains("high-impact"))
            }
    }

    @Test fun paymentAndPublishHinglishPhrasesAreBlocked() {
        listOf("invoice ka payment karo", "paise bhejo", "report publish karo", "report post")
            .forEach { command ->
                val result = NaturalLanguageWorkflowRouter.route(command, profile)
                assertEquals(command, null, result.route)
                org.junit.Assert.assertTrue(command, result.message.contains("high-impact"))
            }
    }

    @Test fun fullWidthEnglishHighImpactWordsAreNormalizedBeforeRouting() {
        val result = NaturalLanguageWorkflowRouter.route("ｐａｙ invoice", profile)
        assertEquals(null, result.route)
        org.junit.Assert.assertTrue(result.message.contains("high-impact"))
    }

    @Test fun mixedHindiActionsRequireSeparateInstructions() {
        val result = NaturalLanguageWorkflowRouter.route("ग्राहक खोजो और इनवॉइस भेजो", profile)
        assertEquals(null, result.route)
        org.junit.Assert.assertTrue(result.message.contains("कई अलग काम"))
    }

}
