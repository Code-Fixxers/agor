package live.agor.app.models

enum class DrawerSessionFilter(
    val token: String,
    val label: String,
    val cutoffDays: Long?,
    val includeArchived: Boolean,
) {
    SevenDays("7d", "7 days", 7, false),
    ThirtyDays("30d", "30 days", 30, false),
    All("all", "All", null, false),
    Archived("archived", "Archived", null, true);

    companion object {
        fun fromToken(token: String?): DrawerSessionFilter {
            return entries.firstOrNull { it.token == token } ?: SevenDays
        }
    }
}
