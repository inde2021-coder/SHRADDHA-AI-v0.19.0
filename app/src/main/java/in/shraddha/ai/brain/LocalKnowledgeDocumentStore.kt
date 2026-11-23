package `in`.shraddha.ai.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Local, encrypted, source-grounded text retrieval. Content is supplied by an explicit user
 * import; this class does not crawl URLs, create embeddings or claim semantic/vector search.
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

    private val store = EncryptedFeatureStore(context.applicationContext)

    @Synchronized
    fun importText(
        consent: Boolean,
        id: String,
        title: String,
        sourceLabel: String,
        sourceUri: String? = null,
        content: String,
        importedAt: Long = System.currentTimeMillis()
    ): Result<Unit> = runCatching {
        require(consent) { "Explicit user approval is required before importing personal knowledge." }
        require(id.matches(ID_PATTERN)) { "Document ID is invalid." }
        require(title.isNotBlank() && title.length <= 160)
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 200)
        require(sourceUri == null || (sourceUri.length <= 2_048 && isAllowedSourceUri(sourceUri))) {
            "Source URI must be HTTPS or an Android content URI."
        }
        val normalized = content.replace("\u0000", "").trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_DOCUMENT_CHARS) {
            "Imported text must contain 1-$MAX_DOCUMENT_CHARS characters."
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

    @Synchronized
    fun search(query: String, limit: Int = 8): Result<List<SearchHit>> = runCatching {
        require(query.isNotBlank() && query.length <= 300)
        require(limit in 1..20)
        val terms = tokens(query).distinct()
        require(terms.isNotEmpty()) { "Search query has no searchable terms." }
        readIndex().flatMap { id ->
            val raw = store.get(DOC_PREFIX + id).getOrThrow()
            require(raw.isNotBlank()) { "Knowledge index references a missing document; repair is required." }
            val doc = JSONObject(raw)
            require(doc.getInt("schemaVersion") == 1 && doc.getString("id") == id) {
                "Knowledge document is malformed or belongs to another schema."
            }
            val chunks = chunk(doc.getString("content"))
            chunks.mapNotNull { chunk ->
                val text = chunk.text
                val words = tokens(text)
                if (words.isEmpty()) return@mapNotNull null
                val frequencies = words.groupingBy { it }.eachCount()
                val matched = terms.count { it in frequencies }
                if (matched == 0) return@mapNotNull null
                val tf = terms.sumOf { term -> (frequencies[term] ?: 0).coerceAtMost(5).toDouble() }
                val coverage = matched.toDouble() / terms.size
                val score = coverage * (1.0 + tf / terms.size)
                val chunkIndex = chunk.index
                val title = doc.getString("title")
                val source = doc.getString("sourceLabel")
                SearchHit(
                    id, title, source, doc.optString("sourceUri").takeIf { it.isNotBlank() },
                    chunkIndex, chunks.size, text.take(1_200), score,
                    "[$title — $source, section ${chunkIndex + 1}/${chunks.length()}]"
                )
            }
        }.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.title }.thenBy { it.chunkIndex })
            .take(limit)
    }

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

    private fun tokens(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .take(20_000)

    private fun isAllowedSourceUri(uri: String): Boolean =
        uri.startsWith("https://", true) || uri.startsWith("content://", true)

    companion object {
        private const val INDEX_KEY = "knowledge_docs_index_v1"
        private const val DOC_PREFIX = "knowledge_doc_"
        private const val MAX_DOCUMENTS = 50
        private const val MAX_DOCUMENT_CHARS = 50_000
        private const val CHUNK_CHARS = 1_200
        private const val CHUNK_OVERLAP = 160
        private const val MAX_CHUNKS_PER_DOCUMENT = 60
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
