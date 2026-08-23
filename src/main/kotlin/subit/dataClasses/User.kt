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

    companion object {
        fun from(oldUserInfo: OldUserInfo) = SsoUserFull(
            id = oldUserInfo.id,
            username = oldUserInfo.name,
            email = listOf(oldUserInfo.email),
            registrationTime = oldUserInfo.registrationTime,
            phone = "",
            seiue = emptyList() // 旧用户没有seiue信息
        )
    }
}

@Serializable
@Suppress("unused") // 该类作为SsoUser的默认实现, 在反序列化时使用, 故不应被标记为unused
data class SsoUserInfo(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    override val email: List<String>,
): SsoUser, NamedUser

/**
 * 用户数据库数据类
 * @property id 用户ID
 * @property penName 笔名
 * @property introduction 个人简介
 * @property showStars 是否公开收藏
 * @property permission 用户管理权限
 * @property filePermission 文件上传权限
 * @property likeBlocks 收藏的区块ID列表
 * @property likeTags 收藏的标签ID列表
 */
@Serializable
data class DatabaseUser(
    override val id: UserId,
    val penName: String?,
    val introduction: String?,
    val showStars: Boolean,
    override val permission: PermissionLevel,
    val filePermission: PermissionLevel,
    val likeBlocks: List<BlockId>,
    val likeTags: List<TagId>,
): PermissionUser
{
    companion object
    {
        val example = DatabaseUser(
            UserId(1),
            penName = "金粟酒",
            "introduction",
            showStars = true,
            permission = PermissionLevel.NORMAL,
            filePermission = PermissionLevel.NORMAL,
            likeBlocks = listOf(BlockId(1)),
            likeTags = listOf(TagId(1)),
        )
    }
}
fun PermissionUser?.hasGlobalAdmin() = this != null && (this.permission >= PermissionLevel.ADMIN)

sealed interface UserInfo: NamedUser
{
    override val id: UserId
    override val username: String
    val penName: String?
    val registrationTime: Long
    val email: List<String>
    val introduction: String?
    val showStars: Boolean
    val permission: PermissionLevel
}

@Serializable
data class UserFull(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    val phone: String,
    override val email: List<String>,
    val seiue: List<SsoUserFull.Seiue>,
    override val penName: String?,
    override val introduction: String?,
    override val showStars: Boolean,
    override val permission: PermissionLevel,
    val filePermission: PermissionLevel,
    val likeBlocks: List<BlockId>,
    val likeTags: List<TagId>,
): UserInfo, NamedUser, PermissionUser
{
    fun toBasicUserInfo() = BasicUserInfo(id, username, registrationTime, email, penName, introduction, permission, showStars)
    fun toSsoUser() = SsoUserFull(id, username, registrationTime, phone, email, seiue)
    fun toDatabaseUser() = DatabaseUser(id, penName, introduction, showStars, permission, filePermission, likeBlocks, likeTags)
    companion object
    {
        fun from(ssoUser: SsoUserFull, dbUser: DatabaseUser) = UserFull(
            ssoUser.id,
            ssoUser.username,
            ssoUser.registrationTime,
            ssoUser.phone,
            ssoUser.email,
            ssoUser.seiue,
            dbUser.penName,
            dbUser.introduction,
            dbUser.showStars,
            dbUser.permission,
            dbUser.filePermission,
            dbUser.likeBlocks,
            dbUser.likeTags,
        )
        val example = UserFull(
            UserId(1),
            "username",
            System.currentTimeMillis(),
            "phone",
            listOf("email"),
            listOf(SsoUserFull.Seiue("studentId", "realName", false)),
            "金粟酒",
            "introduction",
            showStars = true,
            permission = PermissionLevel.NORMAL,
            filePermission = PermissionLevel.NORMAL,
            likeBlocks = listOf(BlockId(1)),
            likeTags = listOf(TagId(1)),
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
    override val penName: String?,
    override val introduction: String?,
    override val permission: PermissionLevel,
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
            dbUser.penName,
            dbUser.introduction,
            dbUser.permission,
            dbUser.showStars
        )
        val example = UserFull.example.toBasicUserInfo()
    }
}

@Serializable
data class OldUserInfo(
    val id: UserId,
    val name: String,
    val email: String,
    val registrationTime: Long,
    val newId: UserId?,
    val avatar: String?,
)
{
    companion object
    {
        val example = OldUserInfo(
            UserId(-1),
            "name",
            "email",
            System.currentTimeMillis(),
            UserId(1),
            "avatar"
        )
    }
}