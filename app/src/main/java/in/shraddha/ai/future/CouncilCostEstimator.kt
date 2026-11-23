package `in`.shraddha.ai.future

/**
 * Estimates API spend from provider-reported token usage and an explicit, user-maintained
 * price table. It never guesses a model price or treats missing usage as free.
 */
object CouncilCostEstimator {
    data class TokenUsage(
        val provider: String,
        val model: String,
        val inputTokens: Long,
        val outputTokens: Long
    )

    data class ModelPrice(
        val provider: String,
        val model: String,
        val inputUsdPerMillionTokens: Double,
        val outputUsdPerMillionTokens: Double
    )

    data class ProviderCost(
        val provider: String,
        val model: String,
        val inputCostUsd: Double,
        val outputCostUsd: Double,
        val totalCostUsd: Double
    )

    data class Estimate(
        /** Null means at least one usage item had no matching price; never report a partial sum as total. */
        val totalUsd: Double?,
        val knownSubtotalUsd: Double,
        val pricedUsage: List<ProviderCost>,
        val unpricedUsage: List<TokenUsage>,
        val budgetUsd: Double?,
        /** Null means total spend or budget is unknown. */
        val withinBudget: Boolean?
    )

    fun estimate(
        usage: List<TokenUsage>,
        prices: List<ModelPrice>,
        budgetUsd: Double? = null
    ): Estimate {
        require(usage.size <= 1_000) { "Usage report contains too many entries." }
        require(prices.size <= 1_000) { "Price table contains too many entries." }
        require(budgetUsd == null || (budgetUsd.isFinite() && budgetUsd >= 0.0)) {
            "Budget must be a finite non-negative USD amount."
        }
        usage.forEach {
            require(it.provider.isNotBlank() && it.model.isNotBlank())
            require(it.inputTokens >= 0 && it.outputTokens >= 0)
        }
        prices.forEach {
            require(it.provider.isNotBlank() && it.model.isNotBlank())
            require(it.inputUsdPerMillionTokens.isFinite() && it.inputUsdPerMillionTokens >= 0.0)
            require(it.outputUsdPerMillionTokens.isFinite() && it.outputUsdPerMillionTokens >= 0.0)
        }
        val priceMap = prices.associateBy { key(it.provider, it.model) }
        require(priceMap.size == prices.size) { "Duplicate provider/model prices are not allowed." }

        val priced = mutableListOf<ProviderCost>()
        val unpriced = mutableListOf<TokenUsage>()
        usage.forEach { item ->
            val price = priceMap[key(item.provider, item.model)]
            if (price == null) {
                unpriced += item
            } else {
                val input = item.inputTokens.toDouble() * price.inputUsdPerMillionTokens / 1_000_000.0
                val output = item.outputTokens.toDouble() * price.outputUsdPerMillionTokens / 1_000_000.0
                val total = input + output
                require(input.isFinite() && output.isFinite() && total.isFinite()) {
                    "Estimated cost overflowed; check token counts and model prices."
                }
                priced += ProviderCost(item.provider, item.model, input, output, total)
            }
        }
        val subtotal = priced.sumOf { it.totalCostUsd }
        require(subtotal.isFinite()) { "Estimated cost overflowed." }
        val total = if (unpriced.isEmpty()) subtotal else null
        return Estimate(
            totalUsd = total,
            knownSubtotalUsd = subtotal,
            pricedUsage = priced.toList(),
            unpricedUsage = unpriced.toList(),
            budgetUsd = budgetUsd,
            withinBudget = if (total != null && budgetUsd != null) total <= budgetUsd else null
        )
    }

    private fun key(provider: String, model: String) =
        provider.trim().uppercase(java.util.Locale.ROOT) + "|" + model.trim().lowercase(java.util.Locale.ROOT)
}
