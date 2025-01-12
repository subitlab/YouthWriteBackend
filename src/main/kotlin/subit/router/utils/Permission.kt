@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package subit.router.utils

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.State.*
import subit.database.Blocks
import subit.database.Permissions
import subit.database.Posts
import subit.database.Prohibits
import subit.utils.HttpStatus
import subit.utils.respond

inline fun <reified T> UserFull?.withPermission(
    body: PermissionGroup.()->T
): T = withPermission(this?.toDatabaseUser(), this?.toSsoUser(), body)

inline fun <reified T> UserFull?.checkPermission(
    body: PermissionChecker.()->T
): T = checkPermission(this?.toDatabaseUser(), this?.toSsoUser(), body)

inline fun <reified T> Context.withPermission(
    user: DatabaseUser? = getLoginUser()?.toDatabaseUser(),
    ssoUser: SsoUserFull? = getLoginUser()?.toSsoUser(),
    body: PermissionGroup.()->T
): T = PermissionGroup(user, ssoUser).body()

inline fun <reified T> Context.checkPermission(
    user: DatabaseUser? = getLoginUser()?.toDatabaseUser(),
    ssoUser: SsoUserFull? = getLoginUser()?.toSsoUser(),
    body: PermissionChecker.()->T
): T = PermissionChecker(user, ssoUser).body()

inline fun <reified T> withPermission(
    user: DatabaseUser?,
    ssoUser: SsoUserFull?,
    body: PermissionGroup.()->T
): T = PermissionGroup(user, ssoUser).body()

inline fun <reified T> checkPermission(
    user: DatabaseUser?,
    ssoUser: SsoUserFull?,
    body: PermissionChecker.()->T
): T = PermissionChecker(user, ssoUser).body()

fun UserFull?.permissionGroup(): PermissionGroup = PermissionGroup(this?.toDatabaseUser(), this?.toSsoUser())
fun UserFull?.permissionChecker(): PermissionChecker = PermissionChecker(this?.toDatabaseUser(), this?.toSsoUser())
fun Pair<DatabaseUser?, SsoUserFull?>.permissionGroup(): PermissionGroup = PermissionGroup(first, second)
fun Pair<DatabaseUser?, SsoUserFull?>.permissionChecker(): PermissionChecker = PermissionChecker(first, second)

open class PermissionGroup(val dbUser: DatabaseUser?, val ssoUser: SsoUserFull?): KoinComponent
{
    init
    {
        require(dbUser == null || ssoUser == null || dbUser.id == ssoUser.id)
    }
    protected val permissions by inject<Permissions>()
    protected val blocks by inject<Blocks>()
    protected val posts by inject<Posts>()
    protected val prohibits by inject<Prohibits>()

    val user get() = dbUser?.id ?: ssoUser?.id

    val hasGlobalAdmin: Boolean
        get() = dbUser.hasGlobalAdmin()
    val hasFileAdmin: Boolean
        get() = filePermission >= PermissionLevel.ADMIN

    /**
     * 获取用户在某一个板块的权限等级
     */
    suspend fun getPermission(bid: BlockId): PermissionLevel =
        if (hasGlobalAdmin) PermissionLevel.ROOT
        else dbUser?.let { permissions.getPermission(bid, it.id) } ?: PermissionLevel.NORMAL

    val globalPermission: PermissionLevel
        get() = dbUser?.permission ?: PermissionLevel.NORMAL

    val filePermission: PermissionLevel
        get() = dbUser?.filePermission ?: PermissionLevel.BANNED

    suspend fun hasAdminIn(block: BlockId): Boolean =
        dbUser != null && (getPermission(block) >= PermissionLevel.ADMIN)

    /**
     * 是否已经实名
     */
    val hasRealName: Boolean
        get() = (ssoUser?.seiue?.size ?: 0) > 0

    private var isProhibit0: Boolean? = null

    suspend fun isProhibit(): Boolean
    {
        var isProhibit = isProhibit0
        if (isProhibit != null) return isProhibit
        isProhibit =
            if (dbUser == null) false
            else dbUser.permission < PermissionLevel.NORMAL || prohibits.isProhibited(dbUser.id)
        isProhibit0 = isProhibit
        return isProhibit
    }

    /// 可以看 ///

    suspend fun canRead(block: Block): Boolean = !isProhibit() && when (block.state)
    {
        NORMAL  -> getPermission(block.id) >= block.reading
        else -> hasGlobalAdmin
    }

