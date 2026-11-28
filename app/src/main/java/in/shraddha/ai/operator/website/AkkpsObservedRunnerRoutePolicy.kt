package `in`.shraddha.ai.operator.website

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** A module link that was actually visible in the authenticated Runner session. */
data class AkkpsObservedRunnerRoute(
    val label: String,
    val path: String,
    val active: Boolean = false
)

/**
 * Discovers only first-level Runner module routes from same-origin links observed in the
 * signed-in WebView. It never manufactures routes or grants write permission.
 */
object AkkpsObservedRunnerRoutePolicy {
    private val openIntent = Regex(
        "(?:^|\\s)(?:open|show|go to|navigate to|खोलो|खोलें|दिखाओ|दिखाएं|जाओ|पर जाएं)(?:\\s|$)"
    )
    private val deniedSegments = setOf("admin", "coordinator", "superadmin", "login", "logout")

    fun validate(label: String, rawUrl: String, currentUrl: String): AkkpsObservedRunnerRoute? {
        if (label.isBlank() || label.length > 100 || rawUrl.isBlank() || rawUrl.length > 2048 ||
            !AkkpsWebsiteProfile.acceptsUrl(currentUrl)) return null
        return runCatching {
            val current = URI(currentUrl)
            val target = URI(current, rawUrl)
            if (!AkkpsWebsiteProfile.acceptsUrl(target.toString()) ||
                !target.host.equals(current.host, true) ||
                target.userInfo != null || target.rawQuery != null || target.rawFragment != null) return null
            val rawPath = target.rawPath.orEmpty()
            if (rawPath.length > 256 || rawPath.contains('%') || rawPath.contains('\\') ||
                rawPath.contains("//") || rawPath.any { it.code < 0x20 || it.code == 0x7f }) return null
            val path = target.path.orEmpty().trimEnd('/').ifBlank { "/" }
            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.size != 2 || segments[0] != "runner" ||
                segments.any { it == "." || it == ".." || it.lowercase(Locale.ROOT) in deniedSegments } ||
                !segments[1].matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,47}"))) return null
            AkkpsObservedRunnerRoute(label.trim().replace(Regex("\\s+"), " ").take(80), "/${segments.joinToString("/")}")
        }.getOrNull()
    }

    fun resolveCommand(command: String, observed: List<AkkpsObservedRunnerRoute>): AkkpsObservedRunnerRoute? {
        if (command.isBlank() || command.length > 300 || !openIntent.containsMatchIn(command.lowercase(Locale.ROOT))) return null
        val normalized = normalize(command)
        val matches = observed.filter { route ->
            val label = normalize(route.label)
            label.isNotBlank() && (normalized.contains(" $label") || normalized.endsWith(label) || normalized == label)
        }
        return matches.distinctBy { it.path }.singleOrNull()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")
}
