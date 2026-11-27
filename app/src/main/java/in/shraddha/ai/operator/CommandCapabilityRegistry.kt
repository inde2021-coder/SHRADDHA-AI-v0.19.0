package `in`.shraddha.ai.operator

import java.util.concurrent.ConcurrentHashMap

enum class AdapterPermissionState { GRANTED, REQUIRED, BLOCKED, UNKNOWN }

data class AdapterCapability(
    val adapterId: String,
    val displayName: String,
    val domains: Set<UnifiedCommandDomain>,
    val actions: Set<UnifiedCommandAction>,
    val permissionKey: String? = null,
    val supportsBackground: Boolean = false
)

data class AdapterRuntimeState(
    val capability: AdapterCapability,
    val enabled: Boolean,
    val permissionState: AdapterPermissionState,
    val unavailableReason: String = ""
)

sealed class CapabilityResolution {
    data class Available(val adapter: AdapterCapability) : CapabilityResolution()
    data class PermissionRequired(val adapter: AdapterCapability, val permissionKey: String) : CapabilityResolution()
    data class Blocked(val adapter: AdapterCapability, val reason: String) : CapabilityResolution()
    data class Ambiguous(val adapterIds: List<String>) : CapabilityResolution()
    data class Unsupported(val domain: UnifiedCommandDomain, val action: UnifiedCommandAction) : CapabilityResolution()
}

/**
 * Runtime registry for real adapters. Nothing is considered supported until a concrete
 * platform adapter registers itself with its currently observed permission state.
 */
class CommandCapabilityRegistry {
    private val adapters = ConcurrentHashMap<String, AdapterRuntimeState>()

    fun register(state: AdapterRuntimeState): Boolean {
        val capability = state.capability
        if (capability.adapterId.isBlank() || capability.adapterId.length > 80 ||
            capability.displayName.isBlank() || capability.domains.isEmpty() ||
            capability.actions.isEmpty() || capability.domains.contains(UnifiedCommandDomain.UNKNOWN) ||
            capability.actions.contains(UnifiedCommandAction.UNKNOWN)) return false
        adapters[capability.adapterId] = state
        return true
    }

    fun updateRuntimeState(
        adapterId: String,
        enabled: Boolean,
        permissionState: AdapterPermissionState,
        unavailableReason: String = ""
    ): Boolean {
        val current = adapters[adapterId] ?: return false
        adapters[adapterId] = current.copy(
            enabled = enabled,
            permissionState = permissionState,
            unavailableReason = unavailableReason.take(160)
        )
        return true
    }

    fun unregister(adapterId: String): Boolean = adapters.remove(adapterId) != null

    fun resolve(domain: UnifiedCommandDomain, action: UnifiedCommandAction): CapabilityResolution {
        if (domain == UnifiedCommandDomain.UNKNOWN || action == UnifiedCommandAction.UNKNOWN) {
            return CapabilityResolution.Unsupported(domain, action)
        }
        val candidates = adapters.values.filter {
            domain in it.capability.domains && action in it.capability.actions
        }.sortedBy { it.capability.adapterId }
        if (candidates.isEmpty()) return CapabilityResolution.Unsupported(domain, action)
        val usable = candidates.filter { it.enabled && it.permissionState == AdapterPermissionState.GRANTED }
        if (usable.size > 1) return CapabilityResolution.Ambiguous(usable.map { it.capability.adapterId })
        if (usable.size == 1) return CapabilityResolution.Available(usable.single().capability)

        val enabled = candidates.filter { it.enabled }
        if (enabled.size > 1) return CapabilityResolution.Ambiguous(enabled.map { it.capability.adapterId })
        val selected = enabled.singleOrNull() ?: candidates.singleOrNull()
            ?: return CapabilityResolution.Ambiguous(candidates.map { it.capability.adapterId })
        if (!selected.enabled || selected.permissionState == AdapterPermissionState.BLOCKED) {
            return CapabilityResolution.Blocked(
                selected.capability,
                selected.unavailableReason.ifBlank { "Adapter is disabled or permission is blocked." }
            )
        }
        val permission = selected.capability.permissionKey
            ?: return CapabilityResolution.Blocked(selected.capability, "Adapter permission state is not granted.")
        return CapabilityResolution.PermissionRequired(selected.capability, permission)
    }

    fun snapshot(): List<AdapterRuntimeState> = adapters.values.sortedBy { it.capability.adapterId }
}
