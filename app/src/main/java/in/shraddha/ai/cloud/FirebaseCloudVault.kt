package `in`.shraddha.ai.cloud

import `in`.shraddha.ai.BuildConfig

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/** Firebase owner-scoped vault. Client-side AES-GCM means Firestore receives ciphertext only. */
class FirebaseCloudVault(private val context: Context) {
    companion object {
        private const val APP_NAME = "shraddha_cloud_vault"
        private const val CONFIG_PREFS = "shraddha_firebase_runtime_config"

        /** Firebase client identifiers are public config, not service-account secrets. */
        fun configure(context: Context, apiKey: String, appId: String, projectId: String, senderId: String, storageBucket: String = ""): Result<Unit> = runCatching {
            require(apiKey.trim().length >= 20) { "Firebase API key is invalid." }
            require(appId.trim().matches(Regex("^1:[0-9]+:android:[A-Za-z0-9]+$"))) { "Firebase App ID must be the Android app ID from Firebase Console." }
            require(projectId.trim().matches(Regex("^[a-z0-9][a-z0-9-]{4,28}[a-z0-9]$"))) { "Firebase project ID is invalid." }
            require(senderId.trim().matches(Regex("^[0-9]{5,20}$"))) { "Firebase sender ID must contain 5–20 digits." }
            require(storageBucket.isBlank() || storageBucket.trim().matches(Regex("^[A-Za-z0-9._-]{3,120}$"))) { "Storage bucket format is invalid." }
            require(FirebaseApp.getApps(context).none { it.name == APP_NAME }) {
                "Firebase is already initialized. Restart Shraddha AI before changing its Firebase project."
            }
            val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            check(prefs.edit().putString("api_key", apiKey.trim()).putString("app_id", appId.trim())
                .putString("project_id", projectId.trim()).putString("sender_id", senderId.trim())
                .putString("storage_bucket", storageBucket.trim()).commit()) { "Could not save Firebase configuration." }
        }
    }

    private fun initialize(): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.let { return it }
        val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FIREBASE_API_KEY
        val appId = prefs.getString("app_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FIREBASE_APP_ID
        val projectId = prefs.getString("project_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FIREBASE_PROJECT_ID
        val senderId = prefs.getString("sender_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FIREBASE_SENDER_ID
        val storageBucket = prefs.getString("storage_bucket", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FIREBASE_STORAGE_BUCKET
        require(apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank() && senderId.isNotBlank()) {
            "Firebase config missing. Enter Android Firebase API key, App ID, Project ID and sender ID in Cloud & Data."
        }
        val options = FirebaseOptions.Builder().setApiKey(apiKey).setApplicationId(appId)
            .setProjectId(projectId).setGcmSenderId(senderId)
            .apply { if (storageBucket.isNotBlank()) setStorageBucket(storageBucket) }.build()
        return FirebaseApp.initializeApp(context, options, APP_NAME) ?: error("Firebase initialization failed")
    }

    fun createAccount(email: String, password: String): Result<String> = runCatching {
        require(email.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Enter a valid email address." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        runBlocking { FirebaseAuth.getInstance(initialize()).createUserWithEmailAndPassword(email.trim(), password).await().user?.uid ?: error("Account creation returned no user") }
    }

    fun signInAndSave(email: String, password: String, key: String, plaintext: String): Result<String> = runCatching {
        require(email.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Enter a valid email address." }
        require(password.isNotEmpty()) { "Account password is required." }
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Backup key format is invalid." }
        require(plaintext.toByteArray(Charsets.UTF_8).size <= 140_000) { "Backup is too large (maximum 140 KB)." }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(email.trim(), password).await().user ?: error("Sign-in failed")
            val encrypted = encrypt(plaintext, password)
            FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("vault").document(key)
                .set(mapOf("format" to "AES-256-GCM/PBKDF2-SHA256-v1", "payload" to encrypted, "updatedAt" to System.currentTimeMillis()))
                .await()
            user.uid
        }
    }

    fun signInAndLoad(email: String, password: String, key: String): Result<String?> = runCatching {
        require(email.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Enter a valid email address." }
        require(password.isNotEmpty()) { "Account password is required." }
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Backup key format is invalid." }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(email.trim(), password).await().user ?: error("Sign-in failed")
            val doc = FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("vault").document(key).get().await()
            val payload = doc.getString("payload") ?: return@runBlocking null
            decrypt(payload, password)
        }
    }

    private fun encrypt(value: String, password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(salt, iv, encrypted).joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }
    }

    private fun decrypt(payload: String, password: String): String {
        require(payload.length <= 190_000) { "Encrypted backup is too large." }
        val parts = payload.split('.')
        require(parts.size == 3) { "Encrypted backup format is invalid" }
        val salt = Base64.decode(parts[0], Base64.NO_WRAP or Base64.URL_SAFE)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
        require(salt.size == 16 && iv.size == 12 && encrypted.size >= 16) { "Encrypted backup parameters are invalid." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
