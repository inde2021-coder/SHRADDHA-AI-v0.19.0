package `in`.shraddha.ai.future

enum class AgentTransport { CLOUD_GATEWAY_HTTPS, DEVICE_KEY_HTTPS }
data class AgentProviderRoute(val provider: String, val transport: AgentTransport)

object AgentProviderPolicy {
    val supportedProviders = setOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")
    private val preferences = mapOf(
        "PLANNER" to listOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY"),
        "RESEARCHER" to listOf("PERPLEXITY", "CLAUDE", "GEMINI", "OPENAI", "GROQ"),
        "SPECIALIST" to listOf("CLAUDE", "GEMINI", "OPENAI", "GROQ", "PERPLEXITY"),
        "CRITIC" to listOf("OPENAI", "CLAUDE", "GROQ", "GEMINI", "PERPLEXITY"),
        "VERIFIER" to listOf("GROQ", "OPENAI", "CLAUDE", "GEMINI", "PERPLEXITY")
    )

    fun assign(roles: List<CouncilRole>, providers: List<String>): Map<String, String> {
        require(roles.isNotEmpty()) { "At least one agent role is required" }
        val available = providers.map { it.uppercase() }.distinct()
        require(available.isNotEmpty() && available.size <= 5 && available.all { it in supportedProviders }) {
            "Configure between one and five supported providers"
        }
        val counts = available.associateWith { 0 }.toMutableMap()
        return roles.associate { role ->
            val key = when {
                role.name.equals("Planner", true) -> "PLANNER"
                role.name.equals("Researcher", true) -> "RESEARCHER"
                role.name.startsWith("Specialist", true) -> "SPECIALIST"
                role.name.equals("Critic", true) -> "CRITIC"
                role.name.equals("Verifier", true) -> "VERIFIER"
                else -> "SPECIALIST"
            }
            val ordered = preferences.getValue(key).filter { it in available }
            val chosen = ordered.minByOrNull { counts[it] ?: Int.MAX_VALUE }
                ?: available.minByOrNull { counts[it] ?: Int.MAX_VALUE }
                ?: error("No provider route is available")
            counts[chosen] = (counts[chosen] ?: 0) + 1
            role.name to chosen
        }
    }

    fun route(provider: String, cloudGatewayHasProvider: Boolean): AgentProviderRoute {
        val normalized = provider.uppercase()
        require(normalized in supportedProviders) { "Unsupported AI provider: $normalized" }
        return AgentProviderRoute(normalized, if (cloudGatewayHasProvider) AgentTransport.CLOUD_GATEWAY_HTTPS else AgentTransport.DEVICE_KEY_HTTPS)
    }
}
