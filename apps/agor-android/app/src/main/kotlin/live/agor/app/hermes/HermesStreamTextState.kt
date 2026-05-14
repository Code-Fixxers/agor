package live.agor.app.hermes

internal data class HermesStreamTextUpdate(
    val text: String,
    val replaceExisting: Boolean,
    val emitTextEvent: Boolean,
)

internal class HermesStreamTextState {
    private val final = StringBuilder()
    var reasoningShown: Boolean = false
        private set
    var finalStarted: Boolean = false
        private set

    val finalText: String
        get() = final.toString()

    fun onReasoningDelta(delta: String): HermesStreamTextUpdate? {
        if (delta.isBlank() || finalStarted) return null
        reasoningShown = true
        return HermesStreamTextUpdate(
            text = delta,
            replaceExisting = false,
            emitTextEvent = false,
        )
    }

    fun onTextDelta(delta: String): HermesStreamTextUpdate? {
        if (delta.isBlank()) return null
        val replaceExisting = !finalStarted && reasoningShown
        finalStarted = true
        final.append(delta)
        return HermesStreamTextUpdate(
            text = delta,
            replaceExisting = replaceExisting,
            emitTextEvent = true,
        )
    }
}
