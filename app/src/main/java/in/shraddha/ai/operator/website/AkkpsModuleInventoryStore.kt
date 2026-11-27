package `in`.shraddha.ai.operator.website

import org.json.JSONArray
import org.json.JSONObject

data class AkkpsModuleInventoryEntry(
    val module: String,
    val observedAtEpochMillis: Long,
    val capabilities: Map<String, CapabilityState>
)

object AkkpsModuleInventoryCodec {
    private val allowedModules = AkkpsRunnerModule.entries.map { it.name }.toSet()

    fun encode(entries: Map<String, AkkpsModuleInventoryEntry>): String {
        require(entries.size <= AkkpsRunnerModule.entries.size)
        require(entries.all { (key, entry) ->
            key == entry.module && key in allowedModules &&
                entry.observedAtEpochMillis > 0L &&
                entry.capabilities.keys.all { it in WebsiteOperation.entries.map { it.name } }
        }) { "AKKPS module inventory contains unsupported values." }
        return JSONObject().put("version", 1).put("modules", JSONArray().apply {
            entries.values.sortedBy { it.module }.forEach { entry ->
                put(JSONObject().put("module", entry.module)
                    .put("observedAt", entry.observedAtEpochMillis)
                    .put("capabilities", JSONObject().apply {
                        entry.capabilities.forEach { (operation, state) -> put(operation, state.name) }
                    }))
            }
        }).toString()
    }

    fun decode(raw: String): Map<String, AkkpsModuleInventoryEntry> {
        require(raw.length in 1..50_000) { "AKKPS inventory payload size is invalid." }
        val json = JSONObject(raw)
        require(json.optInt("version") == 1) { "AKKPS inventory version is unsupported." }
        val array = json.getJSONArray("modules")
        require(array.length() <= AkkpsRunnerModule.entries.size) { "AKKPS inventory has too many modules." }
        val result = linkedMapOf<String, AkkpsModuleInventoryEntry>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val module = item.getString("module")
            require(module in allowedModules && module !in result) { "AKKPS inventory module is invalid or duplicated." }
            val observedAt = item.getLong("observedAt")
            require(observedAt > 0L && observedAt <= System.currentTimeMillis() + 60_000L) {
                "AKKPS inventory timestamp is invalid."
            }
            val rawCapabilities = item.getJSONObject("capabilities")
            val capabilities = linkedMapOf<String, CapabilityState>()
            val names = rawCapabilities.keys()
            while (names.hasNext()) {
                val operation = names.next()
                require(operation in WebsiteOperation.entries.map(WebsiteOperation::name)) {
                    "AKKPS inventory operation is unsupported."
                }
                capabilities[operation] = CapabilityState.valueOf(rawCapabilities.getString(operation))
            }
            result[module] = AkkpsModuleInventoryEntry(module, observedAt, capabilities)
        }
        return result
    }
}

/** Encrypted local checklist; stores module/capability states only, never farmer records or field values. */
class AkkpsModuleInventoryStore(context: android.content.Context) {
    private val encrypted = `in`.shraddha.ai.brain.EncryptedFeatureStore(context.applicationContext)
    private val key = "akkps_runner_module_inventory_v1"

    fun load(): Map<String, AkkpsModuleInventoryEntry> {
        val raw = encrypted.get(key).getOrThrow()
        return if (raw.isBlank()) emptyMap() else AkkpsModuleInventoryCodec.decode(raw)
    }

    fun record(module: String, capabilities: List<WebsiteCapability>) {
        require(module in AkkpsRunnerModule.entries.map { it.name }) { "Unknown AKKPS Runner module." }
        val current = load().toMutableMap()
        current[module] = AkkpsModuleInventoryEntry(
            module = module,
            observedAtEpochMillis = System.currentTimeMillis(),
            capabilities = capabilities.associate { it.operation.name to it.state }
        )
        encrypted.put(key, AkkpsModuleInventoryCodec.encode(current)).getOrThrow()
    }

    fun clear() {
        encrypted.remove(key)
    }
}
