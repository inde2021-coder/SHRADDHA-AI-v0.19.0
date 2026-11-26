package `in`.shraddha.ai.learning

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.security.MessageDigest

/**
 * Quarantine-first, non-executable knowledge pack lifecycle.
 * Packs are plain Markdown/JSON/text; downloaded code and archives are never executed.
 */
class SkillPackLifecycleManager(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "skill-factory").apply { mkdirs() }
    private val quarantine = File(root, "quarantine").apply { mkdirs() }
    private val installed = File(root, "installed").apply { mkdirs() }
    private val state = File(root, "activation-state.json")

    data class Manifest(
        val packId: String,
        val version: String,
        val title: String,
        val contentUrl: String,
        val sourceUrl: String,
        val licenceId: String,
        val sha256: String,
        val contentType: String,
        val sizeBytes: Long,
        val publisherKeyId: String,
        val signatureBase64: String
    ) {
        fun canonicalPayload(): ByteArray = listOf(
            packId, version, title, contentUrl, sourceUrl, licenceId,
            sha256.lowercase(), contentType, sizeBytes.toString(), publisherKeyId
        ).joinToString("\n").toByteArray(Charsets.UTF_8)

        fun toJson(): JSONObject = JSONObject().apply {
            put("packId", packId); put("version", version); put("title", title)
            put("contentUrl", contentUrl); put("sourceUrl", sourceUrl)
            put("licenceId", licenceId); put("sha256", sha256.lowercase())
            put("contentType", contentType); put("sizeBytes", sizeBytes)
            put("publisherKeyId", publisherKeyId); put("signature", signatureBase64)
        }

        companion object {
            fun parse(json: String): Manifest {
                val o = JSONObject(json)
                return Manifest(
                    o.getString("packId"), o.getString("version"), o.getString("title"),
                    o.getString("contentUrl"), o.getString("sourceUrl"), o.getString("licenceId"),
                    o.getString("sha256"), o.getString("contentType"), o.getLong("sizeBytes"),
                    o.getString("publisherKeyId"), o.getString("signature")
                )
            }
        }
    }

    data class TrustedPublisher(val allowedHost: String, val x509RsaPublicKeyBase64: String)
    data class Approval(
        val userReviewed: Boolean,
        val contentSafetyPassed: Boolean,
        val factualValidationPassed: Boolean,
        val regressionTestsPassed: Boolean,
        val duplicateCheckPassed: Boolean
    )
    data class StagedPack(val packId: String, val version: String, val file: File, val signedPublisher: Boolean)

    fun downloadAndQuarantine(
        manifest: Manifest,
        trustedPublishers: Map<String, TrustedPublisher>
    ): StagedPack {
        validateManifest(manifest)
        val publisher = trustedPublishers[manifest.publisherKeyId]
            ?: throw SecurityException("Publisher key is not pinned by this app release.")
        val url = URL(manifest.contentUrl)
        val source = URL(manifest.sourceUrl)
        require(url.protocol.equals("https", true) && url.host.equals(publisher.allowedHost, true) &&
            source.protocol.equals("https", true) && source.host.equals(publisher.allowedHost, true)) {
            "Content and provenance URLs must use the pinned publisher host over HTTPS."
        }
        val target = File(quarantine, "${manifest.packId}-${manifest.version}.pack")
        val part = File(quarantine, "${manifest.packId}-${manifest.version}.part")
        if (target.exists()) throw IllegalStateException("This pack version is already staged.")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", manifest.contentType)
        }
        try {
            connection.connect()
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "Pack server returned HTTP ${connection.responseCode}." }
            val declared = connection.contentLengthLong
            require(declared in 1..MAX_PACK_BYTES && (declared == 0L || declared == manifest.sizeBytes)) {
                "Pack response size is invalid."
            }
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PACK_BYTES && total <= manifest.sizeBytes) { "Pack exceeded its declared size limit." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(part.length() == manifest.sizeBytes) { "Downloaded pack length does not match its manifest." }
            require(sha256(part).equals(manifest.sha256, true)) { "Pack SHA-256 does not match its manifest." }
            verifySignature(manifest, publisher)
            require(isTextPack(part, manifest.contentType)) { "Executable or unsupported pack content is not accepted." }
            check(part.renameTo(target)) { "Could not move verified pack into quarantine." }
            writeMetadata(target, manifest.toJson().put("stage", "SIGNED_REVIEW"))
            return StagedPack(manifest.packId, manifest.version, target, true)
        } catch (error: Exception) {
            part.delete()
            target.delete()
            File(quarantine, target.name + ".json").delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /** Explicit local import: never auto-activates and is marked unsigned for human review. */
    fun importForReview(
        packId: String,
        version: String,
        title: String,
        licenceId: String,
        sourceDescription: String,
        sourceFile: File,
        explicitConsent: Boolean
    ): StagedPack {
        require(explicitConsent) { "Explicit user import consent is required." }
        require(validId(packId) && validVersion(version))
        require(title.isNotBlank() && title.length <= 120)
        require(licenceId.isNotBlank() && licenceId.length <= 120)
        require(sourceDescription.isNotBlank() && sourceDescription.length <= 300)
        require(sourceFile.isFile && sourceFile.length() in 1..MAX_PACK_BYTES)
        val bytes = sourceFile.readBytes()
        try {
            require(bytes.none { it == 0.toByte() }) { "Binary or executable imports are not accepted." }
            val target = File(quarantine, "$packId-$version.import")
            require(!target.exists()) { "This import version is already staged." }
            target.writeBytes(bytes)
            val metadata = JSONObject().apply {
                put("packId", packId); put("version", version); put("title", title)
                put("licenceId", licenceId); put("source", sourceDescription)
                put("sha256", sha256(target)); put("contentType", "text/plain")
                put("stage", "USER_IMPORT_REVIEW"); put("signedPublisher", false)
            }
            writeMetadata(target, metadata)
            return StagedPack(packId, version, target, false)
        } finally {
            bytes.fill(0)
        }
    }

    /** Requires an explicit review and every deterministic gate; no downloaded code is executed. */
    @Synchronized
    fun approveAndActivate(pack: StagedPack, approval: Approval, automaticLearningEnabled: Boolean): Boolean {
        require(pack.file.parentFile?.canonicalFile == quarantine.canonicalFile && pack.file.isFile)
        require(approval.userReviewed && approval.contentSafetyPassed && approval.factualValidationPassed &&
            approval.regressionTestsPassed && approval.duplicateCheckPassed) {
            "Pack remains quarantined until human review and every independent validation gate passes."
        }
        if (!pack.signedPublisher && automaticLearningEnabled) {
            // User-imported packs are manual-only; automatic learning must not activate unsigned imports.
            throw SecurityException("Unsigned user imports cannot be auto-activated.")
        }
        val targetDir = File(installed, pack.packId).apply { mkdirs() }
        val target = File(targetDir, "${pack.version}.pack")
        if (target.exists()) throw IllegalStateException("This pack version is already installed.")
        val previous = readState().optJSONObject(pack.packId)?.optString("current", "").orEmpty()
        val sourceMetadata = File(quarantine, pack.file.name + ".json")
        val targetMetadata = File(target.parentFile, target.name + ".json")
        require(sourceMetadata.isFile) { "Pack provenance metadata is missing; activation is blocked." }
        check(pack.file.renameTo(target)) { "Could not atomically move the approved pack into installed storage." }
        if (!sourceMetadata.renameTo(targetMetadata)) {
            target.renameTo(pack.file)
            throw IllegalStateException("Could not move pack provenance metadata; pack remains quarantined.")
        }
        val stateJson = readState()
        val entry = JSONObject().apply {
            put("current", pack.version)
            put("previous", previous)
            put("updatedAt", System.currentTimeMillis())
        }
        stateJson.put(pack.packId, entry)
        writeState(stateJson)
        return true
    }

    @Synchronized
    fun rollback(packId: String): Boolean {
        require(validId(packId))
        val stateJson = readState()
        val entry = stateJson.optJSONObject(packId) ?: return false
        val previous = entry.optString("previous", "")
        if (!validVersion(previous)) return false
        if (!File(File(installed, packId), "$previous.pack").isFile) return false
        val current = entry.optString("current", "")
        entry.put("current", previous).put("previous", current).put("updatedAt", System.currentTimeMillis())
        writeState(stateJson)
        return true
    }

    fun activePackFile(packId: String): File? {
        if (!validId(packId)) return null
        val version = readState().optJSONObject(packId)?.optString("current").orEmpty()
        if (!validVersion(version)) return null
        return File(File(installed, packId), "$version.pack").takeIf { it.isFile }
    }

    private fun validateManifest(m: Manifest) {
        require(validId(m.packId) && validVersion(m.version))
        require(m.title.isNotBlank() && m.title.length <= 120)
        require(m.licenceId.isNotBlank() && m.licenceId.length <= 120)
        require(m.contentUrl.startsWith("https://") && m.sourceUrl.startsWith("https://"))
        require(m.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(m.contentType in SUPPORTED_CONTENT_TYPES)
        require(m.sizeBytes in 1..MAX_PACK_BYTES)
        require(m.publisherKeyId.matches(Regex("[A-Za-z0-9._-]{3,80}")))
        require(m.signatureBase64.length in 80..2048)
    }

    private fun verifySignature(m: Manifest, publisher: TrustedPublisher) {
        val keyBytes = Base64.getDecoder().decode(publisher.x509RsaPublicKeyBase64)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(m.canonicalPayload())
        val signature = Base64.getDecoder().decode(m.signatureBase64)
        require(verifier.verify(signature)) { "Publisher manifest signature is invalid." }
    }

    private fun isTextPack(file: File, contentType: String): Boolean {
        if (contentType !in SUPPORTED_CONTENT_TYPES || file.length() > MAX_PACK_BYTES) return false
        var valid = true
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (buffer.take(count).any { it == 0.toByte() }) { valid = false; break }
            }
        }
        return valid
    }
    private fun writeMetadata(content: File, metadata: JSONObject) {
        File(content.parentFile, content.name + ".json").writeText(metadata.toString(), Charsets.UTF_8)
    }

    private fun readState(): JSONObject {
        if (!state.exists()) return JSONObject()
        require(state.isFile) { "Skill Factory activation state is not a regular file." }
        return JSONObject(state.readText(Charsets.UTF_8))
    }
    private fun writeState(value: JSONObject) {
        val temp = File(root, "activation-state.tmp")
        temp.writeText(value.toString(), Charsets.UTF_8)
        FileOutputStream(temp, true).use { it.fd.sync() }
        check(temp.renameTo(state)) { "Could not commit Skill Factory activation state." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validId(value: String) = value.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))
    private fun validVersion(value: String) = value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))

    companion object {
        const val MAX_PACK_BYTES = 10L * 1024L * 1024L
        val SUPPORTED_CONTENT_TYPES = setOf("text/markdown", "text/plain", "application/json")
    }
}
