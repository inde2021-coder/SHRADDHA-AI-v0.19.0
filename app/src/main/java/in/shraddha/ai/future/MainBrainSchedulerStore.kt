package `in`.shraddha.ai.future

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted durable metadata for Main Brain's dependency scheduler.
 * A RUNNING item is restored as FAILED: an interrupted side effect is never replayed blindly.
 * PENDING items are safe to resume because no executor has claimed them yet.
 */
class MainBrainSchedulerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun save(snapshots: List<SharedTaskScheduler.Snapshot>) {
        val array = JSONArray()
        snapshots.forEach { item ->
            val dependencies = JSONArray().apply { item.task.dependsOn.sorted().forEach { put(it) } }
            array.put(JSONObject()
                .put("id", item.task.id)
                .put("title", item.task.title)
                .put("dependsOn", dependencies)
                .put("priority", item.task.priority)
                .put("payload", item.task.payload)
                .put("state", item.state.name)
                .put("submittedOrder", item.submittedOrder)
                .put("error", item.error ?: JSONObject.NULL))
        }
        val encrypted = encrypt(array.toString().toByteArray(StandardCharsets.UTF_8))
        check(prefs.edit().putString(KEY_SNAPSHOT, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Could not persist Main Brain scheduler state"
        }
    }

    @Synchronized
    fun load(): List<SharedTaskScheduler.Snapshot> {
        val encoded = prefs.getString(KEY_SNAPSHOT, null) ?: return emptyList()
        val clear = decrypt(Base64.decode(encoded, Base64.NO_WRAP))
        val array = JSONArray(String(clear, StandardCharsets.UTF_8))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val dependencies = item.getJSONArray("dependsOn")
            val task = SharedTaskScheduler.Task(
                id = item.getString("id"),
                title = item.getString("title"),
                dependsOn = (0 until dependencies.length()).map { dependencies.getString(it) }.toSet(),
                priority = item.getInt("priority"),
                payload = item.optString("payload")
            )
            val savedState = SharedTaskScheduler.State.valueOf(item.getString("state"))
            val safeState = if (savedState == SharedTaskScheduler.State.RUNNING) SharedTaskScheduler.State.FAILED else savedState
            SharedTaskScheduler.Snapshot(
                task = task,
                state = safeState,
                submittedOrder = item.getLong("submittedOrder"),
                error = if (savedState == SharedTaskScheduler.State.RUNNING)
                    "Process stopped during execution; action was not replayed. Review and enqueue again."
                else item.optString("error").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    private fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(clear)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > 13) { "Stored scheduler snapshot is truncated" }
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize in 12..16 && payload.size > 1 + ivSize) { "Stored scheduler IV is invalid" }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Main Brain scheduler encryption key is missing; refusing to replace it while encrypted tasks still exist.")
        cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(payload.copyOfRange(1 + ivSize, payload.size))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "shraddha_main_brain_scheduler"
        const val KEY_SNAPSHOT = "encrypted_snapshot_v1"
        const val KEY_ALIAS = "shraddha_main_brain_scheduler_aes"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
