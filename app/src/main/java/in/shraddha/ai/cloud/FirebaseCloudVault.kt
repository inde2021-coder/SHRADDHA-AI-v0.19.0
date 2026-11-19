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
    }

    private fun initialize(): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.let { return it }
        require(BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()) {
            "Firebase config missing. Add FIREBASE_API_KEY, FIREBASE_APP_ID, FIREBASE_PROJECT_ID and FIREBASE_SENDER_ID to local.properties."
        }
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .apply { if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET) }
            .build()
        return FirebaseApp.initializeApp(context, options, APP_NAME)
            ?: FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: error("Firebase initialization failed")
    }

    fun createAccount(email: String, password: String): Result<String> = runCatching {
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            FirebaseAuth.getInstance(initialize())
                .createUserWithEmailAndPassword(normalizedEmail, password)
                .await().user?.uid ?: error("Account creation returned no user")
        }
    }

    fun signInAndSave(email: String, password: String, key: String, plaintext: String): Result<String> = runCatching {
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(plaintext.length <= 16000) { "Cloud memory is limited to 16,000 characters" }
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user ?: error("Sign-in failed")
            val encrypted = encrypt(plaintext, password)
            FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("vault").document(key)
                .set(mapOf("format" to "AES-256-GCM/PBKDF2-SHA256-v1", "payload" to encrypted, "updatedAt" to System.currentTimeMillis()))
                .await()
            user.uid
        }
    }

    fun signInAndLoad(email: String, password: String, key: String): Result<String?> = runCatching {
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user ?: error("Sign-in failed")
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
        val parts = payload.split('.')
        require(parts.size == 3) { "Encrypted backup format is invalid" }
        val salt = Base64.decode(parts[0], Base64.NO_WRAP or Base64.URL_SAFE)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
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
