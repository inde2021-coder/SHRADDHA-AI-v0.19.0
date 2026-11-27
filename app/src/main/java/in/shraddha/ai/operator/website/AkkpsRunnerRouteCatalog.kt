package `in`.shraddha.ai.operator.website

import java.net.URI
import java.util.Locale

/**
 * Runner-only route catalogue extracted from the supplied AKKPS APK.
 *
 * This catalogue identifies screens, not permission to mutate server data.
 * Every action still has to be observed in the signed-in Runner session and
 * verified against the resulting page or record.
 */
enum class AkkpsRunnerModule(val label: String, val basePath: String) {
    HOME("Runner Dashboard", "/runner"),
    FARMERS("Farmers", "/runner/farmers"),
    PLOTS("Plots", "/runner/plots"),
    AWD_REPORTS("AWD Reports", "/runner/awd-reports"),
    PROFILE("Profile", "/runner/profile"),
    SYNC("Data Sync", "/runner/sync")
}

data class AkkpsRunnerRouteMatch(
    val module: AkkpsRunnerModule,
    val path: String,
    val isCreateRoute: Boolean = false,
    val isDetailRoute: Boolean = false
)

/**
 * Exact Runner route allowlist. Admin and Coordinator paths are deliberately
 * excluded even though their route names are present in the APK.
 */
object AkkpsRunnerRouteCatalog {
    private val createRoutes = mapOf(
        "/runner/farmers/new" to AkkpsRunnerModule.FARMERS,
        "/runner/plots/new" to AkkpsRunnerModule.PLOTS,
        // Shared route aliases observed in the APK; AKKPS still enforces Runner access.
        "/plots/new" to AkkpsRunnerModule.PLOTS,
        "/awd-reports/new" to AkkpsRunnerModule.AWD_REPORTS
    )

    fun match(rawPathOrUrl: String): AkkpsRunnerRouteMatch? {
        if (rawPathOrUrl.isBlank() || rawPathOrUrl.length > 2048) return null
        val path = runCatching {
            val isAbsoluteUrl = rawPathOrUrl.contains("://")
            val parsed = if (isAbsoluteUrl) URI(rawPathOrUrl) else URI(null, null, rawPathOrUrl, null)
            if (isAbsoluteUrl) {
                val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
                if (!parsed.scheme.equals("https", true) ||
                    host !in setOf("akkps.ngo", "www.akkps.ngo") ||
                    parsed.userInfo != null ||
                    (parsed.port != -1 && parsed.port != 443)
                ) return null
            }
            parsed.path.orEmpty().trimEnd('/').ifBlank { "/" }
        }.getOrNull() ?: return null
        if (path !in createRoutes && !path.startsWith("/runner/") && path != "/runner") return null
        if (path.split('/').any { it == "." || it == ".." }) return null

        createRoutes[path]?.let { return AkkpsRunnerRouteMatch(it, path, isCreateRoute = true) }

        AkkpsRunnerModule.entries.firstOrNull { path == it.basePath }?.let {
            return AkkpsRunnerRouteMatch(it, path)
        }

        val detailPatterns = listOf(
            Regex("^/runner/farmers/[^/]+$") to AkkpsRunnerModule.FARMERS,
            Regex("^/runner/plots/[^/]+$") to AkkpsRunnerModule.PLOTS,
            Regex("^/runner/awd-reports/[^/]+$") to AkkpsRunnerModule.AWD_REPORTS
        )
        detailPatterns.firstOrNull { (pattern, _) -> pattern.matches(path) }?.let { (pattern, module) ->
            return AkkpsRunnerRouteMatch(module, path, isDetailRoute = true)
        }
        return null
    }

    fun modules(): List<AkkpsRunnerModule> = AkkpsRunnerModule.entries.toList()
}
