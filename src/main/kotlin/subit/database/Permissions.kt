package subit.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.koin.core.component.KoinComponent
import subit.dataClasses.BlockId
import subit.dataClasses.PermissionLevel
import subit.dataClasses.UserId
import subit.database.utils.singleOrNull

class Permissions: DaoSqlImpl<Permissions.PermissionsTable>(PermissionsTable), KoinComponent
{
    object PermissionsTable: Table("permissions")
    {
        val user = reference("user", Users.UsersTable).index()
        val block = reference("block", Blocks.BlocksTable).index()
        val permission = enumeration("permission", PermissionLevel::class).default(PermissionLevel.NORMAL)
    }

    /**
     * 设置一个用户在一个板块的权限
     */
    suspend fun setPermission(bid: BlockId, uid: UserId, permission: PermissionLevel): Unit = query()
    {
        if (permission == PermissionLevel.NORMAL)
        {
            deleteWhere { (user eq uid) and (block eq bid) }
            return@query
        }
        val count = update({ (user eq uid) and (block eq bid) })
        {
            it[PermissionsTable.permission] = permission
        }

        if (count > 0) return@query

        // 如果没有更新到任何数据, 说明这个权限不存在, 需要插入
        insert()
        {
            it[user] = uid
            it[block] = bid
            it[PermissionsTable.permission] = permission
        }
    }

    suspend fun getPermission(block: BlockId, user: UserId): PermissionLevel = query()
    {
        select(permission).where {
            (PermissionsTable.user eq user) and (PermissionsTable.block eq block)
        }.singleOrNull()?.getOrNull(permission) ?: PermissionLevel.NORMAL
    }
}