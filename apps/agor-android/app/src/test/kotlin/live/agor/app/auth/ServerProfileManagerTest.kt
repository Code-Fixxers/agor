package live.agor.app.auth

import live.agor.app.models.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileManagerTest {
    @Test
    fun upsertServerProfilePreservesSingleDefault() {
        val existing = listOf(
            ServerProfile(id = "a", label = "A", url = "http://a", isDefault = true),
        )
        val next = upsertServerProfile(
            profile = ServerProfile(id = "b", label = "B", url = "http://b", isDefault = true),
            current = existing,
        )

        assertEquals(listOf("b", "a"), next.map { it.id })
        assertEquals(listOf(true, false), next.map { it.isDefault })
    }

    @Test
    fun defaultServerProfileListMarksOnlyRequestedProfile() {
        val profiles = listOf(
            ServerProfile(id = "a", label = "A", url = "http://a", isDefault = true),
            ServerProfile(id = "b", label = "B", url = "http://b"),
        )
        val next = profiles.withDefaultServerProfile("b")

        assertEquals(listOf(false, true), next.map { it.isDefault })
        assertTrue(next.first { it.id == "b" }.isDefault)
    }

    @Test
    fun upsertServerProfileDeduplicatesEditedProfileByNormalizedUrl() {
        val existing = listOf(
            ServerProfile(id = "http://agor.local:3030", label = "Old", url = "http://agor.local:3030"),
        )

        val next = upsertServerProfile(
            profile = ServerProfile(
                id = "http://agor.local:3030/",
                label = "New",
                url = "http://agor.local:3030/",
            ),
            current = existing,
        )

        assertEquals(1, next.size)
        assertEquals("New", next.single().label)
        assertEquals("http://agor.local:3030", next.single().id)
        assertEquals("http://agor.local:3030", next.single().url)
    }

    @Test
    fun migrateLegacyServerProfileCreatesDefaultProfileFromStoredUrl() {
        val next = migrateLegacyServerProfile(
            current = emptyList(),
            serverUrl = " http://legacy.local:3030/ ",
            email = "user@example.test",
        )

        assertEquals(1, next.size)
        assertEquals("http://legacy.local:3030", next.single().id)
        assertEquals("http://legacy.local:3030", next.single().url)
        assertEquals("user@example.test", next.single().label)
        assertTrue(next.single().isDefault)
    }

    @Test
    fun migrateLegacyServerProfileDoesNotDuplicateExistingUrl() {
        val existing = listOf(
            ServerProfile(
                id = "http://legacy.local:3030",
                label = "Existing",
                url = "http://legacy.local:3030",
                isDefault = true,
            ),
        )

        val next = migrateLegacyServerProfile(existing, "http://legacy.local:3030/", "new@example.test")

        assertEquals(existing, next)
    }
}
