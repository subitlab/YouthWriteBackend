package subit.database

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import subit.dataClasses.DatabaseUser
import subit.dataClasses.PermissionLevel
import subit.dataClasses.UserId
import subit.database.utils.single

class Users: DaoSqlImpl<Users.UsersTable>(UsersTable)
{
    /**
     * 用户信息表
     */
    object UsersTable: IdTable<UserId>("users")
    {
        override val id = userId("id").entityId()
        val introduction = text("introduction").nullable().default(null)
        val showStars = bool("show_stars").default(true)
        val permission = enumeration<PermissionLevel>("permission").default(PermissionLevel.NORMAL)
        val filePermission = enumeration<PermissionLevel>("file_permission").default(PermissionLevel.NORMAL)
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = DatabaseUser(
        id = row[UsersTable.id].value,
        introduction = row[UsersTable.introduction] ?: "",
        showStars = row[UsersTable.showStars],
        permission = row[UsersTable.permission],
        filePermission = row[UsersTable.filePermission],
    )

    /**
     * 若用户不存在返回false
     */
    suspend fun changeIntroduction(id: UserId, introduction: String?): Boolean = query()
    {
        update({ UsersTable.id eq id }) { it[UsersTable.introduction] = introduction } > 0
    }

    /**
     * 若用户不存在返回false
     */
    suspend fun changeShowStars(id: UserId, showStars: Boolean): Boolean = query()
    {
        update({ UsersTable.id eq id }) { it[UsersTable.showStars] = showStars } > 0
    }

    /**
     * 若用户不存在返回false
     */
    suspend fun changePermission(id: UserId, permission: PermissionLevel): Boolean = query()
    {
        update({ UsersTable.id eq id }) { it[UsersTable.permission] = permission } > 0
    }

    /**
     * 若用户不存在返回false
     */
    suspend fun changeFilePermission(id: UserId, permission: PermissionLevel): Boolean = query()
    {
        update({ UsersTable.id eq id }) { it[filePermission] = permission } > 0
    }

    suspend fun getOrCreateUser(id: UserId): DatabaseUser = query()
    {
        insertIgnore { it[UsersTable.id] = id }
        selectAll().where { UsersTable.id eq id }.single().let(::deserialize)
    }
}