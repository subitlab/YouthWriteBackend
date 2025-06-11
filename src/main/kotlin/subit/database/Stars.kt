package subit.database

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.inject
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.Star
import subit.dataClasses.UserId
import subit.database.utils.asSlice
import subit.database.utils.single
import subit.utils.toInstant
import kotlin.time.Duration

/**
 * 收藏数据库交互类
 */
class Stars: DaoSqlImpl<Stars.StarsTable>(StarsTable)
{
    object StarsTable: CompositeIdTable("stars")
    {
        val user = reference("user", Users.UsersTable).index()
        val post = reference("post", Posts.PostTable).index()
        val time = timestamp("time").index().defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(Stars.StarsTable.user, Stars.StarsTable.post)

        init
        {
            addIdColumn(Stars.StarsTable.user)
            addIdColumn(Stars.StarsTable.post)
        }
    }

    private val posts: Posts by inject()

    private fun deserialize(row: ResultRow) = Star(
        user = row[StarsTable.user].value,
        post = row[StarsTable.post].value,
        time = row[StarsTable.time].toEpochMilliseconds()
    )

    suspend fun addStar(uid: UserId, pid: PostId): Unit = query()
    {
        val id = insertIgnoreAndGetId {
            it[StarsTable.user] = uid
            it[StarsTable.post] = pid
        }
        if (id == null) return@query
        val star = posts.table.select(posts.table.starCount).where { posts.table.id eq pid }.single()[posts.table.starCount]
        posts.table.update({ posts.table.id eq pid }) {
            it[posts.table.starCount] = star + 1
        }
    }

    suspend fun removeStar(uid: UserId, pid: PostId): Unit = query()
    {
        val count = deleteWhere {
            (StarsTable.user eq uid) and (StarsTable.post eq pid)
        }
        if (count == 0) return@query
        val star = posts.table.select(posts.table.starCount).where { posts.table.id eq pid }.single()[posts.table.starCount]
        posts.table.update({ posts.table.id eq pid }) {
            it[posts.table.starCount] = star - count
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
        begin: Long = 0,
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