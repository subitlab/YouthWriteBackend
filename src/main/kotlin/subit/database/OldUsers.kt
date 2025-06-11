package subit.database

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
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
    object OldUsersTable: IdTable<UserId>("old_users")
    {
        override val id = userId("id").entityId()
        val name = varchar("name", 100).index()
        val email = varchar("email", 255).uniqueIndex()
        val registrationTime = timestamp("registration_time").defaultExpression(CurrentTimestamp)
        val newId = reference("new_id", Users.UsersTable).nullable().index()
        override val primaryKey = PrimaryKey(id)
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

    suspend fun getEmailUser(email: String): UserId? = query()
    {
        selectAll().where { OldUsersTable.email eq email.lowercase() }.singleOrNull()?.get(id)?.value
    }

}