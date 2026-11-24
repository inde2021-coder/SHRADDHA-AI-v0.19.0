package `in`.shraddha.ai.social

import android.content.Context
import com.Lia.assistant.data.VoiceGatewaySettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object SocialPublishingClient {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()

    /** Uploads selected media to the signed-in user's private Storage folder and returns its tokenized HTTPS URL for Meta fetch. */
    suspend fun uploadMedia(context: Context, uri: Uri, platform: String, mediaKind: String): String = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Firebase before uploading social media.")
        require(platform == "instagram" || platform == "facebook") { "Media URL upload is supported only for Instagram and Facebook Pages." }
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: error("The selected file has no recognized MIME type.")
        val video = mediaKind in setOf("video", "reel", "story_video")
        val allowed = if (video) setOf("video/mp4") else setOf("image/jpeg", "image/png", "image/webp")
        require(mime in allowed) { if (video) "Choose an MP4 video for this post." else "Choose a JPEG, PNG or WebP image for this post." }
        val measuredSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(measuredSize <= 100L * 1024L * 1024L) { "Social media uploads are limited to 100 MiB." }
        val mediaId = UUID.randomUUID().toString().replace("-", "")
        val metadata = StorageMetadata.Builder().setContentType(mime)
            .setCustomMetadata("ownerUid", user.uid).setCustomMetadata("purpose", "social-publishing").build()
        val ref = FirebaseStorage.getInstance().reference.child("social-media/${user.uid}/$mediaId")
        ref.putFile(uri, metadata).await()
        ref.downloadUrl.await().toString()
    }

    /** Encodes bounded Telegram/Mastodon media for the backend's validated 8 MiB upload path. */
    suspend fun encodeBinaryMedia(context: Context, uri: Uri, mediaKind: String): JSONObject = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: error("The selected file has no recognized MIME type.")
        val allowed = if (mediaKind == "image") setOf("image/jpeg", "image/png", "image/webp")
            else if (mediaKind == "video") setOf("video/mp4", "video/quicktime") else emptySet()
        require(mime in allowed) { "Choose a supported image or video file." }
        val declaredSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(declaredSize <= 8L * 1024L * 1024L) { "Telegram and Mastodon media uploads are limited to 8 MiB." }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "Telegram and Mastodon media uploads are limited to 8 MiB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("The selected media file could not be read.")
        require(bytes.isNotEmpty()) { "The selected media file is empty." }
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            else -> error("Unsupported media type.")
        }
        JSONObject()
            .put("mediaBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("mimeType", mime)
            .put("fileName", "shraddha-upload.$extension")
    }

    suspend fun publish(context: Context, platform: String, text: String, mediaKind: String? = null, mediaUrl: String? = null, mediaBase64: String? = null, mimeType: String? = null, fileName: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("platform", platform).put("text", text).put("preview", text).put("confirmed", true)
            if (platform == "instagram" || platform == "facebook") body.put("mediaKind", mediaKind).put("mediaUrl", mediaUrl)
            if (!mediaBase64.isNullOrBlank()) body.put("mediaKind", mediaKind).put("mediaBase64", mediaBase64).put("mimeType", mimeType).put("fileName", fileName)
            request(context, "/api/social/publish", body)
        }

    suspend fun schedule(
        context: Context, platform: String, text: String, scheduledAt: String,
        idempotencyKey: String = UUID.randomUUID().toString().replace("-", ""),
        mediaKind: String? = null, mediaUrl: String? = null
    ): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Firebase before scheduling posts.")
        val idToken = user.getIdToken(false).await().token ?: error("Could not obtain Firebase user token.")
        return withContext(Dispatchers.IO) {
            request(context, "/api/social/schedule", JSONObject()
                .put("platform", platform).put("text", text).put("preview", text).put("confirmed", true)
                .put("scheduledAt", scheduledAt).put("idempotencyKey", idempotencyKey)
                .apply { if (platform == "instagram" || platform == "facebook") { put("mediaKind", mediaKind); put("mediaUrl", mediaUrl) } }, idToken)
        }
    }

    suspend fun list(context: Context): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Firebase to view scheduled posts.")
        val idToken = user.getIdToken(false).await().token ?: error("Could not obtain Firebase user token.")
        return withContext(Dispatchers.IO) {
            request(context, "/api/social/schedule/list", JSONObject(), idToken, methodGet = true)
        }
    }

    suspend fun cancel(context: Context, id: String): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Firebase before cancelling scheduled posts.")
        val idToken = user.getIdToken(false).await().token ?: error("Could not obtain Firebase user token.")
        return withContext(Dispatchers.IO) {
            request(context, "/api/social/schedule/cancel", JSONObject().put("id", id), idToken)
        }
    }

    private fun request(context: Context, path: String, payload: JSONObject, firebaseIdToken: String? = null, methodGet: Boolean = false): JSONObject {
        val config = VoiceGatewaySettings.get(context)
        require(config.readError == null) { config.readError ?: "Gateway settings could not be read." }
        require(config.baseUrl.startsWith("https://") && config.gatewayToken.isNotBlank()) {
            "Configure the secure HTTPS gateway and token in Settings."
        }
        val builder = Request.Builder().url(config.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer " + config.gatewayToken)
        if (methodGet) builder.get() else builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!firebaseIdToken.isNullOrBlank()) builder.header("X-Firebase-ID-Token", firebaseIdToken)
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { error("Social gateway returned invalid JSON.") }
            if (!response.isSuccessful) error(json.optString("error").ifBlank { "Social request failed (HTTP ${response.code})." })
            return json
        }
    }
}
