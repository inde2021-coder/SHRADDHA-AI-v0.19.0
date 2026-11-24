package com.Lia.assistant.license

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessKeyManager {
    private val _info = MutableStateFlow(LicenseInfo(active = true, plan = "Direct", statusText = "Direct edition active"))
    val info: StateFlow<LicenseInfo> = _info.asStateFlow()

    fun refreshInfo(context: Context) {
        val prefs = context.getSharedPreferences("lia_access_key", Context.MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        val plan = prefs.getString("plan", "Direct") ?: "Direct"
        val expiresAt = prefs.getLong("expires_at", 0L)
        val blocked = prefs.getBoolean("blocked", false)

        // The no-key Direct edition remains usable. Any Pro/paid key must have
        // been verified by a trusted licensing service; local "active" flags
        // and a non-empty user-entered key are not sufficient.
        val directEdition = key.isBlank() && plan.equals("Direct", ignoreCase = true) && !blocked
        val evaluation = if (directEdition) {
            AccessKeyRules.EvaluationResult(
                isActive = true,
                statusText = "Direct edition active",
                isBlocked = false,
                isExpired = false
            )
        } else {
            AccessKeyRules.evaluate(
                plan = plan,
                expiresAt = expiresAt,
                isBlocked = blocked,
                hasKey = key.isNotBlank(),
                isVerified = prefs.getBoolean("verified_by_license_service", false)
            )
        }

        _info.value = LicenseInfo(
            active = evaluation.isActive,
            plan = plan,
            expiresAt = expiresAt,
            statusText = evaluation.statusText,
            blocked = evaluation.isBlocked,
            hasRememberedKey = key.isNotBlank()
        )
    }

    suspend fun activate(context: Context, key: String): Pair<Boolean, String> {
        if (key.trim().isEmpty()) return Pair(false, "Key cannot be empty")

        // No license-verification endpoint or signed entitlement verifier is
        // configured in this build. Never turn arbitrary input into a paid plan.
        refreshInfo(context)
        return Pair(false, "Online license verification is not configured. Key was not activated.")
    }

    suspend fun liveCheck(context: Context): Boolean {
        refreshInfo(context)
        return _info.value.active
    }

    fun isAccessGranted(): Boolean {
        return _info.value.active
    }
}
