package subit.database.sqlImpl

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import subit.dataClasses.Prohibit
import subit.dataClasses.Slice
import subit.dataClasses.UserId
import subit.database.Prohibits
import subit.database.sqlImpl.utils.asSlice

class ProhibitsImpl: DaoSqlImpl<ProhibitsImpl.ProhibitsTable>(ProhibitsTable), Prohibits
{
    object ProhibitsTable: IdTable<UserId>("prohibits")
    {
        val user = reference("user", UsersImpl.UsersTable)
        val time = timestamp("time")
        val reason = text("reason")
        val operator = reference("operator", UsersImpl.UsersTable).index()
        override val id = user
        override val primaryKey = PrimaryKey(user)
    }

    private fun deserialize(row: ResultRow) = Prohibit(
        row[ProhibitsTable.user].value,
        row[ProhibitsTable.time].toEpochMilliseconds(),
        row[ProhibitsTable.reason],
        row[ProhibitsTable.operator].value
    )

    override suspend fun addProhibit(prohibit: Prohibit): Unit = query()
    {
        insert {
            it[user] = prohibit.user
            it[time] = Instant.fromEpochMilliseconds(prohibit.time)
            it[reason] = prohibit.reason
            it[operator] = prohibit.operator
        }
    }

    override suspend fun removeProhibit(uid: UserId): Unit = query()
    {
        deleteWhere { user eq uid }
    }

    /**
     * 检查用户是否被禁止, true代表被封禁
     */
    override suspend fun isProhibited(uid: UserId): Boolean = query()
    {
        deleteWhere { time lessEq Clock.System.now() }
        selectAll().where { user eq uid }.count() > 0
    }

    override suspend fun getProhibitList(begin: Long, count: Int): Slice<Prohibit> = query()
    {
        ProhibitsTable.selectAll().asSlice(begin, count).map(::deserialize)
    }
}