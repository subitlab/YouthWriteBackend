package subit.database.sqlImpl

import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.KoinComponent
import subit.dataClasses.*
import subit.database.PostVersions
import subit.database.sqlImpl.utils.asSlice
import subit.database.sqlImpl.utils.singleOrNull
import subit.plugin.contentNegotiation.dataJson
import subit.utils.getContentText

class PostVersionsImpl: DaoSqlImpl<PostVersionsImpl.PostVersionTable>(PostVersionTable), PostVersions, KoinComponent
{
    object PostVersionTable: IdTable<PostVersionId>("post_versions")
    {
        override val id = postVersionId("id").autoIncrement().entityId()
        val post = reference("post", PostsImpl.PostsTable)
        val title = varchar("title", 255)
        val content = jsonb<JsonElement>("content", dataJson)
        val textContent = text("text_content")
        val time = timestamp("time").defaultExpression(CurrentTimestamp).index()
        val draft = bool("draft").default(false)
        override val primaryKey = PrimaryKey(id)
    }

    private val postVersionColumns = PostVersionTable.columns - PostVersionTable.textContent
    private val postVersionBasicColumns = postVersionColumns - PostVersionTable.content - PostVersionTable.textContent

    private fun deserializePostVersion(row: ResultRow): PostVersionInfo = PostVersionInfo(
        id = row[PostVersionTable.id].value,
        post = row[PostVersionTable.post].value,
        title = row[PostVersionTable.title],
        content = row[PostVersionTable.content],
        time = row[PostVersionTable.time].toEpochMilliseconds(),
        draft = row[PostVersionTable.draft],
    )

    private fun deserializePostVersionBasicInfo(row: ResultRow): PostVersionBasicInfo = PostVersionBasicInfo(
        id = row[PostVersionTable.id].value,
        post = row[PostVersionTable.post].value,
        title = row[PostVersionTable.title],
        time = row[PostVersionTable.time].toEpochMilliseconds(),
        draft = row[PostVersionTable.draft],
    )

    override suspend fun createPostVersion(
        post: PostId,
        title: String,
        content: JsonElement,
        draft: Boolean
    ): PostVersionId = query()
    {
        insertAndGetId {
            it[this.post] = post
            it[this.title] = title
            it[this.content] = content
            it[this.textContent] = getContentText(content)
            it[this.draft] = draft
        }.value
    }

    override suspend fun getPostVersion(pid: PostVersionId): PostVersionInfo? = query()
    {
        select(postVersionColumns).where { id eq pid }.singleOrNull()?.let(::deserializePostVersion)
    }

    override suspend fun getPostVersions(
        post: PostId,
        containsDraft: Boolean,
        begin: Long,
        count: Int
    ): Slice<PostVersionBasicInfo> = query()
    {
        select(postVersionBasicColumns)
            .andWhere { PostVersionTable.post eq post }
            .apply { if (!containsDraft) andWhere { draft eq false } }
            .orderBy(time, SortOrder.DESC)
            .asSlice(begin, count)
            .map { deserializePostVersionBasicInfo(it) }
    }

    override suspend fun getLatestPostVersion(post: PostId, containsDraft: Boolean): PostVersionId? = query()
    {
        select(id)
            .andWhere { PostVersionTable.post eq post }
            .apply { if (!containsDraft) andWhere { draft eq false } }
            .orderBy(time, SortOrder.DESC)
            .singleOrNull()
            ?.let { it[id].value }
    }
}