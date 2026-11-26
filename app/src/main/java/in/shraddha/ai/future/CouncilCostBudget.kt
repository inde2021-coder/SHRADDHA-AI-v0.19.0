package `in`.shraddha.ai.future

import android.content.Context
import org.json.JSONArray
import java.time.YearMonth
import java.time.ZoneOffset

/** Local monthly hard-stop for Main Brain council spend. Prices are user supplied. */
object CouncilCostBudget {
    private const val PREFS = "council_cost_budget"
    private const val KEY_LIMIT = "monthly_limit_usd"
    private const val KEY_PRICES = "model_prices"
    private const val KEY_MONTH = "usage_month"
    private const val KEY_SPEND = "month_spend_usd"
    private const val KEY_UNKNOWN = "unknown_usage_seen"
    data class Settings(val monthlyLimitUsd: Double, val pricesJson: String)

    fun settings(context: Context): Settings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(p.getFloat(KEY_LIMIT, 0f).toDouble(), p.getString(KEY_PRICES, "[]") ?: "[]")
    }

    fun saveSettings(context: Context, monthlyLimitUsd: Double, pricesJson: String) {
        require(monthlyLimitUsd.isFinite() && monthlyLimitUsd >= 0.0 && monthlyLimitUsd <= 1_000_000.0) { "Monthly budget must be between 0 and 1,000,000 USD." }
        val parsed = JSONArray(pricesJson)
        require(parsed.length() <= 100) { "At most 100 model prices can be configured." }
        val seen = mutableSetOf<String>()
        repeat(parsed.length()) { i ->
            val item = parsed.getJSONObject(i)
            val provider = item.getString("provider").trim().uppercase()
            val model = item.getString("model").trim()
            val input = item.getDouble("inputUsdPerMillionTokens")
            val output = item.getDouble("outputUsdPerMillionTokens")
            require(provider.isNotBlank() && model.isNotBlank())
            require(input.isFinite() && output.isFinite() && input >= 0.0 && output >= 0.0)
            require(seen.add("$provider|${model.lowercase()}")) { "Duplicate provider/model price: $provider / $model" }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_LIMIT, monthlyLimitUsd.toFloat()).putString(KEY_PRICES, parsed.toString()).apply()
    }

    /**
     * Applies the configured monthly ceiling to every metered Main Brain request, not
     * only council jobs. Unknown usage is a hard stop while a ceiling is enabled.
     */
    @Synchronized
    fun ensureBudgetAllowsCall(context: Context, provider: String) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val limit = p.getFloat(KEY_LIMIT, 0f).toDouble()
        if (limit <= 0.0) return
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        if (p.getString(KEY_MONTH, null) != month) {
            check(p.edit().putString(KEY_MONTH, month).putFloat(KEY_SPEND, 0f)
                .putBoolean(KEY_UNKNOWN, false).commit()) { "Could not reset monthly API budget state." }
        }
        check(!p.getBoolean(KEY_UNKNOWN, false)) {
            "Provider usage is unverified for this month. Further $provider calls are blocked until the usage and price table are reviewed."
        }
        val spent = p.getFloat(KEY_SPEND, 0f).toDouble()
        check(spent < limit) {
            "Monthly API budget reached: estimated spend USD $spent / USD $limit. Further provider calls are blocked."
        }
    }

    /** Use for cloud features whose provider does not return billable token counters. */
    fun requireUsageCountersAvailable(context: Context, feature: String) {
        val limit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_LIMIT, 0f).toDouble()
        check(limit <= 0.0) {
            "$feature does not expose billable token usage, so it is blocked while a monthly API ceiling is enabled. Disable the ceiling or use a metered text route."
        }
    }

    @Synchronized
    fun recordUsageAndEnforce(context: Context, usage: com.Lia.assistant.voice.ProviderTokenUsage) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val limit = p.getFloat(KEY_LIMIT, 0f).toDouble()
        if (limit <= 0.0) return
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        if (p.getString(KEY_MONTH, null) != month) {
            p.edit().putString(KEY_MONTH, month).putFloat(KEY_SPEND, 0f).putBoolean(KEY_UNKNOWN, false).commit()
        }
        val prices = JSONArray(p.getString(KEY_PRICES, "[]") ?: "[]")
        val price = (0 until prices.length()).map { prices.getJSONObject(it) }.firstOrNull {
            it.optString("provider").equals(usage.provider, true) && it.optString("model").equals(usage.model, true)
        }
        if (price == null) {
            p.edit().putBoolean(KEY_UNKNOWN, true).commit()
            throw IllegalStateException("Monthly API budget is enabled, but no price is configured for ${usage.provider}/${usage.model}. Add its exact rates in Settings; further council calls are stopped.")
        }
        if (p.getBoolean(KEY_UNKNOWN, false)) throw IllegalStateException("Monthly API budget is paused because an earlier model usage had no configured price. Update the price table and start a new council task.")
        val added = usage.inputTokens.toDouble() * price.getDouble("inputUsdPerMillionTokens") / 1_000_000.0 +
            usage.outputTokens.toDouble() * price.getDouble("outputUsdPerMillionTokens") / 1_000_000.0
        require(added.isFinite() && added >= 0.0) { "Token cost calculation is invalid." }
        val updated = p.getFloat(KEY_SPEND, 0f).toDouble() + added
        require(updated.isFinite()) { "Monthly API spend overflowed." }
        p.edit().putFloat(KEY_SPEND, updated.toFloat()).commit()
        if (updated > limit) throw IllegalStateException("Monthly API budget exceeded: estimated spend USD ${updated} is above configured limit USD ${limit}. Further council provider calls are stopped.")
    }

    /** Cloud gateways without token evidence must stop budgeted work instead of bypassing the cap. */
    fun stopIfUsageUnknown(context: Context, provider: String, model: String?) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getFloat(KEY_LIMIT, 0f).toDouble() <= 0.0) return
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        if (p.getString(KEY_MONTH, null) != month) {
            p.edit().putString(KEY_MONTH, month).putFloat(KEY_SPEND, 0f).putBoolean(KEY_UNKNOWN, false).commit()
        }
        check(p.edit().putBoolean(KEY_UNKNOWN, true).commit()) { "Could not persist unknown provider usage state." }
        throw IllegalStateException("Monthly API budget is enabled, but the HTTPS gateway returned no token usage for $provider/${model ?: "unknown model"}. The call may already have been billed; further council calls are stopped until usage pricing is reviewed.")
    }

    fun monthlySpendUsd(context: Context): Double {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getString(KEY_MONTH, null) == YearMonth.now(ZoneOffset.UTC).toString()) p.getFloat(KEY_SPEND, 0f).toDouble() else 0.0
    }
}