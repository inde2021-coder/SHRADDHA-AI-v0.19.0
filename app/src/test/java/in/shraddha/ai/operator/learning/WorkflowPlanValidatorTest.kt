package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowPlanValidatorTest {
    private fun step(
        id: String = "step-1",
        action: String = "CLICK",
        role: String = "button",
        label: String = "Save",
        input: String = "",
        expected: String = "Saved"
    ) = LearnedStep(
        id = id,
        action = action,
        targetRole = role,
        targetLabel = label,
        inputTemplate = input,
        expectedText = expected
    )

    @Test fun acceptsSupportedPlanWithExplicitTargets() {
        val result = WorkflowPlanValidator.validate(
            "com.example.crm", "Save customer",
            listOf(step(), step("step-2", "BACK", "", "", expected = "")),
            emptyMap()
        )
        assertTrue(result.valid)
    }

    @Test fun rejectsUnknownActionBeforeExecution() {
        val result = WorkflowPlanValidator.validate(
            "com.example.crm", "Unknown action",
            listOf(step(action = "RUN_SHELL")), emptyMap()
        )
        assertFalse(result.valid)
    }

    @Test fun rejectsInteractiveActionWithoutTarget() {
        val result = WorkflowPlanValidator.validate(
            "com.example.crm", "Untargeted click",
            listOf(step(role = "", label = "")), emptyMap()
        )
        assertFalse(result.valid)
    }

    @Test fun rejectsDuplicateStepIds() {
        val result = WorkflowPlanValidator.validate(
            "com.example.crm", "Duplicate IDs",
            listOf(step(id = "same"), step(id = "same", action = "BACK", role = "", label = "")),
            emptyMap()
        )
        assertFalse(result.valid)
    }

    @Test fun blocksSensitiveInputInLaterStepBeforeAnyStepCanRun() {
        val result = WorkflowPlanValidator.validate(
            "com.example.crm", "Two step plan",
            listOf(
                step(),
                step(id = "step-2", action = "TYPE", role = "input", label = "OTP", input = "otp")
            ),
            mapOf("otp" to "123456")
        )
        assertFalse(result.valid)
    }

    @Test fun rejectsMalformedPackageIdentity() {
        val result = WorkflowPlanValidator.validate(
            "not a package", "Save customer", listOf(step()), emptyMap()
        )
        assertFalse(result.valid)
    }
}
