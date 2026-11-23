package `in`.shraddha.ai.cloud

import `in`.shraddha.ai.BuildConfig

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.messaging.FirebaseMessaging
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

data class CloudBackupRevision(val id: String, val updatedAt: Long)
data class CloudStorageBackupRevision(val id: String, val updatedAt: Long, val sizeBytes: Long)

/** Firebase owner-scoped vault. Client-side AES-GCM means Firestore receives ciphertext only. */
class FirebaseCloudVault(private val context: Context) {
    companion object {
        private val appCheckLock = Any()
        private val appCheckInstalled = mutableSetOf<String>()

        private const val APP_NAME = "shraddha_cloud_vault"
    }

    private fun initialize(): FirebaseApp {
        require(BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()) {
            "Firebase config missing. Add FIREBASE_API_KEY, FIREBASE_APP_ID, FIREBASE_PROJECT_ID and FIREBASE_SENDER_ID to local.properties."
        }
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .apply { if (BuildConfig.FIREBASE_DATABASE_URL.isNotBlank()) setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL) }
            .apply { if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET) }
            .build()
        // FirebaseMessagingService is dispatched through the default FirebaseApp. Initialize
        // it explicitly because this project injects FirebaseOptions instead of google-services.json.
        val defaultApp = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context, options)
        require(defaultApp.options.applicationId == BuildConfig.FIREBASE_APP_ID &&
            defaultApp.options.projectId == BuildConfig.FIREBASE_PROJECT_ID) {
            "The default FirebaseApp belongs to a different project; correct the Firebase configuration before enabling FCM."
        }
        installAppCheck(defaultApp)
        val store = EncryptedFeatureStore(context)
        val pushConsent = store.get("firebase_fcm_consent").getOrNull() == "true" &&
            store.get("firebase_push_consent").getOrNull() == "true"
        FirebaseMessaging.getInstance().isAutoInitEnabled = pushConsent
        val cloudApp = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(context, options, APP_NAME)
        installAppCheck(cloudApp)
        return cloudApp
    }

    private fun installAppCheck(app: FirebaseApp) {
        synchronized(appCheckLock) {
            if (app.name in appCheckInstalled) return
            val factory = if (BuildConfig.DEBUG) {
                val debugFactory = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                debugFactory.getMethod("getInstance").invoke(null) as AppCheckProviderFactory
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(factory)
            appCheckInstalled.add(app.name)
        }
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
        require(plaintext.length <= 100000) { "Encrypted cloud backup is limited to 100,000 characters" }
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user ?: error("Sign-in failed")
            runCatching { registerStoredPushToken(app, user.uid) }
            val encrypted = encrypt(plaintext, password)
            val record = mapOf("format" to "AES-256-GCM/PBKDF2-SHA256-v1", "payload" to encrypted, "updatedAt" to System.currentTimeMillis())
            FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("vault").document(key)
                .set(record).await()
            // Optional Realtime Database mirror; Firestore remains authoritative if RTDB is not configured.
            if (BuildConfig.FIREBASE_DATABASE_URL.isNotBlank()) {
                runCatching { FirebaseDatabase.getInstance(app, BuildConfig.FIREBASE_DATABASE_URL)
                    .reference.child("users").child(user.uid).child("vault").child(key).setValue(record).await() }
            }
            user.uid
        }
    }

    /**
     * Deletes one user-owned encrypted memory record from both Firebase mirrors.
     * RTDB is removed first because Firestore is authoritative during restore; if Firestore
     * deletion fails, its still-present record remains the authoritative copy.
     */
    fun signInAndDelete(email: String, password: String, key: String): Result<Boolean> = runCatching {
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            val firestoreRef = FirebaseFirestore.getInstance(app)
                .collection("users").document(user.uid).collection("vault").document(key)
            val firestoreRecord = firestoreRef.get().await()
            val realtimeRef = if (BuildConfig.FIREBASE_DATABASE_URL.isNotBlank()) {
                FirebaseDatabase.getInstance(app, BuildConfig.FIREBASE_DATABASE_URL)
                    .reference.child("users").child(user.uid).child("vault").child(key)
            } else null
            val realtimeRecord = realtimeRef?.get()?.await()
            val existed = firestoreRecord.exists() || realtimeRecord?.exists() == true
            if (realtimeRecord?.exists() == true) realtimeRef.removeValue().await()
            if (firestoreRecord.exists()) firestoreRef.delete().await()
            existed
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
            runCatching { registerStoredPushToken(app, user.uid) }
            // Firestore is authoritative. Only fall back to RTDB when the Firestore
            // document is genuinely absent; permission/network failures must not look
            // like an empty backup or silently trigger a potentially stale mirror.
            val firestoreDocument = FirebaseFirestore.getInstance(app)
                .collection("users").document(user.uid).collection("vault").document(key)
                .get().await()
            val firestorePayload = firestoreDocument.getString("payload")
            if (firestoreDocument.exists()) {
                require(firestoreDocument.getString("format") == "AES-256-GCM/PBKDF2-SHA256-v1") {
                    "The Firestore backup format is missing or unsupported; restore was stopped."
                }
                require(!firestorePayload.isNullOrBlank()) {
                    "The Firestore backup record exists but has no payload; restore was stopped to protect backup integrity."
                }
            }
            val realtimeSnapshot = if (!firestoreDocument.exists() && BuildConfig.FIREBASE_DATABASE_URL.isNotBlank()) {
                FirebaseDatabase.getInstance(app, BuildConfig.FIREBASE_DATABASE_URL)
                    .reference.child("users").child(user.uid).child("vault").child(key).get().await()
            } else null
            if (realtimeSnapshot?.exists() == true) {
                require(realtimeSnapshot.child("format").getValue(String::class.java) == "AES-256-GCM/PBKDF2-SHA256-v1") {
                    "The Realtime Database backup format is missing or unsupported; restore was stopped."
                }
                require(!realtimeSnapshot.child("payload").getValue(String::class.java).isNullOrBlank()) {
                    "The Realtime Database backup exists but has no payload; restore was stopped to protect backup integrity."
                }
            }
            val realtimePayload = realtimeSnapshot?.child("payload")?.getValue(String::class.java)
            val payload = firestorePayload ?: realtimePayload ?: return@runBlocking null
            require(payload.length <= 190_000) { "Encrypted backup exceeds the supported restore size." }
            decrypt(payload, password)
        }
    }

    fun signInAndSaveBundle(email: String, password: String, bundleJson: String): Result<String> = runCatching {
        require(bundleJson.length <= 100000) { "Encrypted cloud backup is limited to 100,000 characters" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            runCatching { registerStoredPushToken(app, user.uid) }
            val collection = FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("vault")
            val encrypted = encrypt(bundleJson, password)
            val now = System.currentTimeMillis()
            val record = mapOf("format" to "AES-256-GCM/PBKDF2-SHA256-v1", "payload" to encrypted, "updatedAt" to now)
            val revisionId = "selected_device_backup_v1_${now}_${UUID.randomUUID().toString().take(8)}"
            collection.document(revisionId).set(record).await()
            // Keep a stable latest pointer for older app versions and the one-tap restore flow.
            collection.document("selected_device_backup_v1").set(record).await()
            if (BuildConfig.FIREBASE_DATABASE_URL.isNotBlank()) {
                runCatching { FirebaseDatabase.getInstance(app, BuildConfig.FIREBASE_DATABASE_URL)
                    .reference.child("users").child(user.uid).child("vault").child("selected_device_backup_v1")
                    .setValue(record).await() }
            }
            val history = collection.orderBy("updatedAt", Query.Direction.DESCENDING).get().await()
            history.documents.filter { it.id.startsWith("selected_device_backup_v1_") && it.id != "selected_device_backup_v1" }
                .drop(10).forEach { it.reference.delete().await() }
            user.uid
        }
    }

    fun signInAndLoadBundle(email: String, password: String): Result<String?> =
        signInAndLoad(email, password, "selected_device_backup_v1")

    fun signInAndListBundleRevisions(email: String, password: String): Result<List<CloudBackupRevision>> = runCatching {
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("vault")
                .orderBy("updatedAt", Query.Direction.DESCENDING).get().await().documents
                .filter { it.id.startsWith("selected_device_backup_v1_") }
                .take(10)
                .map { CloudBackupRevision(it.id, it.getLong("updatedAt") ?: 0L) }
        }
    }

    fun signInAndLoadBundleRevision(email: String, password: String, revisionId: String): Result<String?> = runCatching {
        require(revisionId.matches(Regex("^selected_device_backup_v1_[0-9]{10,}_[a-f0-9]{8}$"))) { "Invalid recovery revision ID" }
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            val document = FirebaseFirestore.getInstance(app).collection("users").document(user.uid)
                .collection("vault").document(revisionId).get().await()
            if (!document.exists()) return@runBlocking null
            require(document.getString("format") == "AES-256-GCM/PBKDF2-SHA256-v1") {
                "Recovery revision format is missing or unsupported; restore was stopped."
            }
            val payload = document.getString("payload")
                ?.takeIf { it.isNotBlank() }
                ?: error("Recovery revision exists but its encrypted payload is missing; restore was stopped to protect backup integrity.")
            require(payload.length <= 190_000) { "Recovery revision payload exceeds the supported size." }
            decrypt(payload, password)
        }
    }


    /** Uploads an encrypted recovery bundle to Firebase Storage (up to 1.4 MB plaintext). */
    fun signInAndUploadStorageBundle(email: String, password: String, bundleJson: String): Result<String> = runCatching {
        require(bundleJson.isNotBlank() && bundleJson.length <= 1_400_000) { "Storage backup must be 1-1,400,000 characters" }
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            runCatching { registerStoredPushToken(app, user.uid) }
            val encrypted = encrypt(bundleJson, password)
            val bytes = encrypted.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 2 * 1024 * 1024) { "Encrypted backup exceeds the 2 MB Storage limit" }
            val now = System.currentTimeMillis()
            val revisionId = "backup_${now}_${UUID.randomUUID().toString().take(8)}"
            val storagePath = "users/${user.uid}/backups/${revisionId}.enc"
            val storage = FirebaseStorage.getInstance(app)
            val objectRef = storage.reference.child(storagePath)
            var uploaded = false
            var metadataSaved = false
            try {
                val metadata = StorageMetadata.Builder()
                    .setContentType("application/octet-stream")
                    .setCustomMetadata("format", "AES-256-GCM/PBKDF2-SHA256-v1")
                    .setCustomMetadata("updatedAt", now.toString())
                    .build()
                objectRef.putBytes(bytes, metadata).await()
                uploaded = true
                val collection = FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("storage_backups")
                collection.document(revisionId).set(mapOf(
                    "storagePath" to storagePath,
                    "format" to "AES-256-GCM/PBKDF2-SHA256-v1",
                    "updatedAt" to now,
                    "sizeBytes" to bytes.size.toLong()
                )).await()
                metadataSaved = true
                // Retention cleanup must never invalidate a successfully saved new revision.
                runCatching {
                    val history = collection.orderBy("updatedAt", Query.Direction.DESCENDING).get().await()
                    history.documents.filter { it.id.startsWith("backup_") }.drop(10).forEach { old ->
                        val oldPath = old.getString("storagePath")
                        if (oldPath != null && oldPath.startsWith("users/${user.uid}/backups/")) {
                            storage.reference.child(oldPath).delete().await()
                        }
                        old.reference.delete().await()
                    }
                }
                revisionId
            } catch (error: Exception) {
                if (uploaded && !metadataSaved) runCatching { objectRef.delete().await() }
                throw error
            } finally {
                bytes.fill(0)
            }
        }
    }

    fun signInAndListStorageRevisions(email: String, password: String): Result<List<CloudStorageBackupRevision>> = runCatching {
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            runCatching { registerStoredPushToken(app, user.uid) }
            FirebaseFirestore.getInstance(app).collection("users").document(user.uid).collection("storage_backups")
                .orderBy("updatedAt", Query.Direction.DESCENDING).limit(10).get().await().documents
                .map { doc ->
                    CloudStorageBackupRevision(doc.id, doc.getLong("updatedAt") ?: 0L, doc.getLong("sizeBytes") ?: 0L)
                }
        }
    }

    fun signInAndLoadStorageRevision(email: String, password: String, revisionId: String): Result<String?> = runCatching {
        require(revisionId.matches(Regex("^backup_[0-9]{10,}_[a-f0-9]{8}$"))) { "Invalid Storage backup revision ID" }
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            runCatching { registerStoredPushToken(app, user.uid) }
            val metadata = FirebaseFirestore.getInstance(app).collection("users").document(user.uid)
                .collection("storage_backups").document(revisionId).get().await()
            if (!metadata.exists()) return@runBlocking null
            val storagePath = metadata.getString("storagePath") ?: error("Storage path is missing")
            require(storagePath == "users/${user.uid}/backups/${revisionId}.enc")
            require(metadata.getString("format") == "AES-256-GCM/PBKDF2-SHA256-v1") {
                "Storage backup record format is missing or unsupported."
            }
            val size = metadata.getLong("sizeBytes") ?: error("Storage backup size is missing")
            require(size in 1..(2L * 1024 * 1024)) { "Storage backup size is invalid" }
            val storageRef = FirebaseStorage.getInstance(app).reference.child(storagePath)
            val objectMetadata = storageRef.metadata.await()
            require(objectMetadata.contentType == "application/octet-stream")
            require(objectMetadata.getCustomMetadata("format") == "AES-256-GCM/PBKDF2-SHA256-v1")
            require(objectMetadata.getCustomMetadata("updatedAt") == metadata.getLong("updatedAt")?.toString()) {
                "Storage object timestamp does not match Firestore metadata."
            }
            require(objectMetadata.sizeBytes == size) { "Storage object metadata does not match Firestore metadata" }
            val bytes = storageRef.getBytes(2L * 1024 * 1024).await()
            try {
                require(bytes.size.toLong() == size) { "Downloaded backup size does not match metadata" }
                decrypt(String(bytes, Charsets.UTF_8), password)
            } finally {
                bytes.fill(0)
            }
        }
    }

    /** Obtain a device FCM token and register it under the signed-in owner's private document. */
    fun signInAndRegisterPushToken(email: String, password: String, deviceName: String): Result<Unit> = runCatching {
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        require(deviceName.isNotBlank() && deviceName.length <= 80)
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            val store = EncryptedFeatureStore(context)
            val messaging = FirebaseMessaging.getInstance()
            // Persist both consent flags before allowing FCM to create or rotate a token.
            store.put("firebase_fcm_consent", "true").getOrThrow()
            store.put("firebase_push_consent", "true").getOrThrow()
            messaging.isAutoInitEnabled = true
            try {
                val token = messaging.token.await()
                require(token.length in 20..4096) { "Firebase returned an invalid device token" }
                val previous = store.get("firebase_fcm_token").getOrNull().orEmpty()
                val rotated = store.get("firebase_fcm_previous_token").getOrNull().orEmpty()
                if (previous.isNotBlank() && previous != token) deleteDeviceToken(app, user.uid, previous)
                if (rotated.isNotBlank() && rotated != token && rotated != previous) deleteDeviceToken(app, user.uid, rotated)
                store.put("firebase_fcm_token", token).getOrThrow()
                check(store.remove("firebase_fcm_previous_token"))
                registerDeviceToken(app, user.uid, token, deviceName)
            } catch (error: Exception) {
                store.put("firebase_fcm_consent", "false")
                store.put("firebase_push_consent", "false")
                messaging.isAutoInitEnabled = false
                throw error
            }
        }
    }

    private suspend fun registerStoredPushToken(app: FirebaseApp, userId: String) {
        val store = EncryptedFeatureStore(context)
        // A cached token is never uploaded unless the user explicitly enabled push notifications.
        if (store.get("firebase_push_consent").getOrNull() != "true") return
        if (store.get("firebase_fcm_consent").getOrNull() != "true") return
        val token = store.get("firebase_fcm_token").getOrNull().orEmpty()
        val previous = store.get("firebase_fcm_previous_token").getOrNull().orEmpty()
        if (previous.isNotBlank() && previous != token) deleteDeviceToken(app, userId, previous)
        if (token.isNotBlank()) registerDeviceToken(app, userId, token, android.os.Build.MODEL.take(80))
        if (previous.isNotBlank()) store.remove("firebase_fcm_previous_token")
    }

    private suspend fun deleteDeviceToken(app: FirebaseApp, userId: String, token: String) {
        val deviceId = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        FirebaseFirestore.getInstance(app).collection("users").document(userId).collection("devices")
            .document(deviceId).delete().await()
    }

    fun signInAndUnregisterPushToken(email: String, password: String): Result<Unit> = runCatching {
        val normalizedEmail = email.trim()
        require(normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Valid email address required" }
        require(password.length >= 8) { "Password must contain at least 8 characters" }
        runBlocking {
            val app = initialize()
            val user = FirebaseAuth.getInstance(app).signInWithEmailAndPassword(normalizedEmail, password).await().user
                ?: error("Sign-in failed")
            val store = EncryptedFeatureStore(context)
            // Revoke locally before any network request. A failed remote cleanup must not
            // keep auto-init, token callbacks or queued notifications enabled.
            store.put("firebase_push_consent", "false").getOrThrow()
            store.put("firebase_fcm_consent", "false").getOrThrow()
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = false
            val current = store.get("firebase_fcm_token").getOrNull().orEmpty()
            val previous = store.get("firebase_fcm_previous_token").getOrNull().orEmpty()
            val cleanupErrors = mutableListOf<String>()
            if (current.isNotBlank()) runCatching { deleteDeviceToken(app, user.uid, current) }
                .onFailure { cleanupErrors += "current Firestore token: ${it.message}" }
            if (previous.isNotBlank() && previous != current) runCatching { deleteDeviceToken(app, user.uid, previous) }
                .onFailure { cleanupErrors += "previous Firestore token: ${it.message}" }
            runCatching { messaging.deleteToken().await() }
                .onFailure { cleanupErrors += "FCM token deletion: ${it.message}" }
            check(cleanupErrors.isEmpty()) {
                "Push is disabled locally, but remote cleanup needs retry: ${cleanupErrors.joinToString("; ")}"
            }
            check(store.remove("firebase_fcm_token")) { "Could not clear local FCM token after remote revocation" }
            check(store.remove("firebase_fcm_previous_token")) { "Could not clear rotated FCM token after remote revocation" }
        }
    }

    private suspend fun registerDeviceToken(app: FirebaseApp, userId: String, token: String, deviceName: String) {
        val deviceId = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        FirebaseFirestore.getInstance(app).collection("users").document(userId).collection("devices")
            .document(deviceId).set(mapOf(
                "fcmToken" to token,
                "updatedAt" to System.currentTimeMillis(),
                "platform" to "android",
                "deviceName" to deviceName.take(80)
            )).await()
    }

    private fun encrypt(value: String, password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val clear = value.toByteArray(Charsets.UTF_8)
        try {
            val key = derive(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(clear)
            try {
                return listOf(salt, iv, encrypted).joinToString(".") {
                    Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE)
                }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            clear.fill(0)
            salt.fill(0)
            iv.fill(0)
        }
    }

    private fun decrypt(payload: String, password: String): String {
        require(payload.length <= 190_000) { "Encrypted backup is too large." }
        val parts = payload.split('.')
        require(parts.size == 3) { "Encrypted backup format is invalid" }
        val salt = Base64.decode(parts[0], Base64.NO_WRAP or Base64.URL_SAFE)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
        return try {
            require(salt.size == 16 && iv.size == 12 && encrypted.size >= 16) { "Encrypted backup parameters are invalid." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
            val clear = cipher.doFinal(encrypted)
            try {
                String(clear, Charsets.UTF_8)
            } finally {
                clear.fill(0)
            }
        } finally {
            salt.fill(0)
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, 210_000, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
            passwordChars.fill('\u0000')
        }
    }
}
