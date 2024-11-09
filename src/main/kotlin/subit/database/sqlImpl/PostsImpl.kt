@file:Suppress("RemoveRedundantQualifierName")

package subit.database.sqlImpl

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Function
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.functions.math.PowerFunction
import org.jetbrains.exposed.sql.kotlin.datetime.*
import org.jetbrains.exposed.sql.statements.Statement
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.Slice
import subit.database.*
import subit.database.Posts.PostListSort.*
import subit.database.sqlImpl.PostVersionsImpl.PostVersionTable
import subit.database.sqlImpl.PostsImpl.PostsTable.view
import subit.database.sqlImpl.utils.asSlice
import subit.database.sqlImpl.utils.single
import subit.database.sqlImpl.utils.singleOrNull
import subit.database.sqlImpl.utils.withColumnType
import subit.utils.SUB_CONTENT_LENGTH
import subit.utils.toInstant
import java.sql.ResultSet
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 帖子数据库交互类
 */
class PostsImpl: DaoSqlImpl<PostsImpl.PostsTable>(PostsTable), Posts, KoinComponent
{
    private val blocks: Blocks by inject()
    private val likes: Likes by inject()
    private val stars: Stars by inject()
    private val permissions: Permissions by inject()
    private val postVersions: PostVersions by inject()
    private val tags: Tags by inject()

    object PostsTable: IdTable<PostId>("posts")
    {
        override val id = postId("id").autoIncrement().entityId()
        val author = reference("author", UsersImpl.UsersTable).index()
        val anonymous = bool("anonymous").default(false)
        val block = reference("block", BlocksImpl.BlocksTable).index()
        val view = long("view").default(0L)
        val state = enumerationByName<State>("state", 20).default(State.NORMAL)
        val top = bool("top").default(false)
        // 父帖子, 为null表示是根帖子
        val parent = reference("parent", this).nullable().index()
        // 根帖子, 为null表示是根帖子
        val rootPost = reference("rootPost", this).nullable().index()
        override val primaryKey = PrimaryKey(id)
    }

    /**
     * 创建时间, 为最早的版本的时间, 若没有版本为null
     *
     * 效果类似于:
     * ```sql
     * COALESCE(MIN(post_versions.time), '0001-01-01T00:00:00Z') AS create
     * ```
     */
    private val create = PostVersionTable.time.min().alias("createTime")

    /**
     * 最后修改时间, 为最新的版本的时间, 若没有版本为null
     *
     * 效果类似于:
     * ```sql
     * COALESCE(MAX(post_versions.time), '0001-01-01T00:00:00Z') AS lastModified
     * ```
     */
    private val lastModified = PostVersionTable.time.max().alias("lastModifiedTime")

    private val lastVersionId = PostVersionTable.id.max().alias("lastVersionId")

    /**
     * 一篇帖子的点赞数
     *
     * 效果类似于:
     * ```sql
     * COUNT(likes.id) AS like
     * ```
     */
    private val likeCount = coalesce(
        LikesImpl.LikesTable.post.count().alias("likeCount").aliasOnlyExpression().withColumnType(LongColumnType()),
        longParam(0)
    ).alias("likeCount1")
    private val rawLikeCount = LikesImpl.LikesTable.post.count().alias("likeCount")

    /**
     * 一篇帖子的收藏数
     *
     * 效果类似于:
     * ```sql
     * COUNT(stars.id) AS star
     * ```
     */
    private val starCount = coalesce(
        StarsImpl.StarsTable.post.count().alias("starCount").aliasOnlyExpression().withColumnType(LongColumnType()),
        longParam(0)
    ).alias("starCount1")
    private val rawStarCount = StarsImpl.StarsTable.post.count().alias("starCount")

    /**
     * 一篇帖子的评论数
     *
     * 效果类似于:
     * ```sql
     * COUNT(comments.id) AS comment
     * ```
     */
    private val commentCount = coalesce(
        PostsTable.alias("comments")[PostsTable.rootPost].count().alias("commentCount").aliasOnlyExpression().withColumnType(LongColumnType()),
        longParam(0)
    ).alias("commentCount1")
    private val rawCommentCount = PostsTable.alias("comments")[PostsTable.rootPost].count().alias("commentCount")

