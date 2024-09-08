package subit.database.sqlImpl

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.koin.core.component.KoinComponent
import subit.dataClasses.*
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.Slice.Companion.singleOrNull
import subit.database.PostVersions

class PostVersionsImpl: DaoSqlImpl<PostVersionsImpl.PostVersionsTable>(PostVersionsTable), PostVersions, KoinComponent
{
    object PostVersionsTable: IdTable<PostVersionId>("post_versions")
    {
        override val id = postVersionId("id").autoIncrement().entityId()
        val post = reference("post", PostsImpl.PostsTable)
        val title = varchar("title", 255)
        val content = text("content")
        val time = timestamp("time").defaultExpression(CurrentTimestamp).index()
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserializePostVersion(row: ResultRow): PostVersionInfo = PostVersionInfo(
        id = row[PostVersionsTable.id].value,
        post = row[PostVersionsTable.post].value,
        title = row[PostVersionsTable.title],
        content = row[PostVersionsTable.content],
        time = row[PostVersionsTable.time].toEpochMilliseconds()
    )

    private fun deserializePostVersionBasicInfo(row: ResultRow): PostVersionBasicInfo = PostVersionBasicInfo(
        id = row[PostVersionsTable.id].value,
        post = row[PostVersionsTable.post].value,
        title = row[PostVersionsTable.title],
        time = row[PostVersionsTable.time].toEpochMilliseconds()
    )

    override suspend fun createPostVersion(post: PostId, title: String, content: String): PostVersionId = query()
    {
        insert {
            it[this.post] = post
            it[this.title] = title
            it[this.content] = content
        }[id].value
    }

    override suspend fun getPostVersion(pid: PostVersionId): PostVersionInfo? = query()
    {
        selectAll().where { id eq pid }.singleOrNull()?.let(::deserializePostVersion)
    }

    override suspend fun getPostVersions(post: PostId, begin: Long, count: Int): Slice<PostVersionBasicInfo> = query()
    {
        select(id, table.post, title, time)
            .where { PostVersionsTable.post eq post }
            .orderBy(time, SortOrder.DESC)
            .asSlice(begin, count)
            .map { deserializePostVersionBasicInfo(it) }
    }

    override suspend fun getLatestPostVersion(post: PostId): PostVersionId? = query()
    {
        select(id).where { PostVersionsTable.post eq post }.orderBy(time, SortOrder.DESC).singleOrNull()?.let { it[id].value }
    }
}