    suspend fun canRead(post: PostInfo): Boolean
    {
        if (isProhibit()) return false
        val blockInfo = blocks.getBlock(post.block) ?: return false
        if (!canRead(blockInfo)) return false
        val root = post.root?.let { posts.getPostInfo(it) }
        if (root != null && !canRead(root)) return false
        return when (post.state)
        {
            NORMAL  -> true
            else -> post.author == dbUser?.id || hasGlobalAdmin
        }
    }

    suspend fun canRead(version: PostVersionBasicInfo): Boolean
    {
        if (isProhibit()) return false
        val post = posts.getPostInfo(version.post) ?: return false
        if (!canRead(post)) return false
        if (!version.draft) return true
        return post.author == dbUser?.id || hasGlobalAdmin
    }

    /// 可以删除 ///

    suspend fun canChangeState(post: PostInfo, newState: State): Boolean =
        !isProhibit() && canRead(post) && hasRealName && when (post.state)
        {
            NORMAL ->
                when (newState)
                {
                    DELETED -> post.author == dbUser?.id || hasAdminIn(post.block)
                    PRIVATE -> post.author == dbUser?.id
                    NORMAL -> true
                }
            PRIVATE ->
                when (newState)
                {
                    NORMAL -> post.author == dbUser?.id
                    DELETED -> post.author == dbUser?.id || hasGlobalAdmin
                    PRIVATE -> true
                }
            DELETED -> if (newState != DELETED) hasGlobalAdmin else true
        }

    suspend fun canChangeState(block: Block, newState: State): Boolean
    {
        if (isProhibit()) return false
        if (!hasRealName) return false
        // 板块不能有私密状态
        if (newState == PRIVATE) return false
        if (!canRead(block)) return false
        if (block.state == newState) return true
        return when (block.state)
        {
            NORMAL -> hasAdminIn(block.id)
            else -> hasGlobalAdmin
        }
    }

    /// 可以评论 ///

    suspend fun canComment(post: PostInfo): Boolean = !isProhibit() && canRead(post) && hasRealName && when (post.state)
    {
        NORMAL, PRIVATE -> blocks.getBlock(post.block)?.let { getPermission(post.block) >= it.commenting } == true
        else            -> false
    }

    /// 可以发贴 ///

    suspend fun canPost(block: Block): Boolean = !isProhibit() && hasRealName && when (block.state)
    {
        NORMAL  -> getPermission(block.id) >= block.posting && getPermission(block.id) >= block.reading
        else -> false
    }

    suspend fun canEdit(version: PostVersionBasicInfo): Boolean
    {
        if (isProhibit()) return false
        val post = posts.getPostInfo(version.post) ?: return false
        if (!canRead(post)) return false
        if (!hasRealName) return false
        if (post.state != NORMAL) return false
        return post.author == dbUser?.id
    }

    /// 可以匿名 ///

    suspend fun canAnonymous(block: Block): Boolean = !isProhibit() && hasRealName && when (block.state)
    {
        NORMAL  -> getPermission(block.id) >= block.anonymous
        else -> false
    }

    /// 修改他人权限 ///

    /**
     * 检查是否可以修改权限
     * @param block 修改权限的板块, 为null表示全局权限
     * @param other 被修改权限的用户, 可以是自己
     * @param permission 目标权限(修改后的权限)
     */
    suspend fun canChangePermission(block: Block?, other: DatabaseUser, permission: PermissionLevel): Boolean
    {
        if (isProhibit()) return false
        // 如果在尝试修改自己的权限
        if (other.id == dbUser?.id)
        {
            // 如果尝试修改自己的全局权限, 要有全局管理员且目标权限比当前权限低
            if (block == null)
                return globalPermission >= maxOf(permission, PermissionLevel.ADMIN)

            // 如果尝试修改自己在某板块的权限
            // 如果目标权限比现在低, 直接通过
            if (permission < getPermission(block.id)) return true

            if (hasGlobalAdmin) return true

            // 如果目标权限比现在高
            // 要么其父板块权限高于目标权限
            block.parent?.let { parent ->
                if (getPermission(parent) >= maxOf(permission, PermissionLevel.ADMIN)) return true
            }
            // 要么其拥有全局管理员
            return false
        }
        if (block == null)
        {
            return hasGlobalAdmin && other.permission < globalPermission && permission < globalPermission
        }
        val selfPermission = getPermission(block.id)
        if (selfPermission < PermissionLevel.ADMIN) return false
        val otherPermission = withPermission(other, null) { getPermission(block.id) }
        return selfPermission > otherPermission && selfPermission > permission
    }

