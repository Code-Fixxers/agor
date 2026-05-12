package live.agor.app.ui.messageblocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreePathLinkifierTest {
    @Test
    fun extractsRelativeWorktreePathsAndDropsLineSuffixes() {
        val links = extractWorktreePathLinks(
            "Check apps/agor-android/app/src/main/kotlin/live/agor/app/ui/messageblocks/MarkdownText.kt:42 " +
                "and docs/agor-android-working-task-list.md.",
        )

        assertEquals(
            listOf(
                "apps/agor-android/app/src/main/kotlin/live/agor/app/ui/messageblocks/MarkdownText.kt",
                "docs/agor-android-working-task-list.md",
            ),
            links.map { it.path },
        )
    }

    @Test
    fun normalizesCommonAbsoluteRepositoryPaths() {
        val links = extractWorktreePathLinks(
            "Opened /home/daniel/Repositories/agor/apps/agor-android/app/src/main/kotlin/live/agor/app/ui/chat/ChatScreen.kt:563",
        )

        assertEquals(
            listOf("apps/agor-android/app/src/main/kotlin/live/agor/app/ui/chat/ChatScreen.kt"),
            links.map { it.path },
        )
    }

    @Test
    fun ignoresUrlsAndPlainWords() {
        val links = extractWorktreePathLinks(
            "See https://agor.live/guide and MarkdownText.kt, but use ./context/README.md",
        )

        assertEquals(listOf("context/README.md"), links.map { it.path })
    }

    @Test
    fun markdownConversionAddsAgorFileLinksWithoutTouchingExistingLinks() {
        val linked = "Edit `apps/agor-android/app/src/main/kotlin/Foo.kt` and [site](https://agor.live/docs)."
            .withWorktreePathLinks()

        assertTrue(linked.contains("[apps/agor-android/app/src/main/kotlin/Foo.kt](agor-file://"))
        assertTrue(linked.contains("[site](https://agor.live/docs)"))
    }
}
