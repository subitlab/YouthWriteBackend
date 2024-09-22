package subit.database.sqlImpl

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.KoinComponent
import subit.dataClasses.*
import subit.dataClasses.Slice
import subit.database.PostVersions
import subit.database.sqlImpl.utils.asSlice
import subit.database.sqlImpl.utils.singleOrNull
import subit.plugin.contentNegotiationJson

class PostVersionsImpl: DaoSqlImpl<PostVersionsImpl.PostVersionsTable>(PostVersionsTable), PostVersions, KoinComponent
{
    object PostVersionsTable: IdTable<PostVersionId>("post_versions")
    {
        override val id = postVersionId("id").autoIncrement().entityId()
        val post = reference("post", PostsImpl.PostsTable)
        val title = varchar("title", 255)
        val content = text("content")
        val time = timestamp("time").defaultExpression(CurrentTimestamp).index()
        val draft = bool("draft").default(false)
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserializePostVersion(row: ResultRow): PostVersionInfo = PostVersionInfo(
        id = row[PostVersionsTable.id].value,
        post = row[PostVersionsTable.post].value,
        title = row[PostVersionsTable.title],
        content = if (!row[PostVersionsTable.draft]) row[PostVersionsTable.content] else null,
        operation = if (row[PostVersionsTable.draft]) contentNegotiationJson.decodeFromString(row[PostVersionsTable.content]) else null,
        time = row[PostVersionsTable.time].toEpochMilliseconds(),
        draft = row[PostVersionsTable.draft],
    )

    private fun deserializePostVersionBasicInfo(row: ResultRow): PostVersionBasicInfo = PostVersionBasicInfo(
        id = row[PostVersionsTable.id].value,
        post = row[PostVersionsTable.post].value,
        title = row[PostVersionsTable.title],
        time = row[PostVersionsTable.time].toEpochMilliseconds(),
        draft = row[PostVersionsTable.draft],
    )

    override suspend fun createPostVersion(
        post: PostId,
        title: String,
        content: String,
        draft: Boolean
    ): PostVersionId = query()
    {
        insertAndGetId {
            it[this.post] = post
            it[this.title] = title
            it[this.content] = content
            it[this.draft] = draft
        }.value
    }

    override suspend fun getPostVersion(pid: PostVersionId): PostVersionInfo? = query()
    {
        selectAll().where { id eq pid }.singleOrNull()?.let(::deserializePostVersion)
    }

    override suspend fun getPostVersions(
        post: PostId,
        containsDraft: Boolean,
        begin: Long,
        count: Int
    ): Slice<PostVersionBasicInfo> = query()
    {
        select(id, table.post, title, time, draft)
            .andWhere { PostVersionsTable.post eq post }
            .apply { if (!containsDraft) andWhere { draft eq false } }
            .orderBy(time, SortOrder.DESC)
            .asSlice(begin, count)
            .map { deserializePostVersionBasicInfo(it) }
    }

    override suspend fun getLatestPostVersion(post: PostId, containsDraft: Boolean): PostVersionId? = query()
    {
        select(id)
            .andWhere { PostVersionsTable.post eq post }
            .apply { if (!containsDraft) andWhere { draft eq false } }
            .orderBy(time, SortOrder.DESC)
            .singleOrNull()
            ?.let { it[id].value }
    }
}