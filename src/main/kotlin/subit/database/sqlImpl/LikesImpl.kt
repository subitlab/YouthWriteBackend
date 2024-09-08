package subit.database.sqlImpl

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.koin.core.component.KoinComponent
import subit.dataClasses.PostId
import subit.dataClasses.UserId
import subit.database.Likes
import subit.utils.Locks

class LikesImpl: DaoSqlImpl<LikesImpl.LikesTable>(LikesTable), Likes, KoinComponent
{
    object LikesTable: Table("likes")
    {
        val user = reference("user", UsersImpl.UsersTable).index()
        val post = reference("post", PostsImpl.PostsTable).index()
    }

    private val locks = Locks<Pair<UserId, PostId>>()

    override suspend fun like(uid: UserId, pid: PostId): Unit = query()
    {
        locks.withLock(uid to pid)
        {
            if (getLike(uid, pid)) return@query
            insert {
                it[user] = uid
                it[post] = pid
            }
        }
    }

    override suspend fun unlike(uid: UserId, pid: PostId): Unit = query()
    {
        deleteWhere {
            (user eq uid) and (post eq pid)
        }
    }

    override suspend fun getLike(uid: UserId, pid: PostId): Boolean = query()
    {
        selectAll().where { (user eq uid) and (post eq pid) }.count() > 0
    }

    override suspend fun getLikes(post: PostId): Long = query()
    {
        selectAll().where { table.post eq post }.count()
    }
}