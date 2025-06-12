package subit.database

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.Like
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.UserId
import subit.database.utils.asSlice
import subit.database.utils.single
import subit.utils.toInstant
import kotlin.time.Duration

class Likes: DaoSqlImpl<Likes.LikesTable>(LikesTable), KoinComponent
{
    object LikesTable: CompositeIdTable("likes")
    {
        val user = reference("user", Users.UsersTable).index()
        val post = reference("post", Posts.PostTable).index()
        val time = timestamp("time").index().defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(user, post)

        init
        {
            addIdColumn(user)
            addIdColumn(post)
        }
    }

    private val posts: Posts by inject()

    private fun deserialize(row: ResultRow) = Like(
        user = row[LikesTable.user].value,
        post = row[LikesTable.post].value,
        time = row[LikesTable.time].toEpochMilliseconds()
    )

    suspend fun addLike(uid: UserId, pid: PostId): Unit = query()
    {
        val id = insertIgnoreAndGetId {
            it[user] = uid
            it[post] = pid
        }
        if (id == null) return@query
        val like = posts.table.select(posts.table.likeCount).where { posts.table.id eq pid }.single()[posts.table.likeCount]
        posts.table.update({ posts.table.id eq pid }) {
            it[posts.table.likeCount] = like + 1
        }
    }

    suspend fun removeLike(uid: UserId, pid: PostId): Unit = query()
    {
        val count = deleteWhere {
            (user eq uid) and (post eq pid)
        }
        if (count == 0) return@query
        val like = posts.table.select(posts.table.likeCount).where { posts.table.id eq pid }.single()[posts.table.likeCount]
        posts.table.update({ posts.table.id eq pid }) {
            it[posts.table.likeCount] = like - count
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

    suspend fun claimUserLikes(oldUserId: UserId, newUserId: UserId): Unit = query()
    {
        table.update({ table.user eq oldUserId }) {
            it[user] = newUserId
        }
    }
}