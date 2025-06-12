package subit.database

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import subit.dataClasses.*
import subit.database.utils.singleOrNull

class OldUsers: DaoSqlImpl<OldUsers.OldUsersTable>(OldUsersTable)
{
    /**
     * 用户信息表
     */
    object OldUsersTable: Table("old_users")
    {
        val id = reference("id", Users.UsersTable).uniqueIndex()
        val name = varchar("name", 100).index()
        val email = varchar("email", 255).uniqueIndex()
        val registrationTime = timestamp("registration_time").defaultExpression(CurrentTimestamp)
        val newId = reference("new_id", Users.UsersTable).nullable().index()
        val avatar = text("avatar").nullable().default(null)
    }

    private fun deserialize(row: ResultRow) = SsoUserFull(
        id = row[OldUsersTable.id].value,
        username = row[OldUsersTable.name],
        email = listOf(row[OldUsersTable.email]),
        registrationTime = row[OldUsersTable.registrationTime].toEpochMilliseconds(),
        phone = "",
        seiue = emptyList() // 旧用户没有seiue信息
    )

    suspend fun hasUser(id: UserId): Boolean = query()
    {
        selectAll().where { OldUsersTable.id eq id }.count() > 0
    }

    suspend fun getNewId(id: UserId): UserId? = query()
    {
        selectAll().where { OldUsersTable.id eq id }.singleOrNull()?.get(newId)?.value
    }

    suspend fun setNewId(oldId: UserId, newId: UserId): Boolean = query()
    {
        table.update({ id eq oldId }) {
            it[OldUsersTable.newId] = newId
        } > 0
    }

    suspend fun getOldUser(id: UserId): SsoUserFull? = query()
    {
        selectAll().where { OldUsersTable.id eq id }.singleOrNull()?.let(::deserialize)
    }

    suspend fun getAvatar(id: UserId): String? = query()
    {
        selectAll().where { OldUsersTable.id eq id }.singleOrNull()?.get(avatar)
    }

    suspend fun getEmailUser(email: String): UserId? = query()
    {
        selectAll().where { OldUsersTable.email eq email.lowercase() }.singleOrNull()?.get(id)?.value
    }

}