package live.agor.app.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadNotificationMessageTest {
    @Test
    fun uploadNotificationMessageUsesFilepathPlaceholderForAttachmentOnlyPrompt() {
        assertEquals(
            "Attached file(s): {filepath}\n\nPlease review these attached files.",
            uploadNotificationMessage("   "),
        )
    }

    @Test
    fun uploadNotificationMessageAppendsFilepathPlaceholderToUserPrompt() {
        assertEquals(
            "Please inspect this\n\nAttached file(s): {filepath}",
            uploadNotificationMessage("  Please inspect this  "),
        )
    }
}
