package `in`.shraddha.ai.operator.website

internal object AkkpsSubmitAttemptPolicy {
    fun canStart(pendingVerification: Boolean, expectedValues: List<Pair<String, String>>): Boolean {
        if (pendingVerification || expectedValues.isEmpty() || expectedValues.size > 100) return false
        return expectedValues.all { (key, value) ->
            key.isNotBlank() && key.length <= 160 && value.length <= 1_000
        }
    }
}
