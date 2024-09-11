@file:Suppress("RemoveRedundantQualifierName")

package subit.database.sqlImpl

import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.functions.math.PowerFunction
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.Minute
import org.jetbrains.exposed.sql.statements.Statement
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.Slice
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.Slice.Companion.singleOrNull
import subit.database.*
import subit.database.Posts.PostListSort.*
import subit.database.sqlImpl.PostVersionsImpl.PostVersionsTable
import subit.database.sqlImpl.PostsImpl.PostsTable.view
import subit.router.home.AdvancedSearchData
import subit.utils.toTimestamp
import java.sql.ResultSet
import java.time.OffsetDateTime
import kotlin.reflect.typeOf

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
     * 创建时间, 为最早的版本的时间, 若不存在任何版本(理论上不可能)视为[OffsetDateTime.MIN], 创建alias为create
     *
     * 效果类似于:
     * ```sql
     * COALESCE(MIN(post_versions.time), '0001-01-01T00:00:00Z') AS create
     * ```
     */
    private val create = coalesce(
        PostVersionsTable.time.min(),
        0L.toTimestamp()
    ).alias("createTime")

    /**
     * 最后修改时间, 为最新的版本的时间, 若不存在任何版本(理论上不可能)视为[OffsetDateTime.MIN], 创建alias为lastModified
     *
     * 效果类似于:
     * ```sql
     * COALESCE(MAX(post_versions.time), '0001-01-01T00:00:00Z') AS lastModified
     * ```
     */
    private val lastModified = coalesce(
        PostVersionsTable.time.max(),
        0L.toTimestamp()
    ).alias("lastModifiedTime")

    private val lastVersionId = PostVersionsTable.id.max().alias("lastVersionId")

    /**
     * 一篇帖子的点赞数
     *
     * 效果类似于:
     * ```sql
     * COUNT(likes.id) AS like
     * ```
     */
    private val like = LikesImpl.LikesTable.user.count().alias("likeCount")

    /**
     * 一篇帖子的收藏数
     *
     * 效果类似于:
     * ```sql
     * COUNT(stars.id) AS star
     * ```
     */
    private val star = StarsImpl.StarsTable.post.count().alias("starCount")

    /**
     * 一篇帖子的评论数
     *
     * 效果类似于:
     * ```sql
     * COUNT(comments.id) AS comment
     * ```
     */
    private val comment = PostsTable.alias("comments")[PostsTable.id].count().alias("commentCount")

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
            title = if (type != postInfoType) row[PostVersionsTable.title] else "",
            content = if (type == postFullType) row[PostVersionsTable.content] else "",
            author = row[PostsTable.author].value,
            anonymous = row[PostsTable.anonymous],
            create = if (type != postInfoType) row[create.aliasOnlyExpression()].toEpochMilliseconds() else 0,
            lastModified = if (type != postInfoType) row[lastModified.aliasOnlyExpression()].toEpochMilliseconds() else 0,
            lastVersionId = if (type != postInfoType) row[lastVersionId.aliasOnlyExpression()]?.value ?: PostVersionId(0)
            else PostVersionId(0),
            view = row[PostsTable.view],
            block = row[PostsTable.block].value,
            state = row[PostsTable.state],
            like = if (type != postInfoType) row[like] else 0,
            star = if (type != postInfoType) row[star] else 0,
            parent = row[PostsTable.parent]?.value,
            root = row[PostsTable.rootPost]?.value
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
        PostVersionsTable.title,
        PostsTable.author,
        PostsTable.anonymous,
        create.aliasOnlyExpression(),
        lastModified.aliasOnlyExpression(),
        lastVersionId.aliasOnlyExpression(),
        PostsTable.view,
        PostsTable.block,
        PostsTable.state,
        like,
        star,
        PostsTable.parent,
        PostsTable.rootPost
    )

    /**
     * [PostFull]中包含的列
     */
    private val postFullColumns = postFullBasicInfoColumns + PostVersionsTable.content

    private fun Join.joinPostFull(): Join
    {
        val likesTable = (likes as LikesImpl).table
        val starsTable = (stars as StarsImpl).table
        val commentsTable = PostsTable.alias("comments")
        val postVersionsTable = (postVersions as PostVersionsImpl).table

        return this
            .joinQuery(
                on = { (it[postVersionsTable.post] as Expression<*>) eq PostsTable.id },
                joinType = JoinType.INNER,
                joinPart = {
                    postVersionsTable
                        .select(postVersionsTable.post, lastVersionId, create, lastModified)
                        .groupBy(postVersionsTable.post)
                }
            )
            .join(postVersionsTable, JoinType.INNER, postVersionsTable.id, lastVersionId.aliasOnlyExpression())
            .join(likesTable, JoinType.LEFT, PostsTable.id, likesTable.post)
            .join(starsTable, JoinType.LEFT, PostsTable.id, starsTable.post)
            .join(commentsTable, JoinType.LEFT, PostsTable.id, commentsTable[PostsTable.rootPost])
    }

    private fun Query.groupPostFull() = groupBy(*(postFullColumns - star - like).toTypedArray())

    private fun Table.joinPostFull() = Join(this).joinPostFull()

    override suspend fun createPost(
        author: UserId,
        anonymous: Boolean,
        block: BlockId,
        parent: PostId?,
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
            .joinPostFull()
            .join(descendantIds, JoinType.INNER, PostsTable.id, descendantIds[PostsTable.id])
            .select(postFullColumns)
            .andWhere { PostsTable.id neq pid }
            .groupPostFull()
            .orderBy(sortBy.order)
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
            .joinPostFull()
            .select(postFullColumns)
            .andWhere { PostsTable.parent eq pid }
            .andWhere { PostsTable.state eq State.NORMAL }
            .groupPostFull()
            .orderBy(sortBy.order)
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
            .joinPostFull()
            .select(postFullColumns)
            .where { PostsTable.id eq pid }
            .groupPostFull()
            .singleOrNull()
            ?.let { deserializePost<PostFull>(it) }
    }

    override suspend fun getPostFullBasicInfo(pid: PostId): PostFullBasicInfo? = query()
    {
        table
            .joinPostFull()
            .select(postFullBasicInfoColumns)
            .where { PostsTable.id eq pid }
            .groupPostFull()
            .singleOrNull()
            ?.let { deserializePost<PostFullBasicInfo>(it) }
    }

    private val Posts.PostListSort.order: Pair<Expression<*>, SortOrder>
        get() = when (this)
        {
            NEW          -> create.aliasOnlyExpression() to SortOrder.DESC
            OLD          -> create.aliasOnlyExpression() to SortOrder.ASC
            MORE_VIEW    -> view to SortOrder.DESC
            MORE_LIKE    -> like.delegate to SortOrder.DESC
            MORE_STAR    -> star.delegate to SortOrder.DESC
            MORE_COMMENT -> comment.delegate to SortOrder.DESC
        }

    /**
     * 获取帖子列表
     */
    override suspend fun getUserPosts(
        loginUser: DatabaseUser?,
        author: UserId,
        sortBy: Posts.PostListSort,
        begin: Long,
        limit: Int
    ): Slice<PostFullBasicInfo> = query()
    {
        // 构建查询，联结 PostsTable, BlocksTable 和 PermissionsTable
        val permissionTable = (permissions as PermissionsImpl).table
        val blockTable = (blocks as BlocksImpl).table

        if (loginUser == null)
        {
            return@query PostsTable
                .joinPostFull()
                .join(blockTable, JoinType.INNER, block, blockTable.id)
                .select(postFullBasicInfoColumns)
                .andWhere { PostsTable.author eq author }
                .andWhere { state eq State.NORMAL }
                .andWhere { parent.isNull() }
                .andWhere { blockTable.reading lessEq PermissionLevel.NORMAL }
                .groupPostFull()
                .orderBy(sortBy.order)
                .asSlice(begin, limit)
                .map { deserializePost<PostFullBasicInfo>(it) }
        }

        PostsTable
            .joinPostFull()
            .join(blockTable, JoinType.INNER, block, blockTable.id)
            .join(permissionTable, JoinType.LEFT, block, permissionTable.block) { permissionTable.user eq loginUser.id }
            .select(postFullBasicInfoColumns)
            .andWhere { PostsTable.author eq author }
            .andWhere { if (loginUser.hasGlobalAdmin()) Op.TRUE else state eq State.NORMAL }
            .andWhere { parent.isNull() }
            .groupBy(id, create, blockTable.id, blockTable.reading)
            .groupPostFull()
            .orHaving { permissionTable.permission.max() greaterEq blockTable.reading }
            .orHaving { blockTable.reading lessEq PermissionLevel.NORMAL }
            .orderBy(sortBy.order)
            .asSlice(begin, limit)
            .map { deserializePost<PostFullBasicInfo>(it) }
    }

    override suspend fun getBlockPosts(
        block: BlockId,
        sortBy: Posts.PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo> = query()
    {
        return@query table
            .joinPostFull()
            .select(postFullBasicInfoColumns)
            .andWhere { PostsTable.block eq block }
            .andWhere { state eq State.NORMAL }
            .andWhere { parent.isNull() }
            .groupPostFull()
            .orderBy(sortBy.order)
            .asSlice(begin, count)
            .map { deserializePost<PostFullBasicInfo>(it) }
    }

    override suspend fun getBlockTopPosts(block: BlockId, begin: Long, count: Int): Slice<PostFullBasicInfo> = query()
    {
        PostsTable
            .joinPostFull()
            .select(postFullBasicInfoColumns)
            .andWhere { PostsTable.block eq block }
            .andWhere { top eq true }
            .andWhere { state eq State.NORMAL }
            .andWhere { parent.isNull() }
            .groupPostFull()
            .asSlice(begin, count)
            .map { deserializePost<PostFullBasicInfo>(it) }
    }

    override suspend fun searchPosts(
        loginUser: DatabaseUser?,
        key: String,
        advancedSearchData: AdvancedSearchData,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo> = query()
    {
        val permissionTable = (permissions as PermissionsImpl).table
        val blockTable = (blocks as BlocksImpl).table
        val postVersionsTable = (postVersions as PostVersionsImpl).table
        val additionalConstraint: (SqlExpressionBuilder.()->Op<Boolean>)? =
            if (loginUser != null) ({ permissionTable.user eq loginUser.id })
            else null

        @Suppress("LiftReturnOrAssignment")
        fun Query.whereConstraint(): Query
        {
            var q = this

            if (advancedSearchData.blockIdList != null)
                q = q.andWhere { block inList advancedSearchData.blockIdList }

            if (advancedSearchData.authorIdList != null)
                q = q.andWhere { author inList advancedSearchData.authorIdList }

            if (advancedSearchData.isOnlyTitle == true)
                q = q.andWhere { postVersionsTable.title like "%$key%" }
            else
                q = q.andWhere { (postVersionsTable.title like "%$key%") or (postVersionsTable.content like "%$key%") }

            if (advancedSearchData.lastModifiedAfter != null)
                q = q.andWhere { lastModified.aliasOnlyExpression() greaterEq advancedSearchData.lastModifiedAfter.toTimestamp() }

            if (advancedSearchData.createTime != null)
            {
                val (l, r) = advancedSearchData.createTime
                q = q.andWhere {
                    val createTime = create.aliasOnlyExpression()
                    (createTime greaterEq l.toTimestamp()) and (createTime lessEq r.toTimestamp())
                }
            }

            if (advancedSearchData.isOnlyPost == true)
                q = q.andWhere { rootPost.isNull() }

            return q
        }

        PostsTable
            .joinPostFull()
            .join(blockTable, JoinType.INNER, block, blockTable.id)
            .join(
                permissionTable,
                JoinType.LEFT,
                block,
                permissionTable.block,
                additionalConstraint = additionalConstraint
            )
            .select(postFullBasicInfoColumns)
            .andWhere { if (loginUser.hasGlobalAdmin()) Op.TRUE else state eq State.NORMAL }
            .whereConstraint()
            .groupBy(id, create.aliasOnlyExpression(), blockTable.id, blockTable.reading)
            .groupPostFull()
            .orHaving { permissionTable.permission.max() greaterEq blockTable.reading }
            .orHaving { blockTable.reading lessEq PermissionLevel.NORMAL }
            .orderBy(hotScoreOrder, SortOrder.DESC)
            .asSlice(begin, count)
            .map { deserializePost<PostFullBasicInfo>(it) }
    }

    override suspend fun addView(pid: PostId): Unit = query()
    {
        PostsTable.update({ id eq pid }) { it[view] = view + 1 }
    }

    private val hotScoreOrder by lazy {
        val x =
            (view +
             TimesOp(like.delegate, longParam(3), LongColumnType()) +
             TimesOp(star.delegate, longParam(5), LongColumnType()) +
             TimesOp(comment.delegate, longParam(2), LongColumnType()) +
             1)


        val minute = (Minute(CurrentTimestamp - create.aliasOnlyExpression()) + 1) / 1024

        @Suppress("UNCHECKED_CAST")
        val order = x / (PowerFunction(minute, doubleParam(1.8)) as Expression<Long>)
        order
    }

    override suspend fun getRecommendPosts(loginUser: UserId?, count: Int): Slice<PostFullBasicInfo> = query()
    {
        val blocksTable = (blocks as BlocksImpl).table
        val permissionTable = (permissions as PermissionsImpl).table

        /**
         * 选择所属板块的reading权限小于等于NORMAL的帖子
         *
         * 按照 (浏览量+点赞数*3+收藏数*5+评论数*2)/(发帖到现在的时间(单位: 时间)的1.8次方)
         */

        table
            .joinPostFull()
            .join(blocksTable, JoinType.INNER, block, blocksTable.id)
            .join(permissionTable, JoinType.LEFT, block, permissionTable.block) { permissionTable.user eq loginUser }
            .select(postFullBasicInfoColumns)
            .andWhere { blocksTable.reading lessEq PermissionLevel.NORMAL }
            .andWhere { state eq State.NORMAL }
            .andWhere { rootPost.isNull() } // 不是评论
            .groupPostFull()
            .orderBy(hotScoreOrder, SortOrder.DESC)
            .asSlice(0, count)
            .map { deserializePost<PostFullBasicInfo>(it) }
    }
}