    /**
     * content的前[SUB_CONTENT_LENGTH]个字符(多保留几个字符, 用于确认是否需要省略号)
     */
    private val content100 = PostVersionTable.textContent.substring(0, 105).alias("content100")

    /**
     * 热度
     */
    private val hotScore by lazy {
        val x =
            (view +
             TimesOp(likeCount.delegate, longParam(3), LongColumnType()) +
             TimesOp(starCount.delegate, longParam(5), LongColumnType()) +
             TimesOp(commentCount.delegate, longParam(2), LongColumnType()) +
             1)

        class Epoch(val expression: Expression<Instant>): Function<Long>(LongColumnType())
        {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append("EXTRACT(EPOCH FROM (", expression, "))") }
        }

        val create = coalesce(create.aliasOnlyExpression().withColumnType(KotlinInstantColumnType()), timestampParam(0L.toInstant()))
        val second = (Epoch(CurrentTimestamp - create) + 1) / 60000
        @Suppress("UNCHECKED_CAST")
        val order = x / (PowerFunction(second, doubleParam(1.8)) as Expression<Long>)
        @Suppress("UNCHECKED_CAST")
        (order as Expression<Double>).alias("hotScore")
    }

    /**
     * 随机热度(即热度乘以一个随机数)
     */
    private val randomHotScore by lazy {
        CustomFunction("RANDOM", DoubleColumnType()) * hotScore.delegate
    }

    /**
     * 进行反序列化到data class, 考虑到此处有3种可能的反序列化目标([PostFullBasicInfo], [PostInfo], [PostFull]),
     * 分别定义函数不美观, 故使用inline函数进行统一处理, 通过泛型参数[T]进行区分.
     *
     * 无论类型都先转为[PostFull], 因为[PostFull]包含了所有可能的字段, 再根据类型进行转换.
     *
     * 注意不存在的字段不要去[row]中取, 否则会抛出异常.
     */
    private inline fun <reified T> deserializePost(row: ResultRow): T
    {
        val type = typeOf<T>()
        val postFullBasicInfoType = typeOf<PostFullBasicInfo>()
        val postInfoType = typeOf<PostInfo>()
        val postFullType = typeOf<PostFull>()
        val postFull = PostFull(
            id = row[PostsTable.id].value,
            title = if (type != postInfoType) row[PostVersionTable.title] else null,
            content = when (type)
            {
                postFullBasicInfoType -> JsonObject(mapOf("text" to JsonPrimitive(row[content100])))
                postFullType          -> row[PostVersionTable.content]
                else                  -> null
            },
            author = row[PostsTable.author].value,
            anonymous = row[PostsTable.anonymous],
            create = if (type != postInfoType) row[create.aliasOnlyExpression()]?.toEpochMilliseconds() else null,
            lastModified = if (type != postInfoType) row[lastModified.aliasOnlyExpression()]?.toEpochMilliseconds() else null,
            lastVersionId = if (type != postInfoType) row[lastVersionId.aliasOnlyExpression()]?.value else null,
            view = row[PostsTable.view],
            block = row[PostsTable.block].value,
            top = row[PostsTable.top],
            state = row[PostsTable.state],
            like = if (type != postInfoType) row[likeCount] else 0,
            star = if (type != postInfoType) row[starCount] else 0,
            comment = if (type != postInfoType) row[commentCount] else 0,
            parent = row[PostsTable.parent]?.value,
            root = row[PostsTable.rootPost]?.value,
            hotScore = if (type != postInfoType) row[hotScore] else 0.0
        )

        return when (type)
        {
            postFullBasicInfoType -> postFull.toPostFullBasicInfo() as T
            postInfoType          -> postFull.toPostInfo() as T
            postFullType          -> postFull as T
            else                  -> throw IllegalArgumentException("Unknown type $type")
        }
    }

    /**
     * [PostFullBasicInfo]中包含的列
     */
    private val postFullBasicInfoColumns = listOf(
        PostsTable.id,
        PostVersionTable.title,
        content100,
        PostsTable.author,
        PostsTable.anonymous,
        create.aliasOnlyExpression(),
        lastModified.aliasOnlyExpression(),
        lastVersionId.aliasOnlyExpression(),
        PostsTable.view,
        PostsTable.block,
        PostsTable.top,
        PostsTable.state,
        likeCount,
        starCount,
        commentCount,
        PostsTable.parent,
        PostsTable.rootPost,
        hotScore,
    )

    /**
     * [PostFull]中包含的列
     */
    private val postFullColumns = postFullBasicInfoColumns - content100 + PostVersionTable.content

    private fun Join.joinPostFull(containsDraft: Boolean): Join
    {
        val likesTable = (likes as LikesImpl).table
        val starsTable = (stars as StarsImpl).table
        val commentsTable = PostsTable.alias("comments")
        val postVersionsTable = (postVersions as PostVersionsImpl).table
        val tagsTable = (tags as TagsImpl).table

        return this
            .joinQuery(
                on = { (it[postVersionsTable.post] as Expression<*>) eq PostsTable.id },
                joinType = JoinType.LEFT,
                joinPart = {
                    val q = postVersionsTable
                        .select(postVersionsTable.post, lastVersionId, create, lastModified)
                    if (!containsDraft) q.where { postVersionsTable.draft eq false }
                    q.groupBy(postVersionsTable.post)
                    q
                }
            )
            .joinQuery(
                on = { (it[starsTable.post] as Expression<*>) eq PostsTable.id },
                joinType = JoinType.LEFT,
                joinPart = { starsTable.select(starsTable.post, rawStarCount).groupBy(starsTable.post) }
            )
            .joinQuery(
                on = { (it[likesTable.post] as Expression<*>) eq PostsTable.id },
                joinType = JoinType.LEFT,
                joinPart = { likesTable.select(likesTable.post, rawLikeCount).groupBy(likesTable.post) }
            )
            .joinQuery(
                on = { (it[commentsTable[PostsTable.rootPost]] as Expression<*>) eq PostsTable.id },
                joinType = JoinType.LEFT,
                joinPart = { commentsTable.select(commentsTable[PostsTable.rootPost], rawCommentCount).groupBy(commentsTable[PostsTable.rootPost]) }
            )
            .join(postVersionsTable, JoinType.LEFT, postVersionsTable.id, lastVersionId.aliasOnlyExpression())
            .join(tagsTable, JoinType.LEFT, PostsTable.id, tagsTable.post)
    }

    private fun Query.groupPostFull() = groupBy(
        *(
            postFullColumns
            + setOf(
                rawCommentCount.aliasOnlyExpression(),
                rawLikeCount.aliasOnlyExpression(),
                rawStarCount.aliasOnlyExpression(),
                TagsImpl.TagsTable.tag,
                PostVersionTable.textContent,
            )
            - setOf(
                hotScore,
                likeCount,
                starCount,
                commentCount,
            )
         ).toTypedArray()
    )

    private fun Table.joinPostFull(containsDraft: Boolean) = Join(this).joinPostFull(containsDraft)

    private val Posts.PostListSort.order: Array<Pair<Expression<*>, SortOrder>>
        get() = when (this)
        {
            NEW          -> arrayOf(create.aliasOnlyExpression() to SortOrder.DESC_NULLS_FIRST, PostsTable.id to SortOrder.DESC)
            OLD          -> arrayOf(create.aliasOnlyExpression() to SortOrder.ASC_NULLS_LAST, PostsTable.id to SortOrder.ASC)
            NEW_EDIT     -> arrayOf(lastModified.aliasOnlyExpression() to SortOrder.DESC_NULLS_FIRST, PostsTable.id to SortOrder.DESC)
            OLD_EDIT     -> arrayOf(lastModified.aliasOnlyExpression() to SortOrder.ASC_NULLS_LAST, PostsTable.id to SortOrder.ASC)
            MORE_VIEW    -> arrayOf(view to SortOrder.DESC)
            MORE_LIKE    -> arrayOf(likeCount.delegate to SortOrder.DESC)
            MORE_STAR    -> arrayOf(starCount.delegate to SortOrder.DESC)
            MORE_COMMENT -> arrayOf(commentCount.delegate to SortOrder.DESC)
            HOT          -> arrayOf(hotScore.delegate to SortOrder.DESC)
            RANDOM_HOT   -> arrayOf(randomHotScore to SortOrder.DESC)
        }

    override suspend fun createPost(
        author: UserId,
        anonymous: Boolean,
        block: BlockId,
        parent: PostId?,
        state: State,
        top: Boolean
    ): PostId? = query()
    {
        // 若存在父帖子, 则获取根帖子
        val root =
            if (parent != null)
            {
                val q = select(PostsTable.rootPost).where { PostsTable.id eq parent }.singleOrNull() ?: return@query null
                q[PostsTable.rootPost]?.value ?: parent
            }
            else null

        PostsTable.insertAndGetId {
            it[PostsTable.author] = author
            it[PostsTable.anonymous] = anonymous
            it[PostsTable.block] = block
            it[PostsTable.top] = top
            it[PostsTable.parent] = parent
            it[PostsTable.state] = state
            it[PostsTable.rootPost] = root
        }.value
    }


    /**
     * 连续的空白字符(包括空格和换行)的匹配正则表达式
     */
    private val whiteSpaceRegex = Regex("\\s+")

    override suspend fun isAncestor(parent: PostId, child: PostId): Boolean = query()
    {
        @Language("SQL")
        val query = """
            SELECT posts.id
            FROM posts
            INNER JOIN (
                WITH RECURSIVE Parent AS (
                    SELECT id, parent
                    FROM posts
                    WHERE id = $child
                    UNION ALL
                    SELECT t.id, t.parent
                    FROM posts t
                    INNER JOIN Parent p ON t.id = p.parent)
                    SELECT *
                    FROM Parent
            ) AS Parent ON posts.id = Parent.id
            WHERE posts.id = $parent
        """.trimIndent().replace(whiteSpaceRegex, " ")
        it.exec(query) { res -> res.getLong("id") } == parent.value
    }

    /**
     * 一个查询, 可以获得以[rootId]为根的子树内的所有帖子的id, 也会包括[rootId]自己
     */
    private inner class GetDescendantIdsQuery(
        val rootId: PostId
    ): Query(
        org.jetbrains.exposed.sql.Slice(PostsTable, listOf(PostsTable.id)),
        null
    )
    {
        @Language("SQL")
        val sql = """
            WITH RECURSIVE SubTree AS (
                SELECT id, parent
                FROM posts
                WHERE id = ${rootId.value}
                UNION ALL
                SELECT n.id, n.parent
                FROM posts n
                INNER JOIN SubTree subTree ON n.parent = subTree.id
            )
            SELECT SubTree.id AS id
            FROM SubTree
        """.trimIndent().replace(whiteSpaceRegex, " ")

        override val queryToExecute: Statement<ResultSet>
            get() = this

        override fun prepareSQL(builder: QueryBuilder): String
        {
            builder.append(sql)
            return builder.toString()
        }
    }

    override suspend fun getDescendants(
        pid: PostId,
        sortBy: Posts.PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFull> = query()
    {
        val descendantIds = GetDescendantIdsQuery(pid).alias("descendantIds")

        table
            .joinPostFull(false)
            .join(descendantIds, JoinType.INNER, PostsTable.id, descendantIds[PostsTable.id])
            .select(postFullColumns)
            .andWhere { PostsTable.id neq pid }
            .andWhere { PostsTable.state eq State.NORMAL }
            .groupPostFull()
            .orderBy(*sortBy.order)
            .asSlice(begin, count)
            .map { deserializePost<PostFull>(it) }
    }

    override suspend fun getChildPosts(
        pid: PostId,
        sortBy: Posts.PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFull> = query()
    {
        table
            .joinPostFull(false)
            .select(postFullColumns)
            .andWhere { PostsTable.parent eq pid }
            .andWhere { PostsTable.state eq State.NORMAL }
            .groupPostFull()
            .orderBy(*sortBy.order)
            .asSlice(begin, count)
            .map { deserializePost<PostFull>(it) }
    }

    override suspend fun setTop(pid: PostId, top: Boolean) = query()
    {
        update({ id eq pid }) { it[PostsTable.top] = top } > 0
    }

    override suspend fun setPostState(pid: PostId, state: State): Unit = query()
    {
        update({ id eq pid }) { it[PostsTable.state] = state }
    }

    override suspend fun getPostInfo(pid: PostId): PostInfo? = query()
    {
        selectAll().where { id eq pid }.firstOrNull()?.let { deserializePost<PostInfo>(it) }
    }

    override suspend fun getPostFull(pid: PostId): PostFull? = query()
    {
        table
            .joinPostFull(false)
            .select(postFullColumns)
            .where { PostsTable.id eq pid }
            .groupPostFull()
            .singleOrNull()
            ?.let { deserializePost<PostFull>(it) }
    }

    override suspend fun getPostFullBasicInfo(pid: PostId): PostFullBasicInfo? = query()
    {
        table
            .joinPostFull(false)
            .select(postFullBasicInfoColumns)
            .where { PostsTable.id eq pid }
            .groupPostFull()
            .singleOrNull()
            ?.let { deserializePost<PostFullBasicInfo>(it) }
    }

    /**
     * 获取帖子列表
     */
    override suspend fun getPosts(
        loginUser: DatabaseUser?,
        author: UserId?,
        block: BlockId?,
        top: Boolean?,
        state: State?,
        tag: String?,
        comment: Boolean?,
        draft: Boolean?,
        createBefore: Instant?,
        createAfter: Instant?,
        lastModifiedBefore: Instant?,
        lastModifiedAfter: Instant?,
        containsKeyWord: String?,
        sortBy: Posts.PostListSort,
        begin: Long,
        limit: Int
    ): Slice<PostFullBasicInfo> = query()
    {
        // 构建查询，联结 PostsTable, BlocksTable 和 PermissionsTable
        val permissionTable = (permissions as PermissionsImpl).table
        val blockTable = (blocks as BlocksImpl).table

        // 如果对时间有要求就无法限制是不是草稿
        @Suppress("NAME_SHADOWING")
        val draft =
            if (createBefore != null || createAfter != null || lastModifiedBefore != null || lastModifiedAfter != null) false
            else draft

        fun Query.checkLimits(): Query
        {
            val postVersionTable = (postVersions as PostVersionsImpl).table

            if (author != null) andWhere { table.author eq author }
            if (block != null) andWhere { table.block eq block }
            if (top != null) andWhere { table.top eq top }
            if (state != null) andWhere { table.state eq state }
            if (comment != null) andWhere { if (comment) table.parent.isNotNull() else table.parent.isNull() }
            if (draft != null)
            {
                if (draft) andWhere { lastVersionId.aliasOnlyExpression().isNull() or postVersionTable.draft }
                else andWhere { lastVersionId.aliasOnlyExpression().isNotNull() }
            }
            if (createBefore != null) andWhere { create.aliasOnlyExpression() lessEq timestampParam(createBefore) }
            if (createAfter != null) andWhere { create.aliasOnlyExpression() greaterEq timestampParam(createAfter) }
            if (lastModifiedBefore != null) andWhere { lastModified.aliasOnlyExpression() lessEq timestampParam(lastModifiedBefore) }
            if (lastModifiedAfter != null) andWhere { lastModified.aliasOnlyExpression() greaterEq timestampParam(lastModifiedAfter) }
            if (!containsKeyWord.isNullOrBlank()) andWhere { (PostVersionTable.textContent like "%$containsKeyWord%") or (PostVersionTable.title like "%$containsKeyWord%") }
            if (!tag.isNullOrBlank()) andHaving { TagsImpl.TagsTable.tag eq tag }
            return this
        }

        fun Query.checkPermission(): Query
        {
            // 如果是全局管理员就不需要权限限制
            if (loginUser.hasGlobalAdmin()) return this
            // 对于板块的权限限制
            andHaving { (permissionTable.permission.max() greaterEq blockTable.reading).or(blockTable.reading lessEq PermissionLevel.NORMAL) }

            // 帖子状态限制: 只能看到正常状态的帖子和自己的私密帖子
            andWhere { (table.state eq State.NORMAL) or ((table.state eq State.PRIVATE) and (table.author eq loginUser?.id)) }
            // 板块状态限制: 只能看到正常状态的板块
            andWhere { blockTable.state eq State.NORMAL }
            return this
        }


        PostsTable
            .joinPostFull(draft == true)
            .join(blockTable, JoinType.INNER, table.block, blockTable.id)
            .join(permissionTable, JoinType.LEFT, table.block, permissionTable.block) { permissionTable.user eq loginUser?.id }
            .select(postFullBasicInfoColumns)
            .checkLimits()
            .groupBy(id, create, blockTable.id, blockTable.reading)
            .groupPostFull()
            .checkPermission()
            .orderBy(*sortBy.order)
            .asSlice(begin, limit)
            .map { deserializePost<PostFullBasicInfo>(it) }
            .let {
                if (draft == true) it.map { p -> p.copy(create = null) }
                else it
            }
    }

    override suspend fun addView(pid: PostId): Unit = query()
    {
        PostsTable.update({ id eq pid }) { it[view] = view + 1 }
    }

    override suspend fun monthly(loginUser: DatabaseUser?, begin: Long, count: Int): Slice<PostFullBasicInfo> = query()
    {
        // 构建查询，联结 PostsTable, BlocksTable 和 PermissionsTable
        val permissionTable = (permissions as PermissionsImpl).table
        val blockTable = (blocks as BlocksImpl).table
        val likeTable = (likes as LikesImpl).table

        fun Query.checkPermission(): Query
        {
            // 如果是全局管理员就不需要权限限制
            if (loginUser.hasGlobalAdmin()) return this
            // 对于板块的权限限制
            andHaving { (permissionTable.permission.max() greaterEq blockTable.reading).or(blockTable.reading lessEq PermissionLevel.NORMAL) }

            // 帖子状态限制: 只能看到正常状态的帖子和自己的私密帖子
            andWhere { (table.state eq State.NORMAL) or ((table.state eq State.PRIVATE) and (table.author eq loginUser?.id)) }
            // 板块状态限制: 只能看到正常状态的板块
            andWhere { blockTable.state eq State.NORMAL }
            return this
        }

        val aMonthAgo = Clock.System.now() - 30.days

        PostsTable
            .joinPostFull(false)
            .join(blockTable, JoinType.INNER, table.block, blockTable.id)
            .join(permissionTable, JoinType.LEFT, table.block, permissionTable.block) { permissionTable.user eq loginUser?.id }
            .join(likeTable, JoinType.LEFT, table.id, likeTable.post) { likeTable.time greaterEq aMonthAgo }
            .select(postFullBasicInfoColumns)
            .checkPermission()
            .andWhere { parent.isNull() }
            .andWhere { table.state eq State.NORMAL }
            .andWhere { lastVersionId.aliasOnlyExpression().isNotNull() }
            .groupBy(id, create, blockTable.id, blockTable.reading)
            .groupPostFull()
            .orderBy(likeTable.post.count() to SortOrder.DESC)
            .asSlice(begin, count)
            .map { deserializePost<PostFullBasicInfo>(it) }
    }

    override suspend fun totalPostCount(comment: Boolean, duration: Duration?): Map<State, Long> = query()
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        val res = table
            .joinPostFull(false)
            .select(state, id.count())
            .andWhere { if (comment) parent.isNotNull() else parent.isNull() }
            .andWhere { lastModified.aliasOnlyExpression() greaterEq timestampParam(time) }
            .groupBy(state)
            .associate { it[state] to it[id.count()] }
        State.entries.associateWith { (res[it] ?: 0) }
    }

    override suspend fun totalReadCount(): Long = query()
    {
        table.select(view.sum()).single()[view.sum()] ?: 0
    }
}