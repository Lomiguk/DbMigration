package unit

import engine.AdaptiveBatchConfig
import engine.AdaptiveBatchController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdaptiveBatchControllerTest {

    @Test
    fun `should keep batch size when adaptive mode is disabled`() {
        val controller = AdaptiveBatchController(
            AdaptiveBatchConfig(enabled = false, minBatchSize = 100, maxBatchSize = 10_000),
            initialBatchSize = 1_000
        )

        val decision = controller.onBatchCompleted(durationMs = 10_000, rowsInBatch = 1_000)

        assertThat(decision).isNull()
        assertThat(controller.currentBatchSize).isEqualTo(1_000)
    }

    @Test
    fun `should not clamp initial batch size when adaptive mode is disabled`() {
        val controller = AdaptiveBatchController(
            AdaptiveBatchConfig(enabled = false, minBatchSize = 250, maxBatchSize = 10_000),
            initialBatchSize = 100
        )

        assertThat(controller.currentBatchSize).isEqualTo(100)
    }

    @Test
    fun `should decrease batch size when batch is slower than target`() {
        val controller = AdaptiveBatchController(
            AdaptiveBatchConfig(enabled = true, minBatchSize = 250, maxBatchSize = 10_000, targetBatchDurationMs = 1_000),
            initialBatchSize = 1_000
        )

        controller.onBatchCompleted(durationMs = 1_000, rowsInBatch = 1_000)
        controller.onBatchCompleted(durationMs = 1_000, rowsInBatch = 1_000)

        val decision = controller.onBatchCompleted(durationMs = 2_000, rowsInBatch = 3_000)

        assertThat(decision?.previousBatchSize).isEqualTo(1_000)
        assertThat(decision?.nextBatchSize).isEqualTo(500)
        assertThat(controller.currentBatchSize).isEqualTo(500)
    }

    @Test
    fun `should increase batch size when batch is faster than target`() {
        val controller = AdaptiveBatchController(
            AdaptiveBatchConfig(enabled = true, minBatchSize = 250, maxBatchSize = 10_000, targetBatchDurationMs = 1_000),
            initialBatchSize = 1_000
        )

        controller.onBatchCompleted(durationMs = 1_000, rowsInBatch = 1_000)
        controller.onBatchCompleted(durationMs = 1_000, rowsInBatch = 1_000)

        val decision = controller.onBatchCompleted(durationMs = 400, rowsInBatch = 3_000)

        assertThat(decision?.previousBatchSize).isEqualTo(1_000)
        assertThat(decision?.nextBatchSize).isEqualTo(1_250)
        assertThat(controller.currentBatchSize).isEqualTo(1_250)
    }

    @Test
    fun `should respect adaptive batch bounds`() {
        val controller = AdaptiveBatchController(
            AdaptiveBatchConfig(enabled = true, minBatchSize = 250, maxBatchSize = 1_000, targetBatchDurationMs = 1_000),
            initialBatchSize = 1_000
        )

        controller.onBatchCompleted(durationMs = 1_000, rowsInBatch = 1_000)
        controller.onBatchCompleted(durationMs = 1_000, rowsInBatch = 1_000)
        controller.onBatchCompleted(durationMs = 100, rowsInBatch = 3_000)

        assertThat(controller.currentBatchSize).isEqualTo(1_000)
    }

    @Test
    fun `should wait for warmup batches before changing batch size`() {
        val controller = AdaptiveBatchController(
            AdaptiveBatchConfig(enabled = true, minBatchSize = 250, maxBatchSize = 4_000, targetBatchDurationMs = 150),
            initialBatchSize = 1_000
        )

        val firstDecision = controller.onBatchCompleted(durationMs = 20, rowsInBatch = 10_000)
        val secondDecision = controller.onBatchCompleted(durationMs = 20, rowsInBatch = 10_000)
        val thirdDecision = controller.onBatchCompleted(durationMs = 20, rowsInBatch = 10_000)

        assertThat(firstDecision).isNull()
        assertThat(secondDecision).isNull()
        assertThat(thirdDecision?.nextBatchSize).isEqualTo(1_250)
    }

    @Test
    fun `should wait for minimum row count before changing batch size`() {
        val controller = AdaptiveBatchController(
            AdaptiveBatchConfig(enabled = true, minBatchSize = 250, maxBatchSize = 4_000, targetBatchDurationMs = 150),
            initialBatchSize = 1_000
        )

        controller.onBatchCompleted(durationMs = 20, rowsInBatch = 1_000)
        controller.onBatchCompleted(durationMs = 20, rowsInBatch = 1_000)
        val thirdDecision = controller.onBatchCompleted(durationMs = 20, rowsInBatch = 1_000)
        val fifthDecision = controller.onBatchCompleted(durationMs = 20, rowsInBatch = 2_000)

        assertThat(thirdDecision).isNull()
        assertThat(fifthDecision?.nextBatchSize).isEqualTo(1_250)
    }
}
