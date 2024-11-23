package subit.database

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import kotlin.time.Duration

interface Posts
{
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
    ): PostId?

    /**
     * 判断两个帖子是否有父子关系(包括祖先后代关系)
     */
    suspend fun isAncestor(parent: PostId, child: PostId): Boolean

    suspend fun setTop(pid: PostId, top: Boolean): Boolean
    suspend fun setPostState(pid: PostId, state: State)
    suspend fun getPostInfo(pid: PostId): PostInfo?
    suspend fun getPostFull(pid: PostId): PostFull?
    suspend fun getPostFullBasicInfo(pid: PostId): PostFullBasicInfo?

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
        sortBy: PostListSort,
        begin: Long,
        limit: Int,
        full: Boolean = false,
    ): Slice<IPostFull<*, *>>

    /**
     * 获得若干帖子的基本信息, 用于展示列表
     * @param loginUser 当前操作用户, null表示未登录, 返回的帖子应是该用户可见的.
     * @param posts 帖子ID列表
     */
    suspend fun mapToPostFullBasicInfo(
        loginUser: UserFull? = null,
        posts: List<PostId?>,
    ): List<PostFullBasicInfo?>

    suspend fun addView(pid: PostId)

    /**
     * 获得***最近一个月***点赞数最多的帖子
     */
    suspend fun monthly(loginUser: UserFull?, begin: Long, count: Int): Slice<PostFullBasicInfo>

    suspend fun totalPostCount(comment: Boolean, duration: Duration?): Map<State, Long>
    suspend fun totalReadCount(): Long
}