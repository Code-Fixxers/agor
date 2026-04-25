package live.agor.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UserRole {
    @SerialName("anonymous") ANONYMOUS,
    @SerialName("guest") GUEST,
    @SerialName("member") MEMBER,
    @SerialName("admin") ADMIN,
    @SerialName("superadmin") SUPERADMIN,
}

@Serializable
data class User(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String? = null,
    val emoji: String? = null,
    val role: UserRole = UserRole.MEMBER,
    @SerialName("unix_username") val unixUsername: String? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean? = null,
)
