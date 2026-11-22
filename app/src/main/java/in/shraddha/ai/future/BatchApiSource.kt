package `in`.shraddha.ai.future

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Bounded HTTPS JSON API adapter with page/pageSize pagination and caller-supplied auth. */
class BatchApiSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS).build()
) {
    data class Config(
        val url: String,
        val dataField: String = "data",
        val pageParameter: String = "page",
        val pageSizeParameter: String = "pageSize",
        val pageSize: Int = 100,
        val firstPage: Int = 1,
        val maxPages: Int = 10,
        val headers: Map<String, String> = emptyMap()
    )

    fun read(config: Config): List<BatchProcessingEngine.Record> {
        val uri = URI(config.url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "API source must use HTTPS without embedded credentials" }
        require(config.pageSize in 1..BatchProcessingEngine.MAX_RECORDS)
        require(config.firstPage >= 0 && config.maxPages in 1..BatchProcessingEngine.MAX_RECORDS)
        require(config.dataField.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
        require(config.pageParameter.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
        require(config.pageSizeParameter.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
        require(config.headers.size <= 12 && config.headers.all { (k, v) ->
            k.matches(Regex("[A-Za-z0-9-]{1,64}")) && v.length <= 4096 && !k.equals("Host", true)
        }) { "Invalid API headers" }

        val result = mutableListOf<BatchProcessingEngine.Record>()
        val ids = HashSet<String>()
        for (offset in 0 until config.maxPages) {
            val page = config.firstPage + offset
            val separator = if (config.url.contains("?")) "&" else "?"
            val url = config.url + separator + encode(config.pageParameter) + "=" + page +
                "&" + encode(config.pageSizeParameter) + "=" + config.pageSize
            val builder = Request.Builder().url(url).get()
            config.headers.forEach { (key, value) -> builder.header(key, value) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("API returned HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                require(body.length <= 8 * 1024 * 1024) { "API response exceeds 8 MB" }
                val root = JSONObject(body)
                val array = root.optJSONArray(config.dataField) ?: throw IllegalArgumentException("API response field '${config.dataField}' is not an array")
                require(array.length() <= config.pageSize) { "API returned more rows than requested page size" }
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: throw IllegalArgumentException("API record $i is not an object")
                    val id = item.optString("id").ifBlank { "api_${page}_${i + 1}" }
                    require(ids.add(id)) { "Duplicate API record ID: $id" }
                    result += BatchProcessingEngine.Record(id, item)
                    require(result.size <= BatchProcessingEngine.MAX_RECORDS) { "API source exceeds 1,000 records" }
                }
                if (array.length() < config.pageSize || array.length() == 0) return result
            }
        }
        return result
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
