@file:Suppress("unused")

package subit.database

import io.ktor.server.application.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import subit.dataClasses.*
import subit.dataClasses.State.*
import subit.router.Context
import subit.router.getLoginUser
import subit.utils.HttpStatus
import subit.utils.respond

interface Permissions
{
    suspend fun setPermission(bid: BlockId, uid: UserId, permission: PermissionLevel)
    suspend fun getPermission(block: BlockId, user: UserId): PermissionLevel
}

inline fun <reified T> Context.withPermission(
    user: DatabaseUser? = getLoginUser()?.toDatabaseUser(),
    body: CheckPermissionInContextScope.()->T
): T = CheckPermissionInContextScope(this, user).body()

inline fun <reified T> withPermission(
    user: DatabaseUser?,
    body: CheckPermissionScope.()->T
): T = CheckPermissionScope(user).body()

open class CheckPermissionScope @PublishedApi internal constructor(val user: DatabaseUser?): KoinComponent
{
    protected val permissions = get<Permissions>()
    protected val blocks = get<Blocks>()
    protected val posts = get<Posts>()

    /**
     * 获取用户在某一个板块的权限等级
     */
    suspend fun getPermission(bid: BlockId): PermissionLevel =
        if (hasGlobalAdmin()) PermissionLevel.ROOT
        else user?.let { permissions.getPermission(bid, it.id) } ?: PermissionLevel.NORMAL

    fun getGlobalPermission(): PermissionLevel =
        user?.permission ?: PermissionLevel.NORMAL

    suspend fun hasAdminIn(block: BlockId): Boolean =
        user != null && (getPermission(block) >= PermissionLevel.ADMIN)

    fun hasGlobalAdmin(): Boolean = user.hasGlobalAdmin()

    /// 可以看 ///

    suspend fun canRead(block: Block): Boolean = when (block.state)
    {
        NORMAL  -> getPermission(block.id) >= block.reading || user.hasGlobalAdmin()
        else -> user.hasGlobalAdmin()
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
            PRIVATE -> post.author == user?.id || hasGlobalAdmin()
            else -> hasGlobalAdmin()
        }
    }

    /// 可以删除 ///

    suspend fun canChangeState(post: PostInfo, newState: State): Boolean = canRead(post) && when (post.state)
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
                DELETED -> post.author == user?.id || hasGlobalAdmin()
                PRIVATE -> true
            }
        DELETED -> if (newState != DELETED) hasGlobalAdmin() else true
    }

    suspend fun canChangeState(block: Block, newState: State): Boolean
    {
        // 板块不能有私密状态
        if (newState == PRIVATE) return false
        if (!canRead(block)) return false
        if (block.state == newState) return true
        return when (block.state)
        {
            NORMAL -> hasAdminIn(block.id)
            else -> hasGlobalAdmin()
        }
    }

    /// 可以评论 ///

    suspend fun canComment(post: PostInfo): Boolean = canRead(post) && when (post.state)
    {
        NORMAL,PRIVATE  -> blocks.getBlock(post.block)?.let { getPermission(post.block) >= it.commenting } ?: false
        else -> false
    }

    /// 可以发贴 ///

    suspend fun canPost(block: Block): Boolean = when (block.state)
    {
        NORMAL  -> getPermission(block.id) >= block.posting && getPermission(block.id) >= block.reading
        else -> false
    }

    /// 可以匿名 ///

    suspend fun canAnonymous(block: Block): Boolean = when (block.state)
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
                return getGlobalPermission() >= maxOf(permission, PermissionLevel.ADMIN)

            // 如果尝试修改自己在某板块的权限
            // 如果目标权限比现在低, 直接通过
            if (permission < getPermission(block.id)) return true

            // 如果目标权限比现在高
            // 要么其父板块权限高于目标权限
            block.parent?.let { parent ->
                if (getPermission(parent) > permission) return true
            }
            // 要么其拥有全局管理员
            return hasGlobalAdmin()
        }
        if (block == null)
        {
            return hasGlobalAdmin() && other.permission < getGlobalPermission() && permission < getGlobalPermission()
        }
        val selfPermission = getPermission(block.id)
        if (selfPermission < PermissionLevel.ADMIN) return false
        val otherPermission = withPermission(other) { getPermission(block.id) }
        return selfPermission > otherPermission && selfPermission > permission
    }
}

class CheckPermissionInContextScope @PublishedApi internal constructor(val context: Context, user: DatabaseUser?):
    CheckPermissionScope(user)
{
    /**
     * 结束请求
     */
    private suspend fun finish(status: HttpStatus)
    {
        context.call.respond(status)
        context.finish()
    }

    suspend fun checkHasAdminIn(block: BlockId) = if (!hasAdminIn(block)) finish(HttpStatus.Forbidden) else Unit

    suspend fun checkHasGlobalAdmin() = if (!hasGlobalAdmin()) finish(HttpStatus.Forbidden) else Unit

    /// 可以看 ///

    suspend fun checkCanRead(block: Block)
    {
        if (!canRead(block))
            finish(HttpStatus.Forbidden)
    }

    suspend fun checkCanRead(post: PostInfo)
    {
        if (!canRead(post))
            finish(HttpStatus.Forbidden)
    }

    /// 可以删除 ///

    suspend fun checkCanChangeState(post: PostInfo, newState: State)
    {
        if (!canChangeState(post, newState))
            finish(HttpStatus.Forbidden)
    }

    /// 可以评论 ///

    suspend fun checkCanComment(post: PostInfo)
    {
        if (!canComment(post))
            finish(HttpStatus.Forbidden)
    }

    /// 可以发贴 ///

    suspend fun checkCanPost(block: Block)
    {
        if (!canPost(block))
            finish(HttpStatus.Forbidden)
    }

    /// 可以匿名 ///

    suspend fun checkCanAnonymous(block: Block)
    {
        if (!canAnonymous(block))
            finish(HttpStatus.Forbidden)
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
                if (getGlobalPermission() >= maxOf(permission, PermissionLevel.ADMIN)) return
                else return finish(
                    HttpStatus.Forbidden.copy(
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
            if (hasGlobalAdmin()) return

            // 返回消息, 根据有没有父板块返回不同的消息
            if (block.parent != null) finish(
                HttpStatus.Forbidden.copy(
                    message = "修改自己在板块${block.name}的权限要求在此板块的权限或在父板块的权限不低于目标权限, 或拥有全局管理员权限"
                )
            )
            else finish(
                HttpStatus.Forbidden.copy(
                    message = "修改自己在板块${block.name}的权限要求在此板块的权限不低于目标权限, 或拥有全局管理员权限"
                )
            )
            return
        }
        if (block == null)
        {
            if (hasGlobalAdmin() && other.permission < getGlobalPermission() && permission < getGlobalPermission())
                return
            else return finish(
                HttpStatus.Forbidden.copy(
                    message = "修改他人的全局权限要求拥有全局管理员权限, 且目标用户修改前后的权限都低于自己的全局权限"
                )
            )
        }
        val selfPermission = getPermission(block.id)
        if (selfPermission < PermissionLevel.ADMIN) return finish(
            HttpStatus.Forbidden.copy(
                message = "修改他人在板块${block.name}的权限要求拥有该板块管理员权限"
            )
        )
        val otherPermission = withPermission(other) { getPermission(block.id) }
        if (selfPermission > otherPermission && selfPermission > permission)
            return
        else return finish(
            HttpStatus.Forbidden.copy(
                message = "修改他人在板块${block.name}的权限要求拥有该板块管理员权限, 且目标用户修改前后的权限都低于自己的权限"
            )
        )
    }
}