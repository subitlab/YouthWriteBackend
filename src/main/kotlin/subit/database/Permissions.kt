@file:Suppress("unused")

package subit.database

import subit.dataClasses.BlockId
import subit.dataClasses.PermissionLevel
import subit.dataClasses.UserId

interface Permissions
{
    suspend fun setPermission(bid: BlockId, uid: UserId, permission: PermissionLevel)
    suspend fun getPermission(block: BlockId, user: UserId): PermissionLevel
}