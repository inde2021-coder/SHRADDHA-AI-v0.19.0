package `in`.shraddha.ai.operator.website

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AkkpsObservedModuleInventoryEntry(
    val route: AkkpsObservedRunnerRoute,
    val observedAtEpochMillis: Long,
    val capabilities: Map<String, CapabilityState>
)

/** Codec for the post-login checklist; stores route metadata and capability states only. */
object AkkpsObservedModuleInventoryCodec {
    fun encode(entries: Map<String, AkkpsObservedModuleInventoryEntry>): String {
        require(entries.size <= 30)
        require(entries.all { (path, entry) ->
            path == entry.route.path &&
                AkkpsObservedRunnerRoutePolicy.validate(
                    entry.route.label, entry.route.path, AkkpsWebsiteProfile.CANONICAL_URL
                ) != null &&
                entry.observedAtEpochMillis > 0L &&
                entry.capabilities.keys.all { key -> WebsiteOperation.entries.any { it.name == key } }
        }) { "Observed AKKPS module inventory is invalid." }
        return JSONObject().put("version", 1).put("modules", JSONArray().apply {
            entries.values.sortedBy { it.route.path }.forEach { entry ->
                put(JSONObject()
                    .put("label", entry.route.label)
                    .put("path", entry.route.path)
                    .put("active", entry.route.active)
                    .put("observedAt", entry.observedAtEpochMillis)
                    .put("capabilities", JSONObject().apply {
                        entry.capabilities.forEach { (operation, state) -> put(operation, state.name) }
                    }))
            }
        }).toString()
    }

    fun decode(raw: String): Map<String, AkkpsObservedModuleInventoryEntry> {
        require(raw.length in 1..50_000)
        val root = JSONObject(raw)
        require(root.optInt("version") == 1)
        val modules = root.getJSONArray("modules")
        require(modules.length() <= 30)
        val result = linkedMapOf<String, AkkpsObservedModuleInventoryEntry>()
        for (index in 0 until modules.length()) {
            val item = modules.getJSONObject(index)
            val label = item.getString("label")
            val path = item.getString("path")
            val route = AkkpsObservedRunnerRoutePolicy.validate(
                label, path, AkkpsWebsiteProfile.CANONICAL_URL
            ) ?: error("Observed AKKPS module route is invalid.")
            require(route.path !in result)
            val observedAt = item.getLong("observedAt")
            require(observedAt > 0L && observedAt <= System.currentTimeMillis() + 60_000L)
            val rawCapabilities = item.getJSONObject("capabilities")
            val capabilities = linkedMapOf<String, CapabilityState>()
            val keys = rawCapabilities.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(WebsiteOperation.entries.any { it.name == key })
                capabilities[key] = CapabilityState.valueOf(rawCapabilities.getString(key))
            }
            result[route.path] = AkkpsObservedModuleInventoryEntry(
                route.copy(active = item.optBoolean("active")), observedAt, capabilities
            )
        }
        return result
    }
}

/** Encrypted checklist of modules observed after the user signs in to AKKPS Runner. */
class AkkpsObservedModuleInventoryStore(context: Context) {
    private val encrypted = `in`.shraddha.ai.brain.EncryptedFeatureStore(context.applicationContext)
    private val key = "akkps_runner_observed_module_inventory_v1"

    fun load(): Map<String, AkkpsObservedModuleInventoryEntry> {
        val raw = encrypted.get(key).getOrThrow()
        return if (raw.isBlank()) emptyMap() else AkkpsObservedModuleInventoryCodec.decode(raw)
    }

    /** Add newly seen links as UNKNOWN without erasing prior inspection results. */
    fun registerObserved(routes: List<AkkpsObservedRunnerRoute>) {
        val current = load().toMutableMap()
        routes.take(30).forEach { route ->
            val safe = AkkpsObservedRunnerRoutePolicy.validate(
                route.label, route.path, AkkpsWebsiteProfile.CANONICAL_URL
            ) ?: return@forEach
            if (safe.path !in current) {
                current[safe.path] = AkkpsObservedModuleInventoryEntry(
                    safe,
                    System.currentTimeMillis(),
                    AkkpsWebsiteProfile.initialCapabilities().associate { it.operation.name to it.state }
                )
            }
        }
        encrypted.put(key, AkkpsObservedModuleInventoryCodec.encode(current)).getOrThrow()
    }

    /** Replace capability states only after the corresponding page has been inspected. */
    fun record(route: AkkpsObservedRunnerRoute, capabilities: List<WebsiteCapability>) {
        val safe = AkkpsObservedRunnerRoutePolicy.validate(
            route.label, route.path, AkkpsWebsiteProfile.CANONICAL_URL
        ) ?: error("Observed AKKPS module route is invalid.")
        val current = load().toMutableMap()
        current[safe.path] = AkkpsObservedModuleInventoryEntry(
            safe.copy(active = route.active),
            System.currentTimeMillis(),
            capabilities.associate { it.operation.name to it.state }
        )
        require(current.size <= 30)
        encrypted.put(key, AkkpsObservedModuleInventoryCodec.encode(current)).getOrThrow()
    }

    fun clear() { encrypted.remove(key) }
}
