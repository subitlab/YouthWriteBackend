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
        if (type == SYSTEM) SystemNotice(id, time, row[user].value, read, row[content])
        else PostNotice(id, time, type, row[user].value, read, obj, row[content].toLongOrNull()?: 1)
    }

    override suspend fun createNotice(notice: Notice, merge: Boolean): Unit = query()
    {
        // 如果是系统通知，直接插入一条新消息
        if (notice is SystemNotice) insert()
        {
            it[user] = notice.user
            it[type] = notice.type
            it[post] = null
            it[content] = notice.content
        }
        else if (notice is PostNotice && !merge)
        {
            insert()
            {
                it[user] = notice.user
                it[type] = notice.type
                it[post] = notice.post
                it[content] = notice.count.toString()
            }
        }
        // 否则需要考虑同类型消息的合并
        else if (notice is PostNotice)
        {
            val result = select(id, content)
                .andWhere { user eq notice.user }
                .andWhere { type eq notice.type }
                .andWhere { table.post eq notice.post }
                .andWhere { read eq false }
                .singleOrNull()

            if (result == null) insert {
                it[user] = notice.user
                it[type] = notice.type
                it[post] = notice.post
                it[content] = notice.count.toString()
            }
            else
            {
                val id = result[table.id].value
                val count = (result[table.content].toLongOrNull() ?: 1) + notice.count
                update({ table.id eq id }) { it[content] = count.toString() }
            }
        }
        else error("Unknown notice type: $notice")
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

    override suspend fun unreadNotice(id: NoticeId): Unit = query()
    {
        update({ table.id eq id }) { it[read] = false }
    }

    override suspend fun unreadNotices(user: UserId): Unit = query()
    {
        update({ table.user eq user }) { it[read] = false }
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