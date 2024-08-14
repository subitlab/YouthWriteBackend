package subit.database.sqlImpl

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import subit.JWTAuth
import subit.dataClasses.*
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.Slice.Companion.single
import subit.database.Users

class UsersImpl: DaoSqlImpl<UsersImpl.UserTable>(UserTable), Users
{
    /**
     * 用户信息表
     */
    object UserTable: IdTable<UserId>("users")
    {
        override val id = userId("id").entityId()
        val introduction = text("introduction").nullable().default(null)
        val showStars = bool("show_stars").default(true)
        val permission = enumeration<PermissionLevel>("permission").default(PermissionLevel.NORMAL)
        val filePermission = enumeration<PermissionLevel>("file_permission").default(PermissionLevel.NORMAL)
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = DatabaseUser(
        id = row[UserTable.id].value,
        introduction = row[UserTable.introduction] ?: "",
        showStars = row[UserTable.showStars],
        permission = row[UserTable.permission],
        filePermission = row[UserTable.filePermission]
    )

    override suspend fun changeIntroduction(id: UserId, introduction: String): Boolean = query()
    {
        update({ UserTable.id eq id }) { it[UserTable.introduction] = introduction } > 0
    }

    override suspend fun changeShowStars(id: UserId, showStars: Boolean): Boolean = query()
    {
        update({ UserTable.id eq id }) { it[UserTable.showStars] = showStars } > 0
    }

    override suspend fun changePermission(id: UserId, permission: PermissionLevel): Boolean = query()
    {
        update({ UserTable.id eq id }) { it[UserTable.permission] = permission } > 0
    }

    override suspend fun changeFilePermission(id: UserId, permission: PermissionLevel): Boolean = query()
    {
        update({ UserTable.id eq id }) { it[filePermission] = permission } > 0
    }

    override suspend fun getOrCreateUser(id: UserId): DatabaseUser = query()
    {
        insertIgnore { it[UserTable.id] = id }
        selectAll().where { UserTable.id eq id }.single().let(::deserialize)
    }
}