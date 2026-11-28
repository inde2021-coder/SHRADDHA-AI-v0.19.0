package `in`.shraddha.ai.operator

/** Platforms are explicit so a planner never silently sends an action to the wrong device. */
enum class UniversalCommandPlatform {
    ANDROID_ACCESSIBILITY,
    ANDROID_AKKPS_WEBVIEW,
    WINDOWS_COMPANION,
    BROWSER
}

data class UniversalCommandAdapterCapability(
    val adapterId: String,
    val platform: UniversalCommandPlatform,
    val actions: Set<OperatorActionType>
)

/** A real execution adapter. Each adapter must re-check its own live target before dispatch. */
interface UniversalCommandAdapter {
    val id: String
    val platform: UniversalCommandPlatform
    val supportedActions: Set<OperatorActionType>
    fun execute(step: OperatorStep, confirmed: Boolean): Boolean
}

/** The current Android adapter delegates to observe -> dispatch -> verify OperatorEngine. */
class AndroidAccessibilityCommandAdapter(
    private val executeStep: (OperatorStep, confirmed: Boolean) -> Boolean
) : UniversalCommandAdapter {
    constructor(engine: OperatorEngine) : this(engine::execute)

    override val id: String = "android_accessibility"
    override val platform: UniversalCommandPlatform = UniversalCommandPlatform.ANDROID_ACCESSIBILITY
    override val supportedActions: Set<OperatorActionType> = OperatorActionType.entries.toSet()
    override fun execute(step: OperatorStep, confirmed: Boolean): Boolean = executeStep(step, confirmed)
}

/**
 * Explicit adapter registry for universal command execution.
 * Ambiguous registrations fail closed; an unsupported action is never routed by guesswork.
 */
class UniversalCommandAdapterRegistry {
    private val adapters = linkedMapOf<String, UniversalCommandAdapter>()

    @Synchronized
    fun register(adapter: UniversalCommandAdapter) {
        require(adapter.id.matches(Regex("[a-z][a-z0-9_-]{1,63}")))
        require(adapter.supportedActions.isNotEmpty())
        require(adapter.id !in adapters) { "Universal command adapter ID is already registered." }
        adapters[adapter.id] = adapter
    }

    @Synchronized
    fun capabilities(): List<UniversalCommandAdapterCapability> =
        adapters.values.map { UniversalCommandAdapterCapability(it.id, it.platform, it.supportedActions) }

    @Synchronized
    fun resolve(action: OperatorActionType): UniversalCommandAdapter? =
        adapters.values.filter { action in it.supportedActions }.singleOrNull()

    fun execute(step: OperatorStep, confirmed: Boolean): Boolean {
        val adapter = resolve(step.action) ?: return false
        return runCatching { adapter.execute(step, confirmed) }.getOrDefault(false)
    }
}
