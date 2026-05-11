package live.agor.jetbrains.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AcpConfigBuilderTest {
    @Test
    fun `builds JetBrains acp json for Hermes proxy`() {
        val json = AcpConfigBuilder().buildHermesAgentConfig(
            proxyPath = "/nix/store/proxy/bin/hermes-acp-proxy",
            hermesUrl = "http://hermes:8642",
            hermesTokenCommand = "pass show hermes/token",
            hermesModel = "hermes-4",
        )

        assertEquals(
            """
            {
              "agents": {
                "Hermes": {
                  "command": "/nix/store/proxy/bin/hermes-acp-proxy",
                  "env": {
                    "HERMES_URL": "http://hermes:8642",
                    "HERMES_TOKEN_COMMAND": "pass show hermes/token",
                    "HERMES_MODEL": "hermes-4"
                  }
                }
              }
            }
            """.trimIndent(),
            json,
        )
    }
}
