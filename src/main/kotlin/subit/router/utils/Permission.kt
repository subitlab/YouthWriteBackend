@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package subit.router.utils

import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import subit.dataClasses.*
import subit.dataClasses.State.*
import subit.database.Blocks
import subit.database.Permissions
import subit.database.Posts
import subit.utils.HttpStatus

inline fun <reified T> Context.withPermission(
    user: DatabaseUser? = getLoginUser()?.toDatabaseUser(),
    ssoUser: SsoUserFull? = getLoginUser()?.toSsoUser(),
    body: CheckPermissionInContextScope.()->T
): T = CheckPermissionInContextScope(this, user, ssoUser).body()

inline fun <reified T> withPermission(
    user: DatabaseUser?,
    ssoUser: SsoUserFull?,
    body: CheckPermissionScope.()->T
): T = CheckPermissionScope(user, ssoUser).body()

open class CheckPermissionScope @PublishedApi internal constructor(val user: DatabaseUser?, val ssoUser: SsoUserFull?): KoinComponent
{
    protected val permissions = get<Permissions>()
    protected val blocks = get<Blocks>()
    protected val posts = get<Posts>()

    /**
     * 获取用户在某一个板块的权限等级
     */
    suspend fun getPermission(bid: BlockId): PermissionLevel =
        if (hasGlobalAdmin) PermissionLevel.ROOT
        else user?.let { permissions.getPermission(bid, it.id) } ?: PermissionLevel.NORMAL

    val globalPermission: PermissionLevel
        get() = user?.permission ?: PermissionLevel.NORMAL

    val filePermission: PermissionLevel
        get() = user?.filePermission ?: PermissionLevel.BANNED

    suspend fun hasAdminIn(block: BlockId): Boolean =
        user != null && (getPermission(block) >= PermissionLevel.ADMIN)

    val hasGlobalAdmin: Boolean
        get() = user.hasGlobalAdmin()
    val hasFileAdmin: Boolean
        get() = filePermission >= PermissionLevel.ADMIN

    /**
     * 是否已经实名
     */
    val hasRealName: Boolean
        get() = (ssoUser?.seiue?.size ?: 0) > 0

    /// 可以看 ///

    suspend fun canRead(block: Block): Boolean = when (block.state)
    {
        NORMAL  -> getPermission(block.id) >= block.reading
        else -> hasGlobalAdmin
    }

    suspend fun canRead(post: PostInfo): Boolean
    {
        val blockInfo = blocks.getBlock(post.block) ?: return false
        if (!canRead(blockInfo)) return false
        val root = post.root?.let { posts.getPostInfo(it) }
        if (root != null && !canRead(root)) return false
        return when (post.state)
        {
            NORMAL  -> true
            PRIVATE -> post.author == user?.id || hasGlobalAdmin
            else -> hasGlobalAdmin
        }
    }

    /// 可以删除 ///

    suspend fun canChangeState(post: PostInfo, newState: State): Boolean = canRead(post) && hasRealName && when (post.state)
    {
        NORMAL ->
            when (newState)
            {
                DELETED -> post.author == user?.id || hasAdminIn(post.block)
                PRIVATE -> post.author == user?.id
                NORMAL -> true
            }
        PRIVATE ->
            when (newState)
            {
                NORMAL -> post.author == user?.id
                DELETED -> post.author == user?.id || hasGlobalAdmin
                PRIVATE -> true
            }
        DELETED -> if (newState != DELETED) hasGlobalAdmin else true
    }

