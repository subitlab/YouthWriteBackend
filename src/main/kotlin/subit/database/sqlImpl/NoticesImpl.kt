package subit.database.sqlImpl

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.KoinComponent
import subit.dataClasses.*
import subit.dataClasses.Notice.*
import subit.dataClasses.Notice.Type.SYSTEM
import subit.dataClasses.Slice
import subit.database.Notices
import subit.database.sqlImpl.utils.asSlice
import subit.database.sqlImpl.utils.singleOrNull

class NoticesImpl: DaoSqlImpl<NoticesImpl.NoticesTable>(NoticesTable), Notices, KoinComponent
{
    object NoticesTable: IdTable<NoticeId>("notices")
    {
        override val id = noticeId("id").autoIncrement().entityId()
        val user = reference("user", UsersImpl.UsersTable).index()
        val time = timestamp("time").defaultExpression(CurrentTimestamp)
        val type = enumerationByName<Type>("type", 20).index()
        val post = reference("post", PostsImpl.PostsTable).nullable().index()
        val operator = reference("operator", UsersImpl.UsersTable).nullable()
        val content = text("content")
        val read = bool("read").default(false)
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = table.run()
    {
        val id = row[id].value
        val obj = row[post]?.value ?: PostId(0)
        val time = row[time].toEpochMilliseconds()
        val type = row[type]
        val read = row[read]
        val user = row[user].value
        val content = row[content]
        val operator = row[operator]?.value ?: UserId(0)
        if (type == SYSTEM) SystemNotice(id, time, user, read, content)
        else PostNotice(id = id, time = time, type = type, user = user, read = read, post = obj, operator = operator, content = content)
    }

    override suspend fun createNotice(notice: Notice): Unit = query()
    {
        when (notice)
        {
            is SystemNotice -> insert()
            {
                it[user] = notice.user
                it[type] = notice.type
                it[post] = null
                it[content] = notice.content
            }
            is PostNotice -> insert()
            {
                it[user] = notice.user
                it[type] = notice.type
                it[post] = notice.post
                it[operator] = notice.operator
                it[content] = notice.content
            }
        }
    }

    override suspend fun getNotice(id: NoticeId): Notice? = query()
    {
        selectAll().where { table.id eq id }.singleOrNull()?.let(::deserialize)
    }

    override suspend fun getNotices(user: UserId, type: Type?, read: Boolean?, begin: Long, count: Int): Slice<Notice> = query()
    {
        selectAll()
            .andWhere { table.user eq user }
            .apply { type?.let { andWhere { table.type eq it } } }
            .apply { read?.let { andWhere { table.read eq it } } }
            .orderBy(table.time, SortOrder.DESC)
            .asSlice(begin, count)
            .map(::deserialize)
    }

    override suspend fun readNotice(id: NoticeId): Unit = query()
    {
        update({ table.id eq id }) { it[read] = true }
    }

    override suspend fun readNotices(user: UserId): Unit = query()
    {
        update({ table.user eq user }) { it[read] = true }
    }

    override suspend fun deleteNotice(id: NoticeId): Unit = query()
    {
        deleteWhere { table.id eq id }
    }

    override suspend fun deleteNotices(user: UserId): Unit = query()
    {
        deleteWhere { table.user eq user }
    }
}