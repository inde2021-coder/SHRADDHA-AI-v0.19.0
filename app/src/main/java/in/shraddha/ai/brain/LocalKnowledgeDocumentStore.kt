package `in`.shraddha.ai.brain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Local encrypted, source-grounded hybrid RAG. It combines BM25 with deterministic
 * multilingual hashed n-gram vectors (offline semantic approximation; not a neural embedding model).
 * Imports are explicit and citations remain attached to every retrieved chunk.
 */
class LocalKnowledgeDocumentStore(context: Context) {
    data class Document(
        val id: String,
        val title: String,
        val sourceLabel: String,
        val sourceUri: String?,
        val content: String,
        val importedAt: Long
    )

    data class SearchHit(
        val documentId: String,
        val title: String,
        val sourceLabel: String,
        val sourceUri: String?,
        val chunkIndex: Int,
        val chunkCount: Int,
        val excerpt: String,
        val score: Double,
        val citation: String
    )

    private data class Chunk(val index: Int, val text: String)

    private val contextRef = context.applicationContext
    private val store = EncryptedFeatureStore(contextRef)

    @Synchronized
    internal fun importText(
        consent: Boolean,
        id: String,
        title: String,
        sourceLabel: String,
        sourceUri: String? = null,
        content: String,
        importedAt: Long = System.currentTimeMillis(),
        internalBundled: Boolean = false
    ): Result<Unit> = runCatching {
        require(consent) { "Explicit user approval is required before importing personal knowledge." }
        require(id.matches(ID_PATTERN)) { "Document ID is invalid." }
        require(if (internalBundled) id.startsWith("curriculum_") else !id.startsWith("curriculum_")) {
            "The curriculum_ document namespace is reserved for signed APK lesson packs."
        }
        require(title.isNotBlank() && title.length <= 160)
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 200)
        require(sourceUri == null || (sourceUri.length <= 2_048 && isAllowedSourceUri(sourceUri))) {
            "Source URI must be HTTPS or an Android content URI."
        }
        val normalized = content.replace("\u0000", "").trim()
        val documentLimit = if (internalBundled) MAX_BUNDLED_DOCUMENT_CHARS else MAX_DOCUMENT_CHARS
        require(normalized.isNotEmpty() && normalized.length <= documentLimit) {
            "Imported text must contain 1-$documentLimit characters."
        }
        val index = readIndex().toMutableSet()
        require(id in index || index.size < MAX_DOCUMENTS) { "Knowledge document limit reached." }
        val chunks = chunk(normalized)
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("id", id)
            .put("title", title.trim())
            .put("sourceLabel", sourceLabel.trim())
            .put("sourceUri", sourceUri)
            .put("content", normalized)
            .put("importedAt", importedAt)
        index += id
        store.putAll(mapOf(DOC_PREFIX + id to record.toString(), INDEX_KEY to JSONArray(index.sorted()).toString()))
            .getOrThrow()
    }

    /** Installs a lesson shipped inside the signed APK after checking its pinned digest. */
    internal fun importBundledCurriculum(
        id: String,
        title: String,
        version: String,
        sha256: String,
        content: String
    ): Result<Unit> = runCatching {
        require(id.matches(Regex("curriculum_[a-z0-9_]{3,60}")))
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(content.isNotBlank() && content.length <= MAX_BUNDLED_DOCUMENT_CHARS)
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(actual.equals(sha256, ignoreCase = true)) { "Bundled curriculum integrity check failed." }
        importText(
            consent = true,
            id = id,
            title = title,
            sourceLabel = "Shraddha AI built-in curriculum v$version",
            sourceUri = null,
            content = content,
            internalBundled = true
        ).getOrThrow()
    }

    /**
     * OCR-imports a user-selected PDF through Android's document provider. Pages are rendered
     * one at a time and processed with Latin + Devanagari ML Kit recognizers to bound memory.
     * Requires a readable content URI and explicit consent.
     */
    suspend fun importPdf(
        context: Context,
        consent: Boolean,
        id: String,
        title: String,
        sourceLabel: String,
        pdfUri: Uri
    ): Result<Unit> = runCatching {
        require(consent) { "Explicit user approval is required before importing a PDF." }
        require("content".equals(pdfUri.scheme, ignoreCase = true)) { "Choose a PDF from Android Files." }
        val resolver = context.applicationContext.contentResolver
        val mime = resolver.getType(pdfUri)
        require(mime == null || mime.equals("application/pdf", true)) { "Selected file is not a PDF." }
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val extracted = StringBuilder()
        try {
            val descriptor = resolver.openFileDescriptor(pdfUri, "r")
                ?: error("Android could not open the selected PDF.")
            descriptor.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    require(renderer.pageCount in 1..MAX_PDF_PAGES) {
                        "PDF must contain 1-$MAX_PDF_PAGES pages."
                    }
                    for (pageNumber in 0 until renderer.pageCount) {
                        renderer.openPage(pageNumber).use { page ->
                            val scale = minOf(2.0, 1800.0 / maxOf(page.width, page.height))
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val image = InputImage.fromBitmap(bitmap, 0)
                                val latinText = latin.process(image).await().text
                                val devanagariText = devanagari.process(image).await().text
                                val pageText = listOf(latinText, devanagariText)
                                    .filter { it.isNotBlank() }.distinct().joinToString("\n")
                                if (pageText.isNotBlank()) extracted.append("\n[Page ")
                                    .append(pageNumber + 1).append("]\n").append(pageText)
                                require(extracted.length <= MAX_DOCUMENT_CHARS) {
                                    "OCR text exceeds the $MAX_DOCUMENT_CHARS character import limit."
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            latin.close()
            devanagari.close()
        }
        importText(
            consent = true,
            id = id,
            title = title,
            sourceLabel = sourceLabel,
            sourceUri = pdfUri.toString(),
            content = extracted.toString()
        ).getOrThrow()
    }

    @Synchronized
    fun search(query: String, limit: Int = 8): Result<List<SearchHit>> = runCatching {
        require(query.isNotBlank() && query.length <= 300)
        require(limit in 1..20)
        val terms = tokens(query).distinct()
        require(terms.isNotEmpty()) { "Search query has no searchable terms." }
        // Build a small in-memory corpus and rank with BM25 plus local hashed n-gram
        // vector cosine. This is deterministic/offline, not a neural embedding model.
        data class IndexedChunk(
            val id: String,
            val title: String,
            val source: String,
            val uri: String?,
            val chunk: Chunk,
            val chunkCount: Int,
            val words: List<String>,
            val frequencies: Map<String, Int>
        )
        val corpus = readIndex().flatMap { id ->
            val raw = store.get(DOC_PREFIX + id).getOrThrow()
            require(raw.isNotBlank()) { "Knowledge index references a missing document; repair is required." }
            val doc = JSONObject(raw)
            require(doc.getInt("schemaVersion") == 1 && doc.getString("id") == id) {
                "Knowledge document is malformed or belongs to another schema."
            }
            val title = doc.getString("title")
            val sections = chunk(doc.getString("content"))
            sections.map { section ->
                val words = tokens(section.text)
                IndexedChunk(id, title, doc.getString("sourceLabel"),
                    doc.optString("sourceUri").takeIf { it.isNotBlank() },
                    section, sections.size, words, words.groupingBy { it }.eachCount())
            }
        }
        if (corpus.isEmpty()) return@runCatching emptyList()
        val documentFrequency = terms.associateWith { term ->
            corpus.count { term in it.frequencies }
        }
        val averageLength = corpus.sumOf { it.words.size }.toDouble() / corpus.size
        val normalizedQuery = normalizeForMatch(query)
        val queryVector = vectorize(query)
        corpus.mapNotNull { item ->
            if (item.words.isEmpty()) return@mapNotNull null
            val matched = terms.count { it in item.frequencies }
            if (matched == 0) return@mapNotNull null
            val lengthNorm = 1.2 * (1.0 - 0.75 + 0.75 * item.words.size / averageLength.coerceAtLeast(1.0))
            val bm25 = terms.sumOf { term ->
                val frequency = item.frequencies[term] ?: 0
                if (frequency == 0) 0.0 else {
                    val df = documentFrequency[term] ?: 0
                    val idf = kotlin.math.ln(1.0 + (corpus.size - df + 0.5) / (df + 0.5))
                    idf * (frequency * 2.2) / (frequency + lengthNorm)
                }
            }
            val coverage = matched.toDouble() / terms.size
            val phraseBoost = if (normalizedQuery.length >= 3 &&
                normalizeForMatch(item.chunk.text).contains(normalizedQuery)) 2.0 else 0.0
            val titleTerms = tokens(item.title).toSet()
            val titleBoost = terms.count { it in titleTerms } * 0.35
            val semanticApproximation = cosine(queryVector, vectorize(item.chunk.text))
            val score = bm25 + coverage + phraseBoost + titleBoost + semanticApproximation * 2.0
            SearchHit(
                item.id, item.title, item.source, item.uri,
                item.chunk.index, item.chunkCount, item.chunk.text.take(1_200), score,
                "[${item.title} — ${item.source}, section ${item.chunk.index + 1}/${item.chunkCount}]"
            )
        }.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.title }.thenBy { it.chunkIndex })
            .take(limit)
    }

    /**
     * Explicitly syncs imported documents to the authenticated Firebase vault.
     * The cloud vault stores only the encrypted records produced by UnifiedMemoryRepository.
     */
    @Synchronized
    fun syncToCloud(
        cloudConsent: Boolean,
        email: String,
        password: String
    ): Result<Int> = runCatching {
        require(cloudConsent) { "Enable cloud memory sync explicitly before uploading knowledge." }
        val cloud = UnifiedMemoryRepository(storeContext())
        val ids = readIndex().sorted()
        ids.forEach { id ->
            val raw = store.get(DOC_PREFIX + id).getOrThrow()
            require(raw.isNotBlank()) { "A local knowledge document is missing; sync was stopped." }
            cloud.save(cloudKey(id), raw, true, email, password).getOrThrow()
        }
        cloud.save(CLOUD_INDEX_KEY, JSONArray(ids).toString(), true, email, password).getOrThrow()
        ids.size
    }

    /**
     * Restores only documents named by the cloud index, validates each payload, then commits
     * the local encrypted index and records together. Existing local documents are preserved.
     */
    @Synchronized
    fun restoreFromCloud(
        cloudConsent: Boolean,
        email: String,
        password: String
    ): Result<Int> = runCatching {
        require(cloudConsent) { "Enable cloud memory sync explicitly before restoring knowledge." }
        val cloud = UnifiedMemoryRepository(storeContext())
        val index = cloud.load(CLOUD_INDEX_KEY, true, email, password).getOrThrow().value
            ?: return@runCatching 0
        val remoteIdsArray = JSONArray(index)
        require(remoteIdsArray.length() <= MAX_DOCUMENTS) { "Cloud knowledge index exceeds the supported limit." }
        val remoteIds = (0 until remoteIdsArray.length()).map { remoteIdsArray.getString(it) }
        require(remoteIds.distinct().size == remoteIds.size && remoteIds.all { it.matches(ID_PATTERN) }) {
            "Cloud knowledge index is malformed."
        }
        val merged = readIndex().toMutableSet()
        val writes = linkedMapOf<String, String>()
        remoteIds.forEach { id ->
            val raw = cloud.load(cloudKey(id), true, email, password).getOrThrow().value
                ?: error("Cloud knowledge record is missing: $id")
            val record = JSONObject(raw)
            require(record.getInt("schemaVersion") == 1 && record.getString("id") == id) {
                "Cloud knowledge record failed validation."
            }
            require(record.getString("content").length <= MAX_DOCUMENT_CHARS)
            merged += id
            writes[DOC_PREFIX + id] = raw
        }
        require(merged.size <= MAX_DOCUMENTS) { "Restoring these documents would exceed the local knowledge limit." }
        writes[INDEX_KEY] = JSONArray(merged.sorted()).toString()
        store.putAll(writes).getOrThrow()
        remoteIds.size
    }

    private fun cloudKey(id: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "k_" + digest.take(62)
    }

    private fun storeContext(): Context = contextRef.applicationContext

    @Synchronized
    fun listDocuments(): Result<List<Document>> = runCatching {
        readIndex().map { id ->
            val raw = store.get(DOC_PREFIX + id).getOrThrow()
            require(raw.isNotBlank()) { "Knowledge index references a missing document." }
            val o = JSONObject(raw)
            Document(o.getString("id"), o.getString("title"), o.getString("sourceLabel"),
                o.optString("sourceUri").takeIf { it.isNotBlank() }, o.getString("content"), o.getLong("importedAt"))
        }.sortedByDescending { it.importedAt }
    }

    @Synchronized
    fun delete(id: String): Result<Boolean> = runCatching {
        require(id.matches(ID_PATTERN))
        val ids = readIndex()
        if (id !in ids) return@runCatching false
        val next = ids - id
        store.putAllAndRemove(
            mapOf(INDEX_KEY to JSONArray(next.sorted()).toString()),
            setOf(DOC_PREFIX + id)
        ).getOrThrow()
        true
    }

    @Synchronized
    fun clear(): Result<Int> = runCatching {
        val ids = readIndex()
        val emptyIndex = mapOf(INDEX_KEY to "[]")
        val removedKeys = ids.map { DOC_PREFIX + it }.toSet()
        store.putAllAndRemove(emptyIndex, removedKeys).getOrThrow()
        ids.size
    }

    private fun readIndex(): Set<String> {
        val raw = store.get(INDEX_KEY).getOrThrow()
        if (raw.isBlank()) return emptySet()
        val array = JSONArray(raw)
        require(array.length() <= MAX_DOCUMENTS) { "Knowledge index exceeds the supported limit." }
        val ids = (0 until array.length()).map { array.getString(it) }
        require(ids.distinct().size == ids.size && ids.all { it.matches(ID_PATTERN) }) {
            "Knowledge index is malformed."
        }
        return ids.toSet()
    }

    private fun chunk(text: String): List<Chunk> {
        val result = mutableListOf<Chunk>()
        var start = 0
        while (start < text.length) {
            var end = (start + CHUNK_CHARS).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t', '.', '?', '!'), end)
                if (boundary > start + CHUNK_CHARS / 2) end = boundary
            }
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) result += Chunk(result.size, piece)
            if (end >= text.length) break
            start = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
        }
        require(result.size in 1..MAX_CHUNKS_PER_DOCUMENT) { "Imported text creates too many search sections." }
        return result
    }

    private fun normalizeForMatch(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun tokens(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .take(20_000)

    private fun isAllowedSourceUri(uri: String): Boolean =
        uri.startsWith("https://", true) || uri.startsWith("content://", true)

    /**
     * Feature-hashed word + character n-gram vector. It adds word-form and multilingual
     * token overlap signals without network access or downloaded model weights.
     */
    private fun vectorize(value: String): DoubleArray {
        val vector = DoubleArray(VECTOR_SIZE)
        val words = tokens(normalizeForMatch(value))
        words.forEach { word ->
            addFeature(vector, "w:$word", 1.0)
            if (word.length >= 3) {
                for (i in 0..word.length - 3) addFeature(vector, "c:${word.substring(i, i + 3)}", 0.35)
            }
        }
        val norm = kotlin.math.sqrt(vector.sumOf { it * it }).coerceAtLeast(1e-12)
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    private fun addFeature(vector: DoubleArray, feature: String, weight: Double) {
        val hash = feature.hashCode()
        val index = (hash and Int.MAX_VALUE) % VECTOR_SIZE
        val sign = if (((hash ushr 1) and 1) == 0) 1.0 else -1.0
        vector[index] += sign * weight
    }

    private fun cosine(left: DoubleArray, right: DoubleArray): Double =
        left.indices.sumOf { left[it] * right[it] }.coerceIn(-1.0, 1.0)

    companion object {
        private const val VECTOR_SIZE = 384
        private const val MAX_PDF_PAGES = 40
        private const val INDEX_KEY = "knowledge_docs_index_v1"
        private const val CLOUD_INDEX_KEY = "knowledge_docs_cloud_index_v1"
        private const val DOC_PREFIX = "knowledge_doc_"
        private const val MAX_DOCUMENTS = 100
        private const val MAX_DOCUMENT_CHARS = 50_000
        private const val MAX_BUNDLED_DOCUMENT_CHARS = 100_000
        private const val CHUNK_CHARS = 1_200
        private const val CHUNK_OVERLAP = 160
        private const val MAX_CHUNKS_PER_DOCUMENT = 60
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
