package live.agor.app.automation

object AutomationProtocol {
    const val ACTION_CONTROL = "live.agor.app.action.AGOR_CONTROL"
    const val EXTRA_COMMAND_JSON = "live.agor.app.extra.CONTROL_COMMAND_JSON"
    const val EXTRA_RESPONSE_ACTION = "live.agor.app.extra.CONTROL_RESPONSE_ACTION"
    const val EXTRA_RESPONSE_REQUEST_ID = "live.agor.app.extra.CONTROL_RESPONSE_REQUEST_ID"

    const val KEY_COMMAND = "command"
    const val KEY_REQUEST_ID = "requestId"
    const val KEY_SUCCESS = "success"
    const val KEY_MESSAGE = "message"

    const val KEY_SERVER_URL = "serverUrl"
    const val KEY_EMAIL = "email"
    const val KEY_PASSWORD = "password"
    const val KEY_API_KEY = "apiKey"
    const val KEY_CONNECT_SOCKET = "connectSocket"

    const val KEY_HERMES_WEBHOOK = "hermesWebhook"
    const val KEY_HERMES_PROMPT = "hermesPrompt"
    const val KEY_HERMES_URL = "hermesUrl"
    const val KEY_HERMES_TOKEN = "hermesToken"

    const val COMMAND_LOGIN = "login"
    const val COMMAND_HERMES_TRIGGER = "hermes.trigger"
    const val COMMAND_PING = "ping"
}
