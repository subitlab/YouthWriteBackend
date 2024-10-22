package subit.database.sqlImpl

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.Star
import subit.dataClasses.UserId
import subit.database.Stars
import subit.database.sqlImpl.utils.asSlice
import subit.utils.toInstant
import kotlin.time.Duration

/**
 * 收藏数据库交互类
 */
class StarsImpl: DaoSqlImpl<StarsImpl.StarsTable>(StarsTable), Stars
{
    object StarsTable: Table("stars")
    {
        val user = reference("user", UsersImpl.UsersTable).index()
        val post = reference("post", PostsImpl.PostsTable).index()
        val time = timestamp("time").defaultExpression(CurrentTimestamp).index()
    }

    private fun deserialize(row: ResultRow) = Star(
        user = row[StarsTable.user].value,
        post = row[StarsTable.post].value,
        time = row[StarsTable.time].toEpochMilliseconds()
    )

    override suspend fun addStar(uid: UserId, pid: PostId): Unit = query()
    {
        if (getStar(uid, pid)) return@query
        insert {
            it[user] = uid
            it[post] = pid
        }
    }

    override suspend fun removeStar(uid: UserId, pid: PostId): Unit = query()
    {
        deleteWhere {
            (user eq uid) and (post eq pid)
        }
    }

    override suspend fun getStar(uid: UserId, pid: PostId): Boolean = query()
    {
        selectAll().where { (user eq uid) and (post eq pid) }.count() > 0
    }

    override suspend fun getStarsCount(pid: PostId): Long = query()
    {
        StarsTable.selectAll().where { post eq pid }.count()
    }

    override suspend fun getStars(
        user: UserId?,
        post: PostId?,
        begin: Long,
        limit: Int,
    ): Slice<Star> = query()
    {
        var q = selectAll()

        if (user != null) q = q.andWhere { StarsTable.user eq user }
        if (post != null) q = q.andWhere { StarsTable.post eq post }

        q.asSlice(begin, limit).map(::deserialize)
    }

    override suspend fun totalStarsCount(duration: Duration?): Long = query()
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        table.selectAll().where { table.time greaterEq time }.count()
    }
}