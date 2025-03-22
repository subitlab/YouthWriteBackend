package subit.database

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.Star
import subit.dataClasses.UserId
import subit.database.utils.asSlice
import subit.utils.toInstant
import kotlin.time.Duration

/**
 * 收藏数据库交互类
 */
class Stars: DaoSqlImpl<Stars.StarsTable>(StarsTable)
{
    object StarsTable: Table("stars")
    {
        val user = reference("user", Users.UsersTable).index()
        val post = reference("post", Posts.PostsTable).index()
        val time = timestamp("time").defaultExpression(CurrentTimestamp).index()
    }

    private fun deserialize(row: ResultRow) = Star(
        user = row[StarsTable.user].value,
        post = row[StarsTable.post].value,
        time = row[StarsTable.time].toEpochMilliseconds()
    )

    suspend fun addStar(uid: UserId, pid: PostId): Unit = query()
    {
        if (getStar(uid, pid)) return@query
        insert {
            it[user] = uid
            it[post] = pid
        }
    }

    suspend fun removeStar(uid: UserId, pid: PostId): Unit = query()
    {
        deleteWhere {
            (user eq uid) and (post eq pid)
        }
    }

    suspend fun getStar(uid: UserId, pid: PostId): Boolean = query()
    {
        selectAll().where { (user eq uid) and (post eq pid) }.count() > 0
    }

    suspend fun getStarsCount(pid: PostId): Long = query()
    {
        StarsTable.selectAll().where { post eq pid }.count()
    }

    suspend fun getStars(
        user: UserId? = null,
        post: PostId? = null,
        reverseOrder: Boolean = true,
        begin: Long = 1,
        limit: Int = Int.MAX_VALUE,
    ): Slice<Star> = query()
    {
        val query = table.selectAll()
        query.orderBy(table.time, if (reverseOrder) SortOrder.DESC else SortOrder.ASC)
        user?.let { query.andWhere { table.user eq it } }
        post?.let { query.andWhere { table.post eq it } }
        query.asSlice(begin, limit).map { deserialize(it) }
    }

    suspend fun totalStarsCount(duration: Duration?): Long = query()
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        table.selectAll().where { table.time greaterEq time }.count()
    }
}