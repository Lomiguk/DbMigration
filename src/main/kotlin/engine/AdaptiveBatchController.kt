package engine

data class AdaptiveBatchConfig(
    val enabled: Boolean = false,
    val minBatchSize: Int = 250,
    val maxBatchSize: Int = 4_000,
    val targetBatchDurationMs: Long = 150
) {
    companion object {
        val DISABLED = AdaptiveBatchConfig()
    }
}

data class AdaptiveBatchDecision(
    val previousBatchSize: Int,
    val nextBatchSize: Int,
    val reason: String
)

class AdaptiveBatchController(
    private val config: AdaptiveBatchConfig,
    initialBatchSize: Int
) {
    private val minBatchSize = config.minBatchSize.coerceAtLeast(1)
    private val maxBatchSize = config.maxBatchSize.coerceAtLeast(minBatchSize)
    private val targetBatchDurationMs = config.targetBatchDurationMs.coerceAtLeast(1)

    var currentBatchSize: Int = if (config.enabled) {
        initialBatchSize.coerceAtLeast(1).coerceIn(minBatchSize, maxBatchSize)
    } else {
        initialBatchSize.coerceAtLeast(1)
    }
        private set

    fun onBatchCompleted(durationMs: Long): AdaptiveBatchDecision? {
        if (!config.enabled || durationMs <= 0) return null

        val previous = currentBatchSize
        val next = when {
            durationMs > targetBatchDurationMs * 3 / 2 ->
                (currentBatchSize / 2).coerceAtLeast(minBatchSize)

            durationMs < targetBatchDurationMs / 2 ->
                (currentBatchSize + maxOf(1, currentBatchSize / 4)).coerceAtMost(maxBatchSize)

            else -> currentBatchSize
        }

        if (next == previous) return null

        currentBatchSize = next
        val reason = if (next < previous) {
            "batch duration ${durationMs}ms is above target ${targetBatchDurationMs}ms"
        } else {
            "batch duration ${durationMs}ms is below target ${targetBatchDurationMs}ms"
        }

        return AdaptiveBatchDecision(previous, next, reason)
    }
}