    suspend fun canChangeState(block: Block, newState: State): Boolean
    {
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

    suspend fun canComment(post: PostInfo): Boolean = canRead(post) && hasRealName && when (post.state)
    {
        NORMAL, PRIVATE -> blocks.getBlock(post.block)?.let { getPermission(post.block) >= it.commenting } ?: false
        else            -> false
    }

    /// 可以发贴 ///

    suspend fun canPost(block: Block): Boolean = hasRealName && when (block.state)
    {
        NORMAL  -> getPermission(block.id) >= block.posting && getPermission(block.id) >= block.reading
        else -> false
    }

    /// 可以匿名 ///

    suspend fun canAnonymous(block: Block): Boolean = hasRealName && when (block.state)
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
        // 如果在尝试修改自己的权限
        if (other.id == user?.id)
        {
            // 如果尝试修改自己的全局权限, 要有全局管理员且目标权限比当前权限低
            if (block == null)
                return globalPermission >= maxOf(permission, PermissionLevel.ADMIN)

            // 如果尝试修改自己在某板块的权限
            // 如果目标权限比现在低, 直接通过
            if (permission < getPermission(block.id)) return true

            // 如果目标权限比现在高
            // 要么其父板块权限高于目标权限
            block.parent?.let { parent ->
                if (getPermission(parent) > permission) return true
            }
            // 要么其拥有全局管理员
            return hasGlobalAdmin
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

    fun canChangeFilePermission(other: DatabaseUser, permission: PermissionLevel): Boolean
    {
        if (other.id == user?.id)
            return user.filePermission >= permission
        return filePermission > permission && hasFileAdmin
    }
}

class CheckPermissionInContextScope @PublishedApi internal constructor(val context: Context, user: DatabaseUser?, ssoUser: SsoUserFull?):
    CheckPermissionScope(user, ssoUser)
{

    suspend fun checkHasAdminIn(block: BlockId) = if (!hasAdminIn(block)) finishCall(HttpStatus.Forbidden) else Unit
    private fun checkOrFinish(condition: Boolean, status: HttpStatus) = if (!condition) finishCall(status) else Unit

    fun checkHasGlobalAdmin() = if (!hasGlobalAdmin) finishCall(HttpStatus.Forbidden) else Unit

    fun checkRealName() = if (!hasRealName) finishCall(HttpStatus.NotRealName) else Unit

    /// 可以看 ///

    suspend fun checkRead(block: Block) = when (block.state)
    {
        NORMAL  -> checkOrFinish(getPermission(block.id) >= block.reading, HttpStatus.Forbidden)
        DELETED -> checkOrFinish(hasGlobalAdmin, HttpStatus.NotFound)
        PRIVATE -> checkOrFinish(hasGlobalAdmin, HttpStatus.Forbidden)
    }

    suspend fun checkRead(post: PostInfo)
    {
        val blockInfo = blocks.getBlock(post.block) ?: finishCall(HttpStatus.NotFound)
        checkRead(blockInfo)
        val root = post.root?.let { posts.getPostInfo(it) }
        if (root != null) checkRead(root)
        return when (post.state)
        {
            NORMAL  -> Unit
            PRIVATE -> checkOrFinish(post.author == user?.id || hasGlobalAdmin, HttpStatus.Forbidden)
            DELETED -> checkOrFinish(hasGlobalAdmin, HttpStatus.NotFound)
        }
    }

    /// 可以删除 ///

    suspend fun checkChangeState(post: PostInfo, newState: State)
    {
        checkRealName()
        checkRead(post)
        when (post.state)
        {
            NORMAL ->
                when (newState)
                {
                    DELETED -> checkOrFinish(post.author == user?.id || hasAdminIn(post.block), HttpStatus.Forbidden)
                    PRIVATE -> checkOrFinish(post.author == user?.id, HttpStatus.Forbidden)
                    NORMAL -> Unit
                }
            PRIVATE ->
                when (newState)
                {
                    NORMAL -> checkOrFinish(post.author == user?.id, HttpStatus.Forbidden)
                    DELETED -> checkOrFinish(post.author == user?.id || hasGlobalAdmin, HttpStatus.Forbidden)
                    PRIVATE -> Unit
                }
            DELETED -> checkOrFinish(newState != DELETED, HttpStatus.Forbidden)
        }
    }

    suspend fun checkChangeState(block: Block, newState: State)
    {
        checkRealName()
        checkOrFinish(hasRealName, HttpStatus.NotRealName)
        // 板块不能有私密状态
        checkOrFinish(newState != PRIVATE, HttpStatus.BadRequest.subStatus("板块不能有私密状态"))
        checkRead(block)
        if (block.state == newState) return
        return when (block.state)
        {
            NORMAL -> checkOrFinish(hasAdminIn(block.id), HttpStatus.Forbidden)
            else -> checkOrFinish(hasGlobalAdmin, HttpStatus.Forbidden)
        }
    }

    /// 可以评论 ///

    suspend fun checkComment(post: PostInfo)
    {
        checkRealName()
        checkRead(post)
        checkOrFinish(canComment(post), HttpStatus.Forbidden)
    }

    /// 可以发贴 ///

    suspend fun checkPost(block: Block)
    {
        checkRealName()
        checkOrFinish(canPost(block), HttpStatus.Forbidden)
    }

    /// 可以匿名 ///

    suspend fun checkAnonymous(block: Block)
    {
        checkRealName()
        checkOrFinish(canAnonymous(block), HttpStatus.Forbidden)
    }



    suspend fun checkChangePermission(block: Block?, other: DatabaseUser, permission: PermissionLevel)
    {
        /**
         * 详见[CheckPermissionScope.canChangePermission]
         *
         * 这里在其基础上将返回true改为不做任何操作, 返回false改为结束请求, 并返回403及详细说明
         */
        if (other.id == user?.id)
        {
            // 如果尝试修改自己的全局权限, 要有全局管理员且目标权限比当前权限低
            if (block == null)
            {
                if (globalPermission >= maxOf(permission, PermissionLevel.ADMIN)) return
                else finishCall(
                    HttpStatus.Forbidden.subStatus(
                        message = "修改自己的全局权限要求拥有全局管理员权限, 且目标权限不得高于当前权限"
                    )
                )
            }

            // 如果尝试修改自己在某板块的权限
            // 如果目标权限比现在低, 直接通过
            if (permission <= getPermission(block.id)) return

            // 如果目标权限比现在高
            // 要么其父板块权限高于目标权限
            block.parent?.let { parent ->
                if (getPermission(parent) >= permission) return
            }
            // 要么其拥有全局管理员
            if (hasGlobalAdmin) return

            // 返回消息, 根据有没有父板块返回不同的消息
            if (block.parent != null) finishCall(
                HttpStatus.Forbidden.subStatus(
                    message = "修改自己在板块${block.name}的权限要求在此板块的权限或在父板块的权限不低于目标权限, 或拥有全局管理员权限"
                )
            )
            else finishCall(
                HttpStatus.Forbidden.subStatus(
                    message = "修改自己在板块${block.name}的权限要求在此板块的权限不低于目标权限, 或拥有全局管理员权限"
                )
            )
        }
        if (block == null)
        {
            if (hasGlobalAdmin && other.permission < globalPermission && permission < globalPermission)
                return
            else finishCall(
                HttpStatus.Forbidden.subStatus(
                    message = "修改他人的全局权限要求拥有全局管理员权限, 且目标用户修改前后的权限都低于自己的全局权限"
                )
            )
        }
        val selfPermission = getPermission(block.id)
        if (selfPermission < PermissionLevel.ADMIN) finishCall(
            HttpStatus.Forbidden.subStatus(
                message = "修改他人在板块${block.name}的权限要求拥有该板块管理员权限"
            )
        )
        val otherPermission = withPermission(other, null) { getPermission(block.id) }
        if (selfPermission > otherPermission && selfPermission > permission)
            return
        else finishCall(
            HttpStatus.Forbidden.subStatus(
                message = "修改他人在板块${block.name}的权限要求拥有该板块管理员权限, 且目标用户修改前后的权限都低于自己的权限"
            )
        )
    }

    fun checkChangeFilePermission(other: DatabaseUser, permission: PermissionLevel)
    {
        if (other.id == user?.id)
        {
            if (user.filePermission >= permission) return
            else finishCall(
                HttpStatus.Forbidden.subStatus(
                    message = "修改自己的文件权限要求目标权限不高于当前权限"
                )
            )
        }
        if (filePermission > permission && hasFileAdmin) return
        else finishCall(
            HttpStatus.Forbidden.subStatus(
                message = "修改他人的文件权限要求拥有文件管理员权限, 且目标用户修改前后的权限都低于自己的权限"
            )
        )
    }
}