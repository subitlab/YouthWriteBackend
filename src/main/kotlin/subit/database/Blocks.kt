package subit.database

import subit.dataClasses.*

interface Blocks
{
    suspend fun createBlock(
        name: String,
        description: String,
        parent: BlockId?,
        creator: UserId,
        postingPermission: PermissionLevel = PermissionLevel.NORMAL,
        commentingPermission: PermissionLevel = PermissionLevel.NORMAL,
        readingPermission: PermissionLevel = PermissionLevel.NORMAL,
        anonymousPermission: PermissionLevel = PermissionLevel.NORMAL
    ): BlockId

    suspend fun setPermission(
        block: BlockId,
        posting: PermissionLevel?,
        commenting: PermissionLevel?,
        reading: PermissionLevel?,
        anonymous: PermissionLevel?
    )

    suspend fun getBlock(block: BlockId): Block?
    suspend fun setState(block: BlockId, state: State)
    suspend fun getChildren(loginUser: UserFull?, parent: BlockId?, begin: Long, count: Int): Slice<Block>

    /**
     * 获取所有板块
     * @param loginUser 登录用户, 用于权限判断, null表示未登录
     * @param editable 是否只获取可编辑的板块
     * @param key 若不为null则筛选板块名称包含关键词的板块
     * @param begin 起始位置
     * @param count 数量
     */
    suspend fun getBlocks(
        loginUser: UserFull?,
        editable: Boolean,
        key: String?,
        begin: Long,
        count: Int
    ): Slice<Block>
}