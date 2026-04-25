package live.agor.app.network

import kotlinx.serialization.json.Json

/** Project-wide JSON config: lenient + tolerant of unknown fields. */
val AgorJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}
