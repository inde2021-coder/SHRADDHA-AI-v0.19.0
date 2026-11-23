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
    private val local = EncryptedFeatureStore(context.applicationContext)
    private fun localKey(key: String) = "memory_$key"
    private val cloud = FirebaseCloudVault(context.applicationContext)

    data class SaveResult(val localSaved: Boolean, val cloudSaved: Boolean, val message: String)
    data class LoadResult(val value: String?, val source: String)
    data class ConflictPreview(val key: String, val localValue: String?, val cloudValue: String?, val differs: Boolean)
    data class MemoryRecord(val key: String, val value: String)

    fun save(
        key: String,
        value: String,
        cloudConsent: Boolean = false,
        email: String? = null,
        password: String? = null
    ): Result<SaveResult> = runCatching {
        MemorySyncPolicy.validateRecord(key, value)
        local.put(localKey(key), value).getOrThrow()
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
                local.put(localKey(key), remote).getOrThrow()
                return@runCatching LoadResult(remote, "firebase")
            }
        }
        LoadResult(local.get(localKey(key)).getOrThrow().takeIf { it.isNotEmpty() }, "device")
    }

    /**
     * Explicitly deletes one memory record. When cloud consent is enabled, both Firebase
     * mirrors must be deleted successfully before the local encrypted copy is removed.
     */
    fun delete(
        key: String,
        cloudConsent: Boolean = false,
        email: String? = null,
        password: String? = null
    ): Result<Boolean> = runCatching {
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid memory record key" }
        val cloudRemoved = if (cloudConsent) {
            MemorySyncPolicy.requireCloudConsent(cloudConsent)
            val account = email?.trim().orEmpty()
            val secret = password.orEmpty()
            require(account.isNotBlank() && secret.length >= 8) {
                "Firebase account credentials are required for an approved cloud deletion"
            }
            cloud.signInAndDelete(account, secret, key).getOrThrow()
        } else false
        val localRemoved = local.remove(localKey(key))
        check(localRemoved || cloudRemoved) { "No matching local or cloud memory record was found, or local deletion failed" }
        localRemoved || cloudRemoved
    }

    /** Lists locally stored memory keys without exposing values to callers that only need an index. */
    fun listLocalRecords(): Result<List<MemoryRecord>> = runCatching {
        local.getAllWithPrefixes(setOf("memory_")).getOrThrow()
            .map { (storedKey, value) -> MemoryRecord(storedKey.removePrefix("memory_"), value) }
            .filter { it.key.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }
            .sortedBy { it.key }
    }

    /** Explicit cloud comparison. No overwrite occurs during preview. */
    fun compareLocalAndCloud(
        key: String,
        cloudConsent: Boolean,
        email: String,
        password: String
    ): Result<ConflictPreview> = runCatching {
        MemorySyncPolicy.requireCloudConsent(cloudConsent)
        val localValue = local.get(localKey(key)).getOrThrow().takeIf { it.isNotEmpty() }
        val cloudValue = cloud.signInAndLoad(email, password, key).getOrThrow()
        ConflictPreview(key, localValue, cloudValue, localValue != null && cloudValue != null && localValue != cloudValue)
    }

    /** User chooses the winning copy explicitly; preview itself never mutates either copy. */
    fun resolveConflict(
        key: String,
        winningCopy: String,
        cloudConsent: Boolean,
        email: String,
        password: String
    ): Result<SaveResult> = runCatching {
        require(winningCopy == "local" || winningCopy == "cloud") { "Choose local or cloud as the winning copy." }
        MemorySyncPolicy.requireCloudConsent(cloudConsent)
        if (winningCopy == "cloud") {
            val remote = cloud.signInAndLoad(email, password, key).getOrThrow()
                ?: error("Cloud copy does not exist; nothing was changed.")
            local.put(localKey(key), remote).getOrThrow()
            SaveResult(true, true, "Cloud copy restored to this device. No cloud overwrite was performed.")
        } else {
            val localValue = local.get(localKey(key)).getOrThrow()
            require(localValue.isNotEmpty()) { "Local copy does not exist; nothing was changed." }
            cloud.signInAndSave(email, password, key, localValue).getOrThrow()
            SaveResult(true, true, "Local copy uploaded to the authenticated cloud vault.")
        }
    }

    fun clearLocal(): Result<Unit> = runCatching {
        check(local.removeAllWithPrefix("memory_")) { "Could not clear local memory" }
    }
}
