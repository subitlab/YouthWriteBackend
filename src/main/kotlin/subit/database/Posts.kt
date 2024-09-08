package subit.database

import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.router.home.AdvancedSearchData

interface Posts
{
    @Serializable
    enum class PostListSort
    {
        NEW,
        OLD,
        MORE_VIEW,
        MORE_LIKE,
        MORE_STAR,
        MORE_COMMENT,
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
        top: Boolean = false
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
     * 获取用户发布的帖子
     * @param loginUser 当前操作用户, null表示未登录, 返回的帖子应是该用户可见的.
     */
    suspend fun getUserPosts(
        loginUser: DatabaseUser? = null,
        author: UserId,
        sortBy: PostListSort,
        begin: Long,
        limit: Int,
    ): Slice<PostFullBasicInfo>

    suspend fun getBlockPosts(
        block: BlockId,
        sortBy: PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo>

    suspend fun getBlockTopPosts(block: BlockId, begin: Long, count: Int): Slice<PostFullBasicInfo>
    suspend fun searchPosts(
        loginUser: DatabaseUser?,
        key: String,
        advancedSearchData: AdvancedSearchData,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo>

    suspend fun addView(pid: PostId)

    /**
     * 获取首页推荐, 应按照时间/浏览量/点赞等参数随机, 即越新/点赞越高/浏览量越高...随机到的几率越大.
     * @param count 推荐数量
     */
    suspend fun getRecommendPosts(loginUser: UserId?, count: Int): Slice<PostFullBasicInfo>
}