    suspend fun canChangeFilePermission(other: DatabaseUser, permission: PermissionLevel): Boolean
    {
        if (isProhibit()) return false
        if (other.id == dbUser?.id)
            return dbUser.filePermission >= permission
        return filePermission > permission && hasFileAdmin
    }
}

class PermissionChecker(dbUser: DatabaseUser?, ssoUser: SsoUserFull?): PermissionGroup(dbUser, ssoUser)
{
    class CheckPermissionFailedException(val status: HttpStatus): CallFinish({ respond(status) })
    private fun checkFailed(status: HttpStatus): Nothing = throw CheckPermissionFailedException(status)
    private fun checkOrFailed(condition: Boolean, status: HttpStatus = HttpStatus.Forbidden) =
        if (!condition) checkFailed(status) else Unit

    suspend fun checkHasAdminIn(block: BlockId) =
        checkOrFailed(hasAdminIn(block), HttpStatus.Forbidden)

    fun checkHasGlobalAdmin() =
        checkOrFailed(hasGlobalAdmin, HttpStatus.Forbidden)

    fun checkRealName() =
        checkOrFailed(hasRealName, HttpStatus.NotRealName)

    suspend fun checkProhibit() =
        checkOrFailed(!isProhibit(), HttpStatus.Prohibit)

    /// 可以看 ///

    suspend fun checkRead(block: Block)
    {
        checkProhibit()
        when (block.state)
        {
            NORMAL  -> checkOrFailed(getPermission(block.id) >= block.reading, HttpStatus.Forbidden)
            DELETED -> checkOrFailed(hasGlobalAdmin, HttpStatus.NotFound)
            PRIVATE -> checkOrFailed(hasGlobalAdmin, HttpStatus.Forbidden)
        }
    }

    suspend fun checkRead(post: PostInfo)
    {
        checkProhibit()
        val blockInfo = blocks.getBlock(post.block) ?: checkFailed(HttpStatus.NotFound)
        checkRead(blockInfo)
        val root = post.root?.let { posts.getPostInfo(it) }
        if (root != null) checkRead(root)
        return when (post.state)
        {
            NORMAL  -> Unit
            PRIVATE, DELETED -> checkOrFailed(post.author == dbUser?.id || hasGlobalAdmin, HttpStatus.Forbidden)
        }
    }

    suspend fun checkRead(version: PostVersionBasicInfo)
    {
        checkProhibit()
        val post = posts.getPostInfo(version.post) ?: checkFailed(HttpStatus.NotFound)
        checkRead(post)
        checkRealName()
        checkOrFailed(post.author == dbUser?.id || hasGlobalAdmin, HttpStatus.Forbidden)
    }

    /// 可以删除 ///

    suspend fun checkChangeState(post: PostInfo, newState: State)
    {
        checkProhibit()
        checkRealName()
        checkRead(post)
        when (post.state)
        {
            NORMAL ->
                when (newState)
                {
                    DELETED -> checkOrFailed(post.author == dbUser?.id || hasAdminIn(post.block), HttpStatus.Forbidden)
                    PRIVATE -> checkOrFailed(post.author == dbUser?.id, HttpStatus.Forbidden)
                    NORMAL -> Unit
                }
            PRIVATE ->
                when (newState)
                {
                    NORMAL -> checkOrFailed(post.author == dbUser?.id, HttpStatus.Forbidden)
                    DELETED -> checkOrFailed(post.author == dbUser?.id || hasGlobalAdmin, HttpStatus.Forbidden)
                    PRIVATE -> Unit
                }
            DELETED -> checkOrFailed(newState != DELETED, HttpStatus.Forbidden)
        }
    }

    suspend fun checkChangeState(block: Block, newState: State)
    {
        checkProhibit()
        checkRealName()
        checkOrFailed(hasRealName, HttpStatus.NotRealName)
        // 板块不能有私密状态
        checkOrFailed(newState != PRIVATE, HttpStatus.BadRequest.subStatus("板块不能有私密状态"))
        checkRead(block)
        if (block.state == newState) return
        return when (block.state)
        {
            NORMAL -> checkOrFailed(hasAdminIn(block.id), HttpStatus.Forbidden)
            else -> checkOrFailed(hasGlobalAdmin, HttpStatus.Forbidden)
        }
    }

    /// 可以评论 ///

    suspend fun checkComment(post: PostInfo)
    {
        checkProhibit()
        checkRealName()
        checkRead(post)
        checkOrFailed(canComment(post), HttpStatus.Forbidden)
    }

