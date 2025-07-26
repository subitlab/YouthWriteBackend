@file:Suppress("RemoveRedundantQualifierName")

package subit.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Function
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.functions.math.PowerFunction
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestampParam
import org.jetbrains.exposed.sql.statements.Statement
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.Slice
import subit.database.PostVersions.PostVersionTable
import subit.database.Posts.PostListSort.*
import subit.database.utils.asSlice
import subit.database.utils.single
import subit.database.utils.singleOrNull
import subit.logger.YouthWriteLogger
import subit.router.utils.PermissionGroup
import subit.router.utils.permissionGroup
import subit.utils.SUB_CONTENT_LENGTH
import subit.utils.toInstant
import java.sql.ResultSet
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 帖子数据库交互类
 */
class Posts: DaoSqlImpl<Posts.PostTable>(PostTable), KoinComponent
{
    private val blocks: Blocks by inject()
    private val likes: Likes by inject()
    private val permissions: Permissions by inject()
    private val postVersions: PostVersions by inject()
    private val tags: Tags by inject()

    @Serializable
    enum class PostListSort
    {
        /**
         * 按照创建时间从新到旧排序
         */
        NEW,
        /**
         * 按照创建时间从旧到新排序
         */
        OLD,
        /**
         * 按最新编辑时间从新到旧排序
         */
        NEW_EDIT,
        /**
         * 按最新编辑时间从旧到新排序
         */
        OLD_EDIT,
        /**
         * 按照浏览量从高到低排序
         */
        MORE_VIEW,
        /**
         * 按照点赞数从高到低排序
         */
        MORE_LIKE,
        /**
         * 按照收藏数从高到低排序
         */
        MORE_STAR,
        /**
         * 按照评论数从高到低排序
         */
        MORE_COMMENT,
        /**
         * 按照热度排序
         */
        HOT,
        /**
         * 按照热度并带一定随机性排序
         */
        RANDOM_HOT,
    }

    object PostTable: IdTable<PostId>("posts")
    {
        override val id = postId("id").autoIncrement().entityId()
        val author = reference("author", Users.UsersTable).index()
        val anonymous = bool("anonymous").default(false)
        val block = reference("block", Blocks.BlocksTable).index()
        val view = long("view").default(0L)
        val state = enumerationByName<State>("state", 20).index().default(State.NORMAL)
        val top = bool("top").index().default(false)
        // 父帖子, 为null表示是根帖子
        val parent = reference("parent", this).nullable().index()
        // 根帖子, 为null表示是根帖子
        val rootPost = reference("rootPost", this).nullable().index()

        val commentCount = long("commentCount").default(0L).index()
        val lastVersion = reference("lastVersion", PostVersionTable).nullable().index()
        val lastDraftVersion = reference("lastDraftVersion", PostVersionTable).nullable().index()
        val starCount = long("starCount").default(0L).index()
        val likeCount = long("likeCount").default(0L).index()
        val create = timestamp("create").nullable().default(null).index()
        val secret = varchar("secret", 255).default("")
        override val primaryKey = PrimaryKey(id)
    }

    init
    {
        CommentCountTriggerManager.setupTriggers(database)
    }

    private val lastModified = PostVersionTable.time

    /////////////////// start ////////////////////
    ////////////// basic components //////////////
    //////////////////////////////////////////////
    /**
     * content的前[SUB_CONTENT_LENGTH]个字符(多保留几个字符, 用于确认是否需要省略号)
     */
    private val content100 = PostVersionTable.textContent.substring(0, 105).alias("content100")

