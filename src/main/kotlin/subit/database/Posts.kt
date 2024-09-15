package subit.database

import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.router.home.AdvancedSearchData

interface Posts
{
    @Serializable
    enum class PostListSort
    {
        /**
         * 按照时间从新到旧排序
         */
        NEW,
        /**
         * 按照时间从旧到新排序
         */
        OLD,
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

    /**
     * 获取一个节点的所有子孙节点(即所有后代, 不包括自己)
     */
    suspend fun getDescendants(pid: PostId, sortBy: PostListSort, begin: Long, count: Int): Slice<PostFull>

    /**
     * 获取一个节点的所有子节点(即所有直接子节点)
     */
    suspend fun getChildPosts(pid: PostId, sortBy: PostListSort, begin: Long, count: Int): Slice<PostFull>

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
     * @param sortBy 排序方式
     */
    suspend fun getPosts(
        loginUser: DatabaseUser? = null,
        author: UserId?,
        block: BlockId?,
        top: Boolean?,
        state: State?,
        tag: String?,
        comment: Boolean,
        sortBy: PostListSort,
        begin: Long,
        limit: Int
    ): Slice<PostFullBasicInfo>

    suspend fun searchPosts(
        loginUser: DatabaseUser?,
        key: String,
        advancedSearchData: AdvancedSearchData,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo>

    suspend fun addView(pid: PostId)

    /**
     * 获得***最近一个月***点赞数最多的帖子
     */
    suspend fun monthly(loginUser: DatabaseUser?, begin: Long, count: Int): Slice<PostFullBasicInfo>
}