package subit.database

import subit.JWTAuth
import subit.dataClasses.*
import subit.database.sqlImpl.UsersImpl
import subit.utils.SSO

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
}