    /// 可以发贴 ///

    suspend fun checkPost(block: Block)
    {
        checkProhibit()
        checkRealName()
        checkOrFailed(canPost(block), HttpStatus.Forbidden)
    }

    /// 可以编辑 ///

    suspend fun checkEdit(version: PostVersionBasicInfo)
    {
        checkProhibit()
        val post = posts.getPostInfo(version.post) ?: checkFailed(HttpStatus.NotFound)
        checkRead(post)
        checkRealName()
        if (post.state != NORMAL) checkFailed(HttpStatus.NotAcceptable.subStatus("当前帖子状态不允许编辑"))
        checkOrFailed(post.author == dbUser?.id, HttpStatus.Forbidden)
    }

    /// 可以匿名 ///

    suspend fun checkAnonymous(block: Block)
    {
        checkProhibit()
        checkRealName()
        checkOrFailed(canAnonymous(block), HttpStatus.Forbidden)
    }

    /// 修改他人权限 ///

    suspend fun checkChangePermission(block: Block?, other: DatabaseUser, permission: PermissionLevel)
    {
        checkProhibit()
        /**
         * 详见[PermissionGroup.canChangePermission]
         *
         * 这里在其基础上将返回true改为不做任何操作, 返回false改为结束请求, 并返回403及详细说明
         */
        if (other.id == dbUser?.id)
        {
            // 如果尝试修改自己的全局权限, 要有全局管理员且目标权限比当前权限低
            if (block == null)
            {
                if (globalPermission >= maxOf(permission, PermissionLevel.ADMIN)) return
                else checkFailed(
                    HttpStatus.Forbidden.subStatus(
                        message = "修改自己的全局权限要求拥有全局管理员权限, 且目标权限不得高于当前权限"
                    )
                )
            }

            // 如果尝试修改自己在某板块的权限
            // 如果目标权限比现在低, 直接通过
            if (permission <= getPermission(block.id)) return

            // 如果目标权限比现在高
            // 要么其拥有全局管理员
            if (hasGlobalAdmin) return
            // 要么其父板块拥有管理员且权限高于目标权限
            block.parent?.let { parent ->
                if (getPermission(parent) >= maxOf(permission, PermissionLevel.ADMIN)) return
            }

            // 返回消息, 根据有没有父板块返回不同的消息
            if (block.parent != null) checkFailed(
                HttpStatus.Forbidden.subStatus(
                    message = "修改自己在板块${block.name}的权限要求在此板块的权限或在父板块的权限不低于目标权限, 或拥有全局管理员权限"
                )
            )
            else checkFailed(
                HttpStatus.Forbidden.subStatus(
                    message = "修改自己在板块${block.name}的权限要求在此板块的权限不低于目标权限, 或拥有全局管理员权限"
                )
            )
        }
        if (block == null)
        {
            if (hasGlobalAdmin && other.permission < globalPermission && permission < globalPermission)
                return
            else checkFailed(
                HttpStatus.Forbidden.subStatus(
                    message = "修改他人的全局权限要求拥有全局管理员权限, 且目标用户修改前后的权限都低于自己的全局权限"
                )
            )
        }
        val selfPermission = getPermission(block.id)
        if (selfPermission < PermissionLevel.ADMIN) checkFailed(
            HttpStatus.Forbidden.subStatus(
                message = "修改他人在板块${block.name}的权限要求拥有该板块管理员权限"
            )
        )
        val otherPermission = withPermission(other, null) { getPermission(block.id) }
        if (selfPermission > otherPermission && selfPermission > permission)
            return
        else checkFailed(
            HttpStatus.Forbidden.subStatus(
                message = "修改他人在板块${block.name}的权限要求拥有该板块管理员权限, 且目标用户修改前后的权限都低于自己的权限"
            )
        )
    }

    suspend fun checkChangeFilePermission(other: DatabaseUser, permission: PermissionLevel)
    {
        checkProhibit()
        if (other.id == dbUser?.id)
        {
            if (dbUser.filePermission >= permission) return
            else checkFailed(
                HttpStatus.Forbidden.subStatus(
                    message = "修改自己的文件权限要求目标权限不高于当前权限"
                )
            )
        }
        if (filePermission > permission && hasFileAdmin) return
        else checkFailed(
            HttpStatus.Forbidden.subStatus(
                message = "修改他人的文件权限要求拥有文件管理员权限, 且目标用户修改前后的权限都低于自己的权限"
            )
        )
    }
}