    /**
     * 热度
     */
    private val hotScore by lazy {
        val x =
            (table.view +
             TimesOp(table.likeCount, longParam(3), LongColumnType()) +
             TimesOp(table.starCount, longParam(5), LongColumnType()) +
             TimesOp(table.commentCount, longParam(2), LongColumnType()) +
             1)

        class Epoch(val expression: Expression<Instant>): Function<Long>(LongColumnType())
        {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append("EXTRACT(EPOCH FROM (", expression, "))") }
        }
        val second = (Epoch(CurrentTimestamp - coalesce(table.create, timestampParam(0L.toInstant()))) + 1) / 60000
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
            id = row[table.id].value,
            title = if (type != postInfoType) row[PostVersionTable.title] else null,
            content = when (type)
            {
                postFullBasicInfoType -> JsonObject(mapOf("text" to JsonPrimitive(row[content100])))
                postFullType          -> row[PostVersionTable.content]
                else                  -> null
            },
            author = row[table.author].value,
            anonymous = row[table.anonymous],
            create = if (type != postInfoType) row[table.create]?.toEpochMilliseconds() else null,
            lastModified = if (type != postInfoType) row.getOrNull(lastModified)?.toEpochMilliseconds() else null,
            lastVersionId = if (type != postInfoType) row.getOrNull(PostVersionTable.id)?.value else null,
            view = row[table.view],
            block = row[table.block].value,
            top = row[table.top],
            state = row[table.state],
            like = if (type != postInfoType) row[table.likeCount] else 0,
            star = if (type != postInfoType) row[table.starCount] else 0,
            comment = if (type != postInfoType) row[table.commentCount] else 0,
            parent = row[table.parent]?.value,
            root = row[table.rootPost]?.value,
            hotScore = if (type != postInfoType) row[hotScore] else 0.0,
            private = row[table.secret] != "",
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
        table.id,
        PostVersionTable.title,
        content100,
        table.author,
        table.anonymous,
        table.view,
        table.block,
        table.top,
        table.state,
        table.parent,
        table.rootPost,
        table.create,
        table.commentCount,
        table.lastVersion,
        table.lastDraftVersion,
        table.starCount,
        table.likeCount,
        table.secret,
        lastModified,
        postVersions.table.id,
        hotScore,
    )

    /**
     * [PostFull]中包含的列
     */
    private val postFullColumns = postFullBasicInfoColumns - content100 + PostVersionTable.content

    private fun Join.joinPostFull(
        containsDraft: Boolean,
        joinTags: Boolean = false,
    ): Join
    {
        val postVersionsTable = postVersions.table
        val tagsTable = tags.table

        var j = this
            .join(postVersionsTable, JoinType.LEFT, postVersionsTable.id, if (containsDraft) this@Posts.table.lastDraftVersion else this@Posts.table.lastVersion )
        if (joinTags)
            j = j.join(tagsTable, JoinType.LEFT, this@Posts.table.id, tagsTable.post)
        return j
    }

    /**
     * @see joinPostFull
     */
    private fun Table.joinPostFull(
        containsDraft: Boolean,
        joinTags: Boolean = false,
    ) = Join(this).joinPostFull(containsDraft, joinTags)

    /**
     * 对已经[joinPostFull]后的查询进行group
     */
    private fun Query.groupPostFull(joinTags: Boolean = false): Query
    {
        val list = (
            postFullColumns
            + PostVersionTable.textContent
            - hotScore
        ).toMutableList()
        if (joinTags) list += Tags.TagsTable.tag
        return groupBy(*list.toTypedArray())
    }

    /**
     * join板块表和权限表, 以获取权限信息, 从而检查是否有权限查看帖子
     *
     * 注意:
     * 若loginUser一定有权所有帖子或一定无权阅读所有帖子, 该函数都不会进行join操作.
     * 若loginUser为null则不会join权限表, 但会join板块表.
     * [havingPermission]也会采取对应操作以提升性能
     * @see havingPermission 用于对已经joinPermission后的查询进行having, 判断是否有权限查看帖子
     */
    private suspend fun Join?.joinPermission(permissionGroup: PermissionGroup): Join?
    {
        if (this == null) return null
        if (permissionGroup.hasGlobalAdmin) return this
        if (permissionGroup.isProhibit()) return null

        val blockTable = blocks.table
        val permissionTable = permissions.table
        var j = this.join(blockTable, JoinType.INNER, this@Posts.table.block, blockTable.id)
        j = j.join(this@Posts.table.alias("rootPost"), JoinType.LEFT, this@Posts.table.rootPost, this@Posts.table.id)
        if (permissionGroup.user != null)
        {
            j = j.join(permissionTable, JoinType.LEFT, permissionTable.block, blockTable.id)
        }
        return j
    }

    /**
     * 对已经[joinPermission]后的查询进行having, 判断是否有权限查看帖子
     * @see joinPermission 用于join板块表和权限表, 以获取权限信息, 从而检查是否有权限查看帖子
     */
    private suspend fun Query?.havingPermission(permissionGroup: PermissionGroup): Query?
    {
        if (this == null) return null
        // 如果是全局管理员就不需要权限限制
        if (permissionGroup.hasGlobalAdmin) return this
        // 如果该用户被封禁则不允许查看帖子
        if (permissionGroup.isProhibit()) return null

        val blockTable = blocks.table
        val permissionTable = permissions.table

        groupBy(table.id, blockTable.id, blockTable.reading)

        // 对于板块的权限限制
        if (permissionGroup.user != null)
            andHaving { coalesce(permissionTable.permission.max(), QueryParameter(PermissionLevel.NORMAL, EnumerationColumnType(PermissionLevel::class))) greaterEq blockTable.reading }
        else
            andWhere { blockTable.reading lessEq PermissionLevel.NORMAL }

        // 帖子状态限制: 只能看到正常状态的帖子自己的帖子/被授权的帖子
        andWhere { ((table.state eq State.NORMAL) and (table.secret eq "")) or (table.author eq permissionGroup.user) }
        // 帖子状态限制: 如果有根帖子, 则根帖子也必须是正常状态或自己的帖子
        andWhere { table.rootPost.isNull() or (((table.alias("rootPost")[table.state] eq State.NORMAL) and (table.alias("rootPost")[table.secret] eq "")) or (table.alias("rootPost")[table.author] eq permissionGroup.user)) }
        // 板块状态限制: 只能看到正常状态的板块
        andWhere { blockTable.state eq State.NORMAL }
        return this
    }

    private val Posts.PostListSort.order: Array<Pair<Expression<*>, SortOrder>>
        get() = when (this)
        {
            NEW          -> arrayOf(table.create to SortOrder.DESC_NULLS_FIRST, table.id to SortOrder.DESC)
            OLD          -> arrayOf(table.create to SortOrder.ASC_NULLS_LAST, table.id to SortOrder.ASC)
            NEW_EDIT     -> arrayOf(lastModified to SortOrder.DESC_NULLS_FIRST, table.id to SortOrder.DESC)
            OLD_EDIT     -> arrayOf(lastModified to SortOrder.ASC_NULLS_LAST, table.id to SortOrder.ASC)
            MORE_VIEW    -> arrayOf(table.view to SortOrder.DESC)
            MORE_LIKE    -> arrayOf(table.likeCount to SortOrder.DESC)
            MORE_STAR    -> arrayOf(table.starCount to SortOrder.DESC)
            MORE_COMMENT -> arrayOf(table.commentCount to SortOrder.DESC)
            HOT          -> arrayOf(hotScore.delegate to SortOrder.DESC)
            RANDOM_HOT   -> arrayOf(randomHotScore to SortOrder.DESC)
        }

    /////////////////// start ////////////////////
    ////////// interface implementation //////////
    //////////////////////////////////////////////

    /**
     * 创建新的帖子
     * @param author 作者
     * @param anonymous 是否匿名
     * @param block 所属板块
     * @param parent 父帖子, 为null表示没有父帖子
     * @param top 是否置顶
     * @return 帖子ID, 当父帖子不为null时, 返回null表示父帖子不存在
     */
    suspend fun createPost(
        author: UserId,
        anonymous: Boolean,
        block: BlockId,
        parent: PostId?,
        state: State,
        top: Boolean = false,
    ): PostId? = query()
    {
        // 若存在父帖子, 则获取根帖子
        val root =
            if (parent != null)
            {
                val q = select(table.rootPost).where { table.id eq parent }.singleOrNull() ?: return@query null
                q[table.rootPost]?.value ?: parent
            }
            else null

        table.insertAndGetId {
            it[table.author] = author
            it[table.anonymous] = anonymous
            it[table.block] = block
            it[table.top] = top
            it[table.parent] = parent
            it[table.state] = state
            it[table.rootPost] = root
        }.value
    }


    /**
     * 连续的空白字符(包括空格和换行)的匹配正则表达式
     */
    private val whiteSpaceRegex = Regex("\\s+")

    suspend fun isAncestor(parent: PostId, child: PostId): Boolean = query()
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
        org.jetbrains.exposed.sql.Slice(table, listOf(table.id)),
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

    suspend fun setTop(pid: PostId, top: Boolean) = query()
    {
        update({ id eq pid }) { it[table.top] = top } > 0
    }

    suspend fun setPostState(pid: PostId, state: State): Unit = query()
    {
        update({ id eq pid }) { it[table.state] = state }
    }

    suspend fun getPostInfo(pid: PostId): PostInfo? = query()
    {
        selectAll().where { id eq pid }.firstOrNull()?.let { deserializePost<PostInfo>(it) }
    }

    suspend fun getPostFull(pid: PostId): PostFull? = query()
    {
        table
            .joinPostFull(false)
            .select(postFullColumns)
            .where { table.id eq pid }
            .groupPostFull()
            .singleOrNull()
            ?.let { deserializePost<PostFull>(it) }
    }

    suspend fun getPostFullBasicInfo(pid: PostId): PostFullBasicInfo? = query()
    {
        table
            .joinPostFull(false)
            .select(postFullBasicInfoColumns)
            .where { table.id eq pid }
            .groupPostFull()
            .singleOrNull()
            ?.let { deserializePost<PostFullBasicInfo>(it) }
    }

    /**
     * 获得帖子列表
     * @param loginUser 当前操作用户, null表示未登录, 返回的帖子应是该用户可见的.
     * @param author 作者, null表示所有作者
     * @param block 板块, null表示所有板块
     * @param top 是否置顶, null表示所有
     * @param state 帖子状态, null表示所有状态
     * @param tag 标签, 过滤带有该标签的帖子, null表示所有
     * @param comment 是否是评论, null表示所有
     * @param draft 是否是草稿, null表示所有
     * @param childOf 父帖子, null表示所有
     * @param descendantOf 祖先帖子, null表示所有
     * @param createBefore 创建时间在该时间之前, null表示不限制
     * @param createAfter 创建时间在该时间之后, null表示不限制
     * @param lastModifiedBefore 最后编辑时间在该时间之前, null表示不限制
     * @param lastModifiedAfter 最后编辑时间在该时间之后, null表示不限制
     * @param containsKeyWord 包含关键字, null表示不限制
     * @param sortBy 排序方式
     * @param begin 起始位置
     * @param limit 限制数量
     * @param full 是否返回完整信息, 若为false返回[PostFullBasicInfo], 若为true返回[PostFull]
     */
    suspend fun getPosts(
        loginUser: UserFull? = null,
        author: UserId? = null,
        block: BlockId? = null,
        top: Boolean? = null,
        state: State? = null,
        tag: String? = null,
        comment: Boolean? = null,
        draft: Boolean? = null,
        childOf: PostId? = null,
        descendantOf: PostId? = null,
        createBefore: Instant? = null,
        createAfter: Instant? = null,
        lastModifiedBefore: Instant? = null,
        lastModifiedAfter: Instant? = null,
        containsKeyWord: String? = null,
        sortBy: subit.database.Posts.PostListSort,
        begin: Long,
        limit: Int,
        full: Boolean = false,
    ): Slice<IPostFull<*, *>> = query()
    {
        // 如果对时间有要求就无法限制是不是草稿
        @Suppress("NAME_SHADOWING")
        val draft =
            if (createBefore != null || createAfter != null || lastModifiedBefore != null || lastModifiedAfter != null) false
            else draft
        val descendantIds = descendantOf?.let { GetDescendantIdsQuery(it).alias("descendantIds") }

        fun Query.checkLimits(): Query
        {
            if (author != null) andWhere { table.author eq author }
            if (block != null) andWhere { table.block eq block }
            if (top != null) andWhere { table.top eq top }
            if (state != null) andWhere { table.state eq state }
            if (comment != null) andWhere { if (comment) table.parent.isNotNull() else table.parent.isNull() }
            if (draft != null)
            {
                if (draft) andWhere { lastVersion.isNull() or (lastDraftVersion.isNotNull() and (lastVersion less lastDraftVersion)) }
                else andWhere { lastVersion.isNotNull() and (lastDraftVersion.isNull() or (lastVersion greater lastDraftVersion)) }
            }
            if (childOf != null) andWhere { table.parent eq childOf }
            if (descendantOf != null) andWhere { table.id neq descendantOf }
            if (createBefore != null) andWhere { create lessEq timestampParam(createBefore) }
            if (createAfter != null) andWhere { create greaterEq timestampParam(createAfter) }
            if (lastModifiedBefore != null) andWhere { lastModified lessEq timestampParam(lastModifiedBefore) }
            if (lastModifiedAfter != null) andWhere { lastModified greaterEq timestampParam(lastModifiedAfter) }
            if (!containsKeyWord.isNullOrBlank()) andWhere { (PostVersionTable.textContent like "%$containsKeyWord%") or (PostVersionTable.title like "%$containsKeyWord%") }
            if (!tag.isNullOrBlank()) andHaving { Tags.TagsTable.tag eq tag }
            return this
        }

        val permissionGroup = loginUser.permissionGroup()

        val res = table
            .joinPostFull(draft == true, !tag.isNullOrBlank())
            .joinPermission(permissionGroup)
            ?.let { if (descendantIds != null) it.join(descendantIds, JoinType.INNER, table.id, descendantIds[table.id]) else it }
            ?.let { if (full) it.select(postFullColumns) else it.select(postFullBasicInfoColumns) }
            ?.checkLimits()
            ?.groupPostFull(!tag.isNullOrBlank())
            ?.havingPermission(permissionGroup)
            ?.orderBy(*sortBy.order)
            ?.asSlice(begin, limit)
            ?: Slice.empty()

        if (full)
        {
            res.map { deserializePost<PostFull>(it) }
                .let {
                    if (draft == true) it.map { p -> p.copy(create = null) }
                    else it
                }
        }
        else
        {
            res.map { deserializePost<PostFullBasicInfo>(it) }.let {
                if (draft == true) it.map { p -> p.copy(create = null) }
                else it
            }
        }
    }

    suspend fun getPostsAdvanced(
        loginUser: UserFull? = null,
        author: List<UserId>? = null,
        block: List<BlockId>? = null,
        top: Boolean? = null,
        state: List<State>? = null,
        tag: List<String>? = null,
        comment: Boolean? = null,
        draft: Boolean? = null,
        childOf: List<PostId>? = null,
        descendantOf: PostId? = null,
        createBefore: Instant? = null,
        createAfter: Instant? = null,
        lastModifiedBefore: Instant? = null,
        lastModifiedAfter: Instant? = null,
        containsKeyWord: List<String>? = null,
        sortBy: subit.database.Posts.PostListSort,
        begin: Long,
        limit: Int,
        full: Boolean = false,
    ): Slice<IPostFull<*, *>> = query()
    {
        // 如果对时间有要求就无法限制是不是草稿
        @Suppress("NAME_SHADOWING")
        val draft =
            if (createBefore != null || createAfter != null || lastModifiedBefore != null || lastModifiedAfter != null) false
            else draft
        val descendantIds = descendantOf?.let { GetDescendantIdsQuery(it).alias("descendantIds") }

        fun Query.checkLimits(): Query
        {
            if (author != null) andWhere { table.author inList author }
            if (block != null) andWhere { table.block inList block }
            if (top != null) andWhere { table.top eq top }
            if (state != null) andWhere { table.state inList state }
            if (comment != null) andWhere { if (comment) table.parent.isNotNull() else table.parent.isNull() }
            if (draft != null)
            {
                if (draft) andWhere { lastVersion.isNull() or (lastDraftVersion.isNotNull() and (lastVersion less lastDraftVersion)) }
                else andWhere { lastVersion.isNotNull() and (lastDraftVersion.isNull() or (lastVersion greater lastDraftVersion)) }
            }
            if (childOf != null) andWhere { table.parent inList childOf }
            if (descendantOf != null) andWhere { table.id neq descendantOf }
            if (createBefore != null) andWhere { create lessEq timestampParam(createBefore) }
            if (createAfter != null) andWhere { create greaterEq timestampParam(createAfter) }
            if (lastModifiedBefore != null) andWhere { lastModified lessEq timestampParam(lastModifiedBefore) }
            if (lastModifiedAfter != null) andWhere { lastModified greaterEq timestampParam(lastModifiedAfter) }
            if (!containsKeyWord.isNullOrEmpty()) andWhere { containsKeyWord.map { keyword ->
                (PostVersionTable.textContent like "%$keyword%") or (PostVersionTable.title like "%$keyword%")
            }.reduce { acc, condition -> acc or condition } }
            if (!tag.isNullOrEmpty()) andHaving { Tags.TagsTable.tag inList tag }
            return this
        }

        val permissionGroup = loginUser.permissionGroup()

        val res = table
            .joinPostFull(draft == true, !tag.isNullOrEmpty())
            .joinPermission(permissionGroup)
            ?.let { if (descendantIds != null) it.join(descendantIds, JoinType.INNER, table.id, descendantIds[table.id]) else it }
            ?.let { if (full) it.select(postFullColumns) else it.select(postFullBasicInfoColumns) }
            ?.checkLimits()
            ?.groupPostFull(!tag.isNullOrEmpty())
            ?.havingPermission(permissionGroup)
            ?.orderBy(*sortBy.order)
            ?.asSlice(begin, limit)
            ?: Slice.empty()

        if (full)
        {
            res.map { deserializePost<PostFull>(it) }
                .let {
                    if (draft == true) it.map { p -> p.copy(create = null) }
                    else it
                }
        }
        else
        {
            res.map { deserializePost<PostFullBasicInfo>(it) }.let {
                if (draft == true) it.map { p -> p.copy(create = null) }
                else it
            }
        }
    }

    /**
     * 获得若干帖子的基本信息, 用于展示列表
     * @param loginUser 当前操作用户, null表示未登录, 返回的帖子应是该用户可见的.
     * @param posts 帖子ID列表
     */
    suspend fun mapToPostFullBasicInfo(
        loginUser: UserFull? = null,
        posts: List<PostId?>,
    ): List<PostFullBasicInfo?> = query()
    {
        val permissionGroup = loginUser.permissionGroup()
        val res = table
            .joinPostFull(false)
            .joinPermission(permissionGroup)
            ?.select(postFullBasicInfoColumns)
            ?.andWhere { table.id inList posts.filterNotNull() }
            ?.groupPostFull()
            ?.havingPermission(permissionGroup)
            ?.map { deserializePost<PostFullBasicInfo>(it) }
            ?.associateBy(PostFullBasicInfo::id)
        posts.map((res ?: emptyMap())::get)
    }

    suspend fun addView(pid: PostId): Unit = query()
    {
        table.update({ id eq pid }) { it[view] = view + 1 }
    }

    /**
     * 获得***最近一个月***点赞数最多的帖子
     */
    suspend fun monthly(
        loginUser: UserFull?,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo> = query()
    {
        val likeTable = likes.table

        val permissionGroup = loginUser.permissionGroup()
        val aMonthAgo = Clock.System.now() - 30.days

        table
            .joinPostFull(false)
            .joinPermission(permissionGroup)
            ?.join(likeTable, JoinType.LEFT, table.id, likeTable.post) { likeTable.time greaterEq aMonthAgo }
            ?.select(postFullBasicInfoColumns)
            ?.havingPermission(permissionGroup)
            ?.andWhere { parent.isNull() }
            ?.andWhere { table.state eq State.NORMAL }
            ?.andWhere { table.lastVersion.isNotNull() }
            ?.groupPostFull()
            ?.orderBy(likeTable.post.count() to SortOrder.DESC)
            ?.asSlice(begin, count)
            ?.map { deserializePost<PostFullBasicInfo>(it) }
        ?: Slice.empty()
    }

    suspend fun totalPostCount(comment: Boolean, duration: Duration?): Map<State, Long> = query()
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        val res = table
            .joinPostFull(false)
            .select(state, id.count())
            .groupPostFull()
            .andWhere { if (comment) parent.isNotNull() else parent.isNull() }
            .andWhere { lastModified greaterEq timestampParam(time) }
            .groupBy(state)
            .associate { it[state] to it[id.count()] }
        State.entries.associateWith { (res[it] ?: 0) }
    }

    suspend fun totalReadCount(): Long = query()
    {
        table.select(view.sum()).single()[view.sum()] ?: 0
    }

    suspend fun bindNewAccount(oldAuthor: UserId, newAuthor: UserId): Unit = query()
    {
        table.update({ table.author eq oldAuthor }) { it[table.author] = newAuthor }
    }

    suspend fun getAuthorAndSecret(pid: PostId): Pair<UserId,String>? = query()
    {
        table
            .select(table.secret, table.author)
            .where { table.id eq pid }
            .singleOrNull()
            ?.let { it[table.author].value to it[table.secret] }
    }

    suspend fun setSecret(pid: PostId, secret: String): Boolean = query()
    {
        table.update({ table.id eq pid }) { it[table.secret] = secret } > 0
    }
}

