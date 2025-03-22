package subit.database

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.KoinComponent
import subit.dataClasses.Like
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.UserId
import subit.database.utils.asSlice
import subit.utils.toInstant
import kotlin.time.Duration

class Likes: DaoSqlImpl<Likes.LikesTable>(LikesTable), KoinComponent
{
    object LikesTable: Table("likes")
    {
        val user = reference("user", Users.UsersTable).index()
        val post = reference("post", Posts.PostsTable).index()
        val time = timestamp("time").index().defaultExpression(CurrentTimestamp)
    }

    private fun deserialize(row: ResultRow) = Like(
        user = row[LikesTable.user].value,
        post = row[LikesTable.post].value,
        time = row[LikesTable.time].toEpochMilliseconds()
    )

    suspend fun addLike(uid: UserId, pid: PostId): Unit = query()
    {
        if (getLike(uid, pid)) return@query
        insert {
            it[user] = uid
            it[post] = pid
        }
    }

    suspend fun removeLike(uid: UserId, pid: PostId): Unit = query()
    {
        deleteWhere {
            (user eq uid) and (post eq pid)
        }
    }

    suspend fun getLike(uid: UserId, pid: PostId): Boolean = query()
    {
        selectAll().where { (user eq uid) and (post eq pid) }.count() > 0
    }

    suspend fun getLikesCount(pid: PostId): Long = query()
    {
        LikesTable.selectAll().where { post eq pid }.count()
    }

    suspend fun getLikes(
        user: UserId? = null,
        post: PostId? = null,
        reverseOrder: Boolean = true,
        begin: Long = 1,
        limit: Int = Int.MAX_VALUE,
    ): Slice<Like> = query()
    {
        val query = table.selectAll()
        query.orderBy(table.time, if (reverseOrder) SortOrder.DESC else SortOrder.ASC)
        user?.let { query.andWhere { table.user eq it } }
        post?.let { query.andWhere { table.post eq it } }
        query.asSlice(begin, limit).map { deserialize(it) }
    }

    suspend fun totalLikesCount(duration: Duration?): Long = query()
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        table.selectAll().where { table.time greaterEq time }.count()
    }
}