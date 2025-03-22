package subit.database

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
import subit.database.utils.asSlice
import subit.database.utils.singleOrNull
import subit.plugin.contentNegotiation.dataJson
import subit.utils.getContentText

class PostVersions: DaoSqlImpl<PostVersions.PostVersionTable>(PostVersionTable), KoinComponent
{
    object PostVersionTable: IdTable<PostVersionId>("post_versions")
    {
        override val id = postVersionId("id").autoIncrement().entityId()
        val post = reference("post", Posts.PostsTable)
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

    /**
     * 创建新的帖子版本, 时间为当前时间.
     * @param post 帖子ID
     * @param title 标题
     * @param content 内容
     * @return 帖子版本ID
     */
    suspend fun createPostVersion(
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

    /**
     * 获取帖子版本信息
     * @param pid 帖子版本ID
     * @return 帖子版本信息, 不存在返回null
     */
    suspend fun getPostVersion(pid: PostVersionId): PostVersionInfo? = query()
    {
        select(postVersionColumns).where { id eq pid }.singleOrNull()?.let(::deserializePostVersion)
    }

    /**
     * 获取帖子的所有版本, 按时间倒序排列, 即最新的版本在前.
     * @param post 帖子ID
     * @return 帖子版本ID列表
     */
    suspend fun getPostVersions(
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

    /**
     * 获取最新的帖子版本
     */
    suspend fun getLatestPostVersion(post: PostId, containsDraft: Boolean): PostVersionId? = query()
    {
        select(id)
            .andWhere { PostVersionTable.post eq post }
            .apply { if (!containsDraft) andWhere { draft eq false } }
            .orderBy(time, SortOrder.DESC)
            .singleOrNull()
            ?.let { it[id].value }
    }
}