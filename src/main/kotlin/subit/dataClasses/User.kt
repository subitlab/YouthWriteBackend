package subit.dataClasses

import io.ktor.server.auth.*
import kotlinx.serialization.Serializable

@Serializable
sealed interface SsoUser
{
    val id: UserId
    val username: String
    val registrationTime: Long
    val email: List<String>
}

@Serializable
data class SsoUserFull(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    val phone: String,
    override val email: List<String>,
    val seiue: List<Seiue>,
): SsoUser
{
    @Serializable
    data class Seiue(
        val studentId: String,
        val realName: String,
        val archived: Boolean,
    )
}

@Serializable
data class SsoUserInfo(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    override val email: List<String>,
): SsoUser

/**
 * 用户数据库数据类
 * @property id 用户ID
 * @property introduction 个人简介
 * @property showStars 是否公开收藏
 * @property permission 用户管理权限
 * @property filePermission 文件上传权限
 */
@Serializable
data class DatabaseUser(
    val id: UserId,
    val introduction: String?,
    val showStars: Boolean,
    val mergeNotice: Boolean,
    val permission: PermissionLevel,
    val filePermission: PermissionLevel,
)
{
    companion object
    {
        val example = DatabaseUser(
            UserId(1),
            "introduction",
            showStars = true,
            mergeNotice = true,
            permission = PermissionLevel.NORMAL,
            filePermission = PermissionLevel.NORMAL
        )
    }
}
fun DatabaseUser?.hasGlobalAdmin() = this != null && (this.permission >= PermissionLevel.ADMIN)
fun UserFull?.hasGlobalAdmin() = this != null && (this.permission >= PermissionLevel.ADMIN)

sealed interface UserInfo
{
    val id: UserId
    val username: String
    val registrationTime: Long
    val email: List<String>
    val introduction: String?
    val showStars: Boolean
}

@Serializable
data class UserFull(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    val phone: String,
    override val email: List<String>,
    val seiue: List<SsoUserFull.Seiue>,
    override val introduction: String?,
    override val showStars: Boolean,
    val mergeNotice: Boolean,
    val permission: PermissionLevel,
    val filePermission: PermissionLevel
): Principal, UserInfo
{
    fun toBasicUserInfo() = BasicUserInfo(id, username, registrationTime, email, introduction, showStars)
    fun toSsoUser() = SsoUserFull(id, username, registrationTime, phone, email, seiue)
    fun toDatabaseUser() = DatabaseUser(id, introduction, showStars, mergeNotice, permission, filePermission)
    companion object
    {
        fun from(ssoUser: SsoUserFull, dbUser: DatabaseUser) = UserFull(
            ssoUser.id,
            ssoUser.username,
            ssoUser.registrationTime,
            ssoUser.phone,
            ssoUser.email,
            ssoUser.seiue,
            dbUser.introduction,
            dbUser.showStars,
            dbUser.mergeNotice,
            dbUser.permission,
            dbUser.filePermission
        )
        val example = UserFull(
            UserId(1),
            "username",
            System.currentTimeMillis(),
            "phone",
            listOf("email"),
            listOf(SsoUserFull.Seiue("studentId", "realName", false)),
            "introduction",
            showStars = true,
            mergeNotice = true,
            permission = PermissionLevel.NORMAL,
            filePermission = PermissionLevel.NORMAL
        )
    }
}

/**
 * 用户基本信息, 即一般人能看到的信息
 */
@Serializable
data class BasicUserInfo(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    override val email: List<String>,
    override val introduction: String?,
    override val showStars: Boolean
): UserInfo
{
    companion object
    {
        fun from(ssoUser: SsoUser, dbUser: DatabaseUser) = BasicUserInfo(
            ssoUser.id,
            ssoUser.username,
            ssoUser.registrationTime,
            ssoUser.email,
            dbUser.introduction,
            dbUser.showStars
        )
        val example = UserFull.example.toBasicUserInfo()
    }
}