package `in`.shraddha.ai.brain

import android.content.Context
import `in`.shraddha.ai.cloud.FirebaseCloudVault

/** Explicit policy gate shared by Personal Memory, Knowledge Vault and Firebase sync. */
object MemorySyncPolicy {
    fun requireCloudConsent(cloudConsent: Boolean) {
        require(cloudConsent) { "Cloud memory sync is disabled until the user explicitly enables it" }
    }

    fun validateRecord(key: String, value: String) {
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid memory record key" }
        require(value.isNotBlank() && value.length <= 100_000) { "Memory record is empty or too large" }
    }
}

/**
 * Local-first memory repository. The device vault is always written before optional Firebase
 * sync. Firebase receives only the cloud-vault encrypted record. No background sync is started.
 */
class UnifiedMemoryRepository(context: Context) {
    private val local = SecureMemoryVault(context.applicationContext)
    private val cloud = FirebaseCloudVault(context.applicationContext)

    data class SaveResult(val localSaved: Boolean, val cloudSaved: Boolean, val message: String)
    data class LoadResult(val value: String?, val source: String)

    fun save(
        key: String,
        value: String,
        cloudConsent: Boolean = false,
        email: String? = null,
        password: String? = null
    ): Result<SaveResult> = runCatching {
        MemorySyncPolicy.validateRecord(key, value)
        local.save(value).getOrThrow()
        if (!cloudConsent) return@runCatching SaveResult(true, false, "Saved to encrypted device memory; cloud sync remains off")
        MemorySyncPolicy.requireCloudConsent(cloudConsent)
        val account = email?.trim().orEmpty()
        val secret = password.orEmpty()
        require(account.isNotBlank() && secret.length >= 8) { "Firebase account credentials are required for an approved cloud sync" }
        cloud.signInAndSave(account, secret, key, value).getOrThrow()
        SaveResult(true, true, "Saved locally and synced to the authenticated Firebase vault")
    }

    /** Cloud restore is opt-in and always refreshes the local encrypted vault after verification. */
    fun load(
        key: String,
        cloudConsent: Boolean = false,
        email: String? = null,
        password: String? = null
    ): Result<LoadResult> = runCatching {
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        if (cloudConsent) {
            MemorySyncPolicy.requireCloudConsent(cloudConsent)
            val account = email?.trim().orEmpty()
            val secret = password.orEmpty()
            require(account.isNotBlank() && secret.length >= 8) { "Firebase account credentials are required for cloud restore" }
            val remote = cloud.signInAndLoad(account, secret, key).getOrThrow()
            if (remote != null) {
                local.save(remote).getOrThrow()
                return@runCatching LoadResult(remote, "firebase")
            }
        }
        LoadResult(local.load().getOrThrow().takeIf { it.isNotEmpty() }, "device")
    }

    fun clearLocal(): Result<Unit> = runCatching {
        check(local.clear()) { "Could not clear local memory" }
    }
}
