package subit.database

import subit.dataClasses.DatabaseUser
import subit.dataClasses.PermissionLevel
import subit.dataClasses.UserId

interface Users
{
    suspend fun getOrCreateUser(id: UserId): DatabaseUser

    /**
     * 若用户不存在返回false
     */
    suspend fun changeIntroduction(id: UserId, introduction: String): Boolean

    /**
     * 若用户不存在返回false
     */
    suspend fun changeShowStars(id: UserId, showStars: Boolean): Boolean

    /**
     * 若用户不存在返回false
     */
    suspend fun changePermission(id: UserId, permission: PermissionLevel): Boolean

    /**
     * 若用户不存在返回false
     */
    suspend fun changeFilePermission(id: UserId, permission: PermissionLevel): Boolean

    /**
     * 设置同类型消息是否合并
     */
    suspend fun changeMergeNotice(id: UserId, mergeNotice: Boolean): Boolean
}