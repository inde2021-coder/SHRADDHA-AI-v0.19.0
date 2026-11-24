package com.Lia.assistant.license

object AccessKeyRules {

    data class EvaluationResult(
        val isActive: Boolean,
        val statusText: String,
        val isBlocked: Boolean,
        val isExpired: Boolean
    )

    fun evaluate(
        plan: String,
        expiresAt: Long,
        isBlocked: Boolean,
        hasKey: Boolean,
        isVerified: Boolean = false,
        currentTime: Long = System.currentTimeMillis()
    ): EvaluationResult {
        if (isBlocked) {
            return EvaluationResult(
                isActive = false,
                statusText = "Access Blocked by Administrator",
                isBlocked = true,
                isExpired = false
            )
        }

        if (!hasKey) {
            return EvaluationResult(
                isActive = false,
                statusText = "No Access Key Configured",
                isBlocked = false,
                isExpired = false
            )
        }

        // A user-entered string is not proof of entitlement. Only a trusted
        // server-side verifier may mark a key as verified.
        if (!isVerified) {
            return EvaluationResult(
                isActive = false,
                statusText = "License Key Not Verified",
                isBlocked = false,
                isExpired = false
            )
        }

        if (expiresAt > 0L && currentTime > expiresAt) {
            return EvaluationResult(
                isActive = false,
                statusText = "Subscription Expired",
                isBlocked = false,
                isExpired = true
            )
        }

        return EvaluationResult(
            isActive = true,
            statusText = "$plan Plan Active",
            isBlocked = false,
            isExpired = false
        )
    }
}
