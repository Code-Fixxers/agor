package live.agor.app.viewmodels

import live.agor.app.models.InputRequestContent
import live.agor.app.models.InputRequestQuestion
import org.junit.Assert.assertEquals
import org.junit.Test

class InputRequestAnswerMappingTest {
    @Test
    fun mapsMultipleAnswersByQuestionText() {
        val request = InputRequestContent(
            questions = listOf(
                InputRequestQuestion(question = "Which branch?"),
                InputRequestQuestion(question = "Anything else?"),
            ),
        )

        val answers = answersByQuestion(request, listOf("main", "Run tests"))

        assertEquals(
            mapOf(
                "Which branch?" to "main",
                "Anything else?" to "Run tests",
            ),
            answers,
        )
    }

    @Test
    fun omitsBlankAnswersForMultipleQuestions() {
        val request = InputRequestContent(
            questions = listOf(
                InputRequestQuestion(question = "Which branch?"),
                InputRequestQuestion(question = "Anything else?"),
            ),
        )

        val answers = answersByQuestion(request, listOf("main", "   "))

        assertEquals(mapOf("Which branch?" to "main"), answers)
    }

    @Test
    fun joinsMultipleSelectionsForSingleQuestion() {
        val request = InputRequestContent(
            questions = listOf(InputRequestQuestion(question = "Pick checks")),
        )

        val answers = answersByQuestion(request, listOf("Build", "Test"))

        assertEquals(mapOf("Pick checks" to "Build, Test"), answers)
    }

    @Test
    fun uploadedPathsAreAppliedToAttachmentChipsByIndex() {
        val attachments = listOf(
            ChatViewModel.PendingSessionAttachment("a", "one.txt", "text/plain", 1, byteArrayOf(1)),
            ChatViewModel.PendingSessionAttachment("b", "two.txt", "text/plain", 1, byteArrayOf(2)),
        )

        val updated = attachments.withUploadedPaths(listOf("uploads/one.txt"))

        assertEquals("uploads/one.txt", updated[0].uploadedPath)
        assertEquals(null, updated[1].uploadedPath)
    }
}
