package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded, resumable engine for up to 1,000 structured records.
 *
 * The caller supplies a source adapter (CSV/Excel/DB/API) and an idempotent processor.
 * Only record hashes and status/error metadata are checkpointed; source values are not
 * copied into the checkpoint. Failed rows are returned separately for an export/report.
 */
class BatchProcessingEngine(
    context: Context,
    private val maxParallel: Int = 4,
    private val checkpointEvery: Int = 10
) {
    data class Record(val id: String, val values: JSONObject)
    data class Failure(val recordId: String, val reason: String)
    data class Report(
        val batchId: String,
        val total: Int,
        val succeeded: Int,
        val duplicates: Int,
        val failed: List<Failure>,
        val pending: Int,
        val resumed: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("batch_id", batchId).put("total", total).put("succeeded", succeeded)
            .put("duplicates", duplicates).put("failed", JSONArray().also { a ->
                failed.forEach { a.put(JSONObject().put("record_id", it.recordId).put("reason", it.reason)) }
            }).put("pending", pending).put("resumed", resumed)
    }

    private enum class State { PENDING, RUNNING, SUCCEEDED, DUPLICATE, FAILED }
    private val store = EncryptedFeatureStore(context.applicationContext)
    private val checkpointPrefix = "batch_checkpoint_"

    init {
        require(maxParallel in 1..8) { "maxParallel must be between 1 and 8" }
        require(checkpointEvery in 1..100) { "checkpointEvery must be between 1 and 100" }
    }

    /**
     * Processes no more than 1,000 records. Work is limited to maxParallel workers.
     * Processor must be idempotent: a crash after an external side effect but before
     * checkpoint commit can otherwise cause that record to be retried.
     */
    fun run(
        batchId: String,
        records: List<Record>,
        retryFailed: Boolean = false,
        processor: (Record) -> Unit
    ): Report {
        require(batchId.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid batch ID" }
        require(records.size in 1..MAX_RECORDS) { "Batch must contain 1-$MAX_RECORDS records" }
        require(records.map { it.id }.distinct().size == records.size) { "Record IDs must be unique" }
        records.forEach { require(it.id.isNotBlank() && it.id.length <= 32) { "Record IDs must be 1-32 characters" } }

        val key = checkpointPrefix + batchId
        val loaded = store.get(key).getOrNull().orEmpty()
        val resumed = loaded.isNotBlank()
        val checkpoint = if (resumed) JSONObject(loaded) else newCheckpoint(records)
        validateCheckpoint(checkpoint, records)
        val states = checkpoint.getJSONArray("states")
        val errors = checkpoint.getJSONArray("errors")
        if (retryFailed) records.indices.filter { states.getString(it) == State.FAILED.name }.forEach {
            states.put(it, State.PENDING.name)
            errors.put(it, JSONObject.NULL)
        }
        val hashes = records.map { fingerprint(it.values) }
        val savedHashes = checkpoint.optJSONArray("record_hashes") ?: error("Checkpoint fingerprints are missing")
        require(savedHashes.length() == hashes.size) { "Checkpoint fingerprints are incomplete" }
        hashes.indices.forEach { require(savedHashes.getString(it) == hashes[it]) { "Record content changed since checkpoint; refusing unsafe resume" } }
        val firstByHash = HashSet<String>()
        for (i in records.indices) {
            if (!firstByHash.add(hashes[i]) && states.getString(i) == State.PENDING.name) {
                states.put(i, State.DUPLICATE.name)
            }
        }
        // RUNNING means the process died after claim. Never assume the external side effect
        // did not happen; retry only because processor contract explicitly requires idempotency.
        for (i in records.indices) if (states.getString(i) == State.RUNNING.name) states.put(i, State.PENDING.name)
        persist(key, checkpoint)

        val executor = Executors.newFixedThreadPool(maxParallel)
        try {
            while (true) {
                val indexes = synchronized(states) {
                    records.indices.filter { states.getString(it) == State.PENDING.name }.take(maxParallel)
                        .also { batch -> batch.forEach { states.put(it, State.RUNNING.name) } }
                }
                if (indexes.isEmpty()) break
                persist(key, checkpoint)
                val futures = indexes.map { index ->
                    executor.submit(Callable {
                        runCatching { processor(records[index]) }
                    })
                }
                var changed = 0
                for (position in indexes.indices) {
                    val index = indexes[position]
                    val result = futures[position].get()
                    if (result.isSuccess) {
                        states.put(index, State.SUCCEEDED.name)
                        errors.put(index, JSONObject.NULL)
                    } else {
                        val reason = (result.exceptionOrNull()?.message ?: "Processing failed").take(24)
                        states.put(index, State.FAILED.name)
                        errors.put(index, reason)
                    }
                    changed++
                    if (changed % checkpointEvery == 0) persist(key, checkpoint)
                }
                persist(key, checkpoint)
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS)
        }

        val failures = mutableListOf<Failure>()
        var succeeded = 0
        var duplicates = 0
        var pending = 0
        for (i in records.indices) when (states.getString(i)) {
            State.SUCCEEDED.name -> succeeded++
            State.DUPLICATE.name -> duplicates++
            State.FAILED.name -> failures += Failure(records[i].id, errors.optString(i, "Processing failed"))
            else -> pending++
        }
        persist(key, checkpoint)
        return Report(batchId, records.size, succeeded, duplicates, failures, pending, resumed)
    }

    fun readCheckpoint(batchId: String): JSONObject? {
        require(batchId.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        return store.get(checkpointPrefix + batchId).getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun clearCheckpoint(batchId: String): Boolean {
        require(batchId.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        return store.remove(checkpointPrefix + batchId)
    }

    private fun newCheckpoint(records: List<Record>) = JSONObject()
        .put("version", 1).put("total", records.size)
        .put("record_ids", JSONArray().also { a -> records.forEach { a.put(it.id) } })
        .put("record_hashes", JSONArray().also { a -> records.forEach { a.put(fingerprint(it.values)) } })
        .put("states", JSONArray().also { a -> records.forEach { _ -> a.put(State.PENDING.name) } })
        .put("errors", JSONArray().also { a -> records.forEach { _ -> a.put(JSONObject.NULL) } })
        .put("updated_at", System.currentTimeMillis())

    private fun validateCheckpoint(checkpoint: JSONObject, records: List<Record>) {
        require(checkpoint.optInt("version") == 1) { "Unsupported checkpoint version" }
        require(checkpoint.optInt("total") == records.size) { "Checkpoint record count does not match input" }
        val ids = checkpoint.optJSONArray("record_ids") ?: error("Checkpoint IDs are missing")
        val states = checkpoint.optJSONArray("states") ?: error("Checkpoint states are missing")
        val hashes = checkpoint.optJSONArray("record_hashes") ?: error("Checkpoint fingerprints are missing")
        require(ids.length() == records.size && states.length() == records.size && hashes.length() == records.size) { "Checkpoint is incomplete" }
        records.indices.forEach { require(ids.getString(it) == records[it].id) { "Input order/identity changed; refusing unsafe resume" } }
    }

    private fun persist(key: String, checkpoint: JSONObject) {
        checkpoint.put("updated_at", System.currentTimeMillis())
        store.put(key, checkpoint.toString()).getOrThrow()
    }

    private fun fingerprint(value: JSONObject): String {
        val canonical = value.keys().asSequence().toList().sorted().joinToString("|") { key ->
            "$key=${value.opt(key)?.toString().orEmpty()}"
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }

    companion object { const val MAX_RECORDS = 1000 }
}
