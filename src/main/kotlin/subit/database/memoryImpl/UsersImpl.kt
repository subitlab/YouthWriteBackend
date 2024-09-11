package subit.database.memoryImpl

import subit.dataClasses.*
import subit.database.Users
import java.util.*

class UsersImpl: Users
{
    private val map = Collections.synchronizedMap(hashMapOf<UserId, DatabaseUser>())


    override suspend fun changeIntroduction(id: UserId, introduction: String): Boolean =
        map[id]?.let {
            map[id] = it.copy(introduction = introduction)
            true
        } ?: false

    override suspend fun changeShowStars(id: UserId, showStars: Boolean): Boolean =
        map[id]?.let {
            map[id] = it.copy(showStars = showStars)
            true
        } ?: false

    override suspend fun changePermission(id: UserId, permission: PermissionLevel): Boolean =
        map[id]?.let {
            map[id] = it.copy(permission = permission)
            true
        } ?: false

    override suspend fun changeFilePermission(id: UserId, permission: PermissionLevel): Boolean =
        map[id]?.let {
            map[id] = it.copy(filePermission = permission)
            true
        } ?: false

    override suspend fun getOrCreateUser(id: UserId): DatabaseUser
    {
        if (id in map) return map[id]!!
        val user = DatabaseUser(
            id = id,
            introduction = null,
            showStars = false,
            permission = PermissionLevel.NORMAL,
            filePermission = PermissionLevel.NORMAL
        )
        map[id] = user
        return user
    }
}