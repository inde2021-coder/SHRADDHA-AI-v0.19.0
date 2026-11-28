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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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

    @Synchronized
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
    @Synchronized
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
    fun approveAndActivate(
        pack: StagedPack,
        approval: Approval,
        automaticLearningEnabled: Boolean,
        trustedPublishers: Map<String, TrustedPublisher> = emptyMap()
    ): Boolean {
        require(validId(pack.packId) && validVersion(pack.version))
        require(pack.file.canonicalFile.parentFile == quarantine.canonicalFile && pack.file.isFile)
        require(pack.file.name == "${pack.packId}-${pack.version}.pack" ||
            pack.file.name == "${pack.packId}-${pack.version}.import") {
            "Staged pack path does not match its declared identity."
        }
        require(approval.userReviewed && approval.contentSafetyPassed && approval.factualValidationPassed &&
            approval.regressionTestsPassed && approval.duplicateCheckPassed) {
            "Pack remains quarantined until human review and every independent validation gate passes."
        }
        val sourceMetadata = File(quarantine, pack.file.name + ".json")
        require(sourceMetadata.isFile) { "Pack provenance metadata is missing; activation is blocked." }
        val metadata = JSONObject(sourceMetadata.readText(Charsets.UTF_8))
        require(metadata.optString("packId") == pack.packId &&
            metadata.optString("version") == pack.version) { "Pack metadata identity does not match the staged pack." }
        val contentType = metadata.optString("contentType")
        require(isTextPack(pack.file, contentType)) { "Only bounded UTF-8 text packs can be activated." }
        require(sha256(pack.file).equals(metadata.optString("sha256"), true)) {
            "Staged pack content changed after quarantine; activation is blocked."
        }
        val signed = metadata.optString("signature").isNotBlank() && metadata.optString("publisherKeyId").isNotBlank()
        require(pack.signedPublisher == signed) { "Staged publisher state does not match signed provenance metadata." }
        if (signed) {
            val manifest = Manifest.parse(metadata.toString())
            validateManifest(manifest)
            val publisher = trustedPublishers[manifest.publisherKeyId]
                ?: throw SecurityException("Publisher key is not pinned by this app release; activation is blocked.")
            val contentUrl = URL(manifest.contentUrl)
            val sourceUrl = URL(manifest.sourceUrl)
            require(contentUrl.protocol.equals("https", true) && contentUrl.host.equals(publisher.allowedHost, true) &&
                sourceUrl.protocol.equals("https", true) && sourceUrl.host.equals(publisher.allowedHost, true)) {
                "Signed pack provenance no longer matches the pinned publisher host."
            }
            verifySignature(manifest, publisher)
        } else {
            require(metadata.optBoolean("signedPublisher", false).not() &&
                metadata.optString("stage") == "USER_IMPORT_REVIEW") {
                "Unsigned content must be an explicit user import in quarantine."
            }
            if (automaticLearningEnabled) {
                throw SecurityException("Unsigned user imports cannot be auto-activated.")
            }
        }
        val targetDir = File(installed, pack.packId).apply { mkdirs() }
        val target = File(targetDir, "${pack.version}.pack")
        if (target.exists()) throw IllegalStateException("This pack version is already installed.")
        val previous = readState().optJSONObject(pack.packId)?.optString("current", "").orEmpty()
        check(pack.file.renameTo(target)) { "Could not atomically move the approved pack into installed storage." }
        val targetMetadata = File(target.parentFile, target.name + ".json")
        if (!sourceMetadata.renameTo(targetMetadata)) {
            target.renameTo(pack.file)
            throw IllegalStateException("Could not move pack provenance metadata; pack remains quarantined.")
        }
        try {
            metadata.put("stage", "ACTIVE")
            writeMetadata(target, metadata)
            val stateJson = readState()
            val entry = JSONObject().apply {
                put("current", pack.version)
                put("previous", previous)
                put("updatedAt", System.currentTimeMillis())
            }
            stateJson.put(pack.packId, entry)
            writeState(stateJson)
        } catch (error: Exception) {
            targetMetadata.renameTo(sourceMetadata)
            target.renameTo(pack.file)
            throw error
        }
        return true
    }

    @Synchronized
    fun rollback(packId: String): Boolean {
        require(validId(packId))
        val stateJson = readState()
        val entry = stateJson.optJSONObject(packId) ?: return false
        val previous = entry.optString("previous", "")
        if (!validVersion(previous)) return false
        val previousFile = File(File(installed, packId), "$previous.pack")
        if (!isStoredPackValid(previousFile)) return false
        val current = entry.optString("current", "")
        entry.put("current", previous).put("previous", current).put("updatedAt", System.currentTimeMillis())
        writeState(stateJson)
        return true
    }

    fun activePackFile(packId: String): File? {
        if (!validId(packId)) return null
        val version = readState().optJSONObject(packId)?.optString("current").orEmpty()
        if (!validVersion(version)) return null
        val active = File(File(installed, packId), "$version.pack")
        return active.takeIf { isStoredPackValid(it) }
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
        if (!file.isFile || contentType !in SUPPORTED_CONTENT_TYPES || file.length() !in 1..MAX_PACK_BYTES) return false
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
            text.none { ch -> ch == '\u0000' || (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') }
        } catch (_: Exception) {
            false
        }
    }

    private fun isStoredPackValid(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            val metadataFile = File(file.parentFile, file.name + ".json")
            if (!metadataFile.isFile) return false
            val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
            metadata.optString("stage") == "ACTIVE" &&
                metadata.optString("packId") == file.parentFile.name &&
                metadata.optString("version") == file.name.removeSuffix(".pack") &&
                sha256(file).equals(metadata.optString("sha256"), true) &&
                isTextPack(file, metadata.optString("contentType"))
        }.getOrDefault(false)
    }
    private fun writeMetadata(content: File, metadata: JSONObject) {
        val target = File(content.parentFile, content.name + ".json")
        val temp = File(content.parentFile, content.name + ".json.tmp")
        val backup = File(content.parentFile, content.name + ".json.bak")
        val bytes = metadata.toString().toByteArray(Charsets.UTF_8)
        try {
            check(!backup.exists() || backup.delete()) { "Could not clear stale Skill Factory metadata backup." }
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (target.exists()) {
                check(target.renameTo(backup)) { "Could not preserve existing Skill Factory provenance metadata." }
            }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IllegalStateException("Could not commit Skill Factory provenance metadata.")
            }
            backup.delete()
        } finally {
            temp.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            bytes.fill(0)
        }
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
