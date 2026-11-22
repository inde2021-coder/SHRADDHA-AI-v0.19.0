package `in`.shraddha.ai.future

/** Validation boundary for user-requested Android Download Manager downloads. */
object DownloadUrlPolicy {
    data class ValidatedDownload(val httpsUrl: String, val fileName: String)

    fun validate(rawUrl: String, rawFileName: String): ValidatedDownload? {
        val trimmedName = rawFileName.trim()
        if (trimmedName.contains("/") || trimmedName.contains(92.toChar())) return null
        val name = trimmedName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        val uri = runCatching { java.net.URI(rawUrl.trim()).normalize() }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val ipv4 = host.split(".").mapNotNull { it.toIntOrNull() }
        val privateIpv4 = ipv4.size == 4 && (ipv4[0] == 10 || ipv4[0] == 127 || ipv4[0] == 0 ||
            ipv4[0] == 169 && ipv4[1] == 254 || ipv4[0] == 192 && ipv4[1] == 168 ||
            ipv4[0] == 172 && ipv4[1] in 16..31)
        val privateIpv6 = host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
        if (!uri.scheme.equals("https", true) || host.isBlank() || host.contains(":") || host == "localhost" || host.endsWith(".local") ||
            privateIpv4 || privateIpv6 || uri.userInfo != null || name.isBlank() || name == "." || name == ".." ||
            name.contains("/") || name.contains("\\\\")) return null
        return ValidatedDownload(uri.toASCIIString(), name)
    }
}