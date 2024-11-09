package subit.dataClasses

import kotlinx.serialization.Serializable

@Serializable
sealed interface NamedUser
{
    val id: UserId
    val username: String
}

@Serializable
sealed interface PermissionUser
{
    val id: UserId
    val permission: PermissionLevel
}

@Serializable
sealed interface SsoUser: NamedUser
{
    override val id: UserId
    override val username: String
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
): SsoUser, NamedUser
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
): SsoUser, NamedUser

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
    override val id: UserId,
    val introduction: String?,
    val showStars: Boolean,
    override val permission: PermissionLevel,
    val filePermission: PermissionLevel,
): PermissionUser
{
    companion object
    {
        val example = DatabaseUser(
            UserId(1),
            "introduction",
            showStars = true,
            permission = PermissionLevel.NORMAL,
            filePermission = PermissionLevel.NORMAL
        )
    }
}
fun PermissionUser?.hasGlobalAdmin() = this != null && (this.permission >= PermissionLevel.ADMIN)

sealed interface UserInfo: NamedUser
{
    override val id: UserId
    override val username: String
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
    override val permission: PermissionLevel,
    val filePermission: PermissionLevel
): UserInfo, NamedUser, PermissionUser
{
    fun toBasicUserInfo() = BasicUserInfo(id, username, registrationTime, email, introduction, showStars)
    fun toSsoUser() = SsoUserFull(id, username, registrationTime, phone, email, seiue)
    fun toDatabaseUser() = DatabaseUser(id, introduction, showStars, permission, filePermission)
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
): UserInfo, NamedUser
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