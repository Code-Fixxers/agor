package live.agor.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCredentialSnapshotTest {
    @Test
    fun profileCredentialSnapshotsIgnoreBlankProfileIds() {
        val raw = encodeProfileCredentialSnapshots(
            mapOf(
                "" to ProfileCredentialSnapshot(serverUrl = "http://blank"),
                "http://server-a:3030" to ProfileCredentialSnapshot(
                    serverUrl = "http://server-a:3030",
                    email = "a@example.test",
                    accessToken = "token-a",
                ),
            ),
        )

        val decoded = decodeProfileCredentialSnapshots(raw)

        assertNull(decoded[""])
        assertEquals("token-a", decoded["http://server-a:3030"]?.accessToken)
    }

    @Test
    fun profileCredentialSnapshotsFallBackToEmptyForInvalidJson() {
        val decoded = decodeProfileCredentialSnapshots("not-json")

        assertEquals(emptyMap<String, ProfileCredentialSnapshot>(), decoded)
    }
}
