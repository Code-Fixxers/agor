package live.agor.app.ui.messageblocks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionCardViewTest {
    @Test
    fun permissionInputPreviewPrefersCommand() {
        val preview = permissionInputPreview(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive("README.md"),
                    "command" to JsonPrimitive("pnpm test"),
                ),
            ),
        )

        assertEquals("command: pnpm test", preview)
    }

    @Test
    fun permissionInputPreviewFallsBackToKeySummary() {
        val preview = permissionInputPreview(
            JsonObject(
                mapOf(
                    "zeta" to JsonPrimitive("z"),
                    "alpha" to JsonPrimitive("a"),
                ),
            ),
        )

        assertEquals("{alpha, zeta}", preview)
    }

    @Test
    fun permissionInputPreviewIsEmptyForNoInput() {
        assertNull(permissionInputPreview(JsonObject(emptyMap())))
    }
}
