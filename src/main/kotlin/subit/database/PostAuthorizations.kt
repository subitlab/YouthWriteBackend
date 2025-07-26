package subit.database

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import subit.dataClasses.*
import subit.database.Likes.LikeTable
import subit.database.utils.asSlice

class PostAuthorizations: DaoSqlImpl<PostAuthorizations.AuthorizationTable>(AuthorizationTable)
{
    /**
     * 用户信息表
     */
    object AuthorizationTable: Table("authorization")
    {
        val from = reference("from", Users.UsersTable).index()
        val to = reference("to", Users.UsersTable).index()
        val post = reference("post", Posts.PostTable).index()
        val time = timestamp("time").index().defaultExpression(CurrentTimestamp)

        override val primaryKey = PrimaryKey(from, to, post)
    }

    private fun deserialize(row: ResultRow) = PostAuthorization(
        from = row[AuthorizationTable.from].value,
        to = row[AuthorizationTable.to].value,
        post = row[LikeTable.post].value,
        time = row[LikeTable.time].toEpochMilliseconds()
    )

    suspend fun hasAuthorized(userId: UserId, postId: PostId): Boolean = query()
    {
        selectAll().where { (AuthorizationTable.to eq userId) and (AuthorizationTable.post eq postId) }.count() > 0
    }

    suspend fun authorize(from: UserId, to: UserId, postId: PostId): Unit = query()
    {
        insertIgnore {
            it[AuthorizationTable.from] = from
            it[AuthorizationTable.to] = to
            it[AuthorizationTable.post] = postId
        }
    }

    suspend fun getAuthorizations(
        postId: PostId? = null,
        fromId: UserId? = null,
        toId: UserId? = null,
        begin: Long,
        limit: Int,
    ): Slice<PostAuthorization> = query()
    {
        selectAll()
            .apply { if(postId != null ) this.andWhere { post eq postId } }
            .apply { if(fromId != null ) this.andWhere { from eq fromId } }
            .apply { if(toId != null ) this.andWhere { to eq toId } }
            .orderBy(time, SortOrder.DESC)
            .asSlice(begin, limit)
            .map(::deserialize)
    }

    suspend fun deleteAuthorization(fromId: UserId, toId: UserId, postId: PostId): Boolean = query()
    {
        deleteWhere {
            (AuthorizationTable.from eq fromId) and
            (AuthorizationTable.to eq toId) and
            (AuthorizationTable.post eq postId)
        } > 0
    }

    suspend fun deleteAuthorizations(postId: PostId): Boolean = query()
    {
        deleteWhere { AuthorizationTable.post eq postId } > 0
    }


}