package subit.database.sqlImpl

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.KoinComponent
import subit.dataClasses.Like
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.UserId
import subit.database.Likes
import subit.database.sqlImpl.utils.asSlice

class LikesImpl: DaoSqlImpl<LikesImpl.LikesTable>(LikesTable), Likes, KoinComponent
{
    object LikesTable: Table("likes")
    {
        val user = reference("user", UsersImpl.UsersTable).index()
        val post = reference("post", PostsImpl.PostsTable).index()
        val time = timestamp("time").index().defaultExpression(CurrentTimestamp)
    }

    private fun deserialize(row: ResultRow) = Like(
        user = row[LikesTable.user].value,
        post = row[LikesTable.post].value,
        time = row[LikesTable.time].toEpochMilliseconds()
    )

    override suspend fun addLike(uid: UserId, pid: PostId): Unit = query()
    {
        if (getLike(uid, pid)) return@query
        insert {
            it[user] = uid
            it[post] = pid
        }
    }

    override suspend fun removeLike(uid: UserId, pid: PostId): Unit = query()
    {
        deleteWhere {
            (user eq uid) and (post eq pid)
        }
    }

    override suspend fun getLike(uid: UserId, pid: PostId): Boolean = query()
    {
        selectAll().where { (user eq uid) and (post eq pid) }.count() > 0
    }

    override suspend fun getLikesCount(pid: PostId): Long = query()
    {
        LikesTable.selectAll().where { post eq pid }.count()
    }

    override suspend fun getLikes(user: UserId?, post: PostId?, begin: Long, limit: Int): Slice<Like> = query()
    {
        val query = LikesTable.selectAll()
        user?.let { query.andWhere { LikesTable.user eq it } }
        post?.let { query.andWhere { LikesTable.post eq it } }
        query.asSlice(begin, limit).map { deserialize(it) }
    }
}