package live.agor.app.models

import live.agor.app.network.AgorJson
import org.junit.Assert.assertEquals
import org.junit.Test

class InputRequestTest {
    @Test
    fun decodesPlainStringOptionsForCompatibility() {
        val content = AgorJson.decodeFromString(
            InputRequestContent.serializer(),
            """
            {
              "request_id": "input-1",
              "questions": [
                {
                  "question": "Pick one",
                  "options": ["Fast", "Careful"],
                  "multiSelect": false
                }
              ],
              "status": "pending"
            }
            """.trimIndent(),
        )

        val options = content.questions.single().options.orEmpty()
        assertEquals("Fast", options[0].label)
        assertEquals("", options[0].description)
        assertEquals("Careful", options[1].label)
    }

    @Test
    fun decodesStructuredOptionsWithDescriptionAndMarkdown() {
        val content = AgorJson.decodeFromString(
            InputRequestContent.serializer(),
            """
            {
              "request_id": "input-1",
              "questions": [
                {
                  "question": "Pick one",
                  "options": [
                    {
                      "label": "Fast",
                      "description": "Move quickly",
                      "markdown": "**Fast** path"
                    }
                  ],
                  "multiSelect": false
                }
              ],
              "status": "pending"
            }
            """.trimIndent(),
        )

        val option = content.questions.single().options.orEmpty().single()
        assertEquals("Fast", option.label)
        assertEquals("Move quickly", option.description)
        assertEquals("**Fast** path", option.markdown)
    }
}