object CommentCountTriggerManager
{
    private val logger = YouthWriteLogger.getLogger<CommentCountTriggerManager>()

    private const val INSERT_FUNCTION = "update_comment_count_insert"
    private const val DELETE_FUNCTION = "update_comment_count_delete"
    private const val UPDATE_FUNCTION = "update_comment_count_update"
    private const val INSERT_TRIGGER = "trigger_after_comment_insert"
    private const val DELETE_TRIGGER = "trigger_after_comment_delete"
    private const val UPDATE_TRIGGER = "trigger_after_comment_update"

    fun setupTriggers(db: Database)
    {
        transaction(db)
        {
            try
            {
                createFunctionsIfNotExists()
                createTriggersIfNotExists()
                logger.info("Database triggers initialized successfully")
            }
            catch (e: Exception)
            {
                logger.severe("Failed to initialize database triggers", e)
                throw e
            }
        }
    }

    private fun Transaction.createFunctionsIfNotExists()
    {
        deleteFunction(INSERT_FUNCTION)
        exec(
            """
            CREATE FUNCTION $INSERT_FUNCTION() RETURNS TRIGGER AS $$
            BEGIN
                UPDATE posts 
                SET "commentCount" = "commentCount" + 1
                WHERE id = NEW."rootPost";
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )

        deleteFunction(DELETE_FUNCTION)
        exec(
            """
            CREATE FUNCTION $DELETE_FUNCTION() RETURNS TRIGGER AS $$
            BEGIN
                UPDATE posts 
                SET "commentCount" = "commentCount" - 1 
                WHERE id = OLD."rootPost";
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )

        deleteFunction(UPDATE_FUNCTION)
        exec(
            """
            CREATE FUNCTION $UPDATE_FUNCTION() RETURNS TRIGGER AS $$
            BEGIN
                IF OLD."rootPost" <> NEW."rootPost" THEN
                    UPDATE posts SET "commentCount" = "commentCount" - 1 WHERE id = OLD.rootPost;
                    UPDATE posts SET "commentCount" = "commentCount" + 1 WHERE id = NEW.rootPost;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
    }

    private fun Transaction.createTriggersIfNotExists()
    {
        deleteTrigger(INSERT_TRIGGER)
        exec(
            """
            CREATE TRIGGER $INSERT_TRIGGER
            AFTER INSERT ON posts
            FOR EACH ROW
            EXECUTE FUNCTION $INSERT_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(DELETE_TRIGGER)
        exec(
            """
            CREATE TRIGGER $DELETE_TRIGGER
            AFTER DELETE ON posts
            FOR EACH ROW
            EXECUTE FUNCTION $DELETE_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(UPDATE_TRIGGER)
        exec(
            """
            CREATE TRIGGER $UPDATE_TRIGGER
            AFTER UPDATE ON posts
            FOR EACH ROW
            EXECUTE FUNCTION $UPDATE_FUNCTION();
            """.trimIndent()
        )
    }

    private fun Transaction.deleteFunction(functionName: String)
    {
        exec("DROP FUNCTION IF EXISTS $functionName() CASCADE;")
    }

    private fun Transaction.deleteTrigger(triggerName: String)
    {
        exec("DROP TRIGGER IF EXISTS $triggerName ON likes;")
    }
}