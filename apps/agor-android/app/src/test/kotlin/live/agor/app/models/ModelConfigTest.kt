package live.agor.app.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelConfigTest {
    @Test
    fun displaySummaryIncludesProviderModelEffortAndThinkingMode() {
        val config = ModelConfig(
            provider = "anthropic",
            model = "claude-opus-4-6",
            effort = "high",
            thinkingMode = "extended",
        )

        assertEquals(
            "anthropic · claude-opus-4-6 · effort high · extended",
            config.displaySummary,
        )
    }

    @Test
    fun displaySummarySkipsBlankFields() {
        val config = ModelConfig(provider = " ", model = "gpt-5.4", effort = null)

        assertEquals("gpt-5.4", config.displaySummary)
    }
}
