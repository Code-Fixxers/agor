package live.agor.jetbrains.settings

class AcpConfigBuilder {
    fun buildHermesAgentConfig(
        proxyPath: String,
        hermesUrl: String,
        hermesTokenCommand: String,
        hermesModel: String,
    ): String =
        """
        {
          "agents": {
            "Hermes": {
              "command": ${proxyPath.json()},
              "env": {
                "HERMES_URL": ${hermesUrl.json()},
                "HERMES_TOKEN_COMMAND": ${hermesTokenCommand.json()},
                "HERMES_MODEL": ${hermesModel.json()}
              }
            }
          }
        }
        """.trimIndent()

    private fun String.json(): String =
        buildString {
            append('"')
            for (char in this@json) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
}
