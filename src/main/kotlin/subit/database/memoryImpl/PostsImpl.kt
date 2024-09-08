package subit.database.memoryImpl

import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.PostId.Companion.toPostId
import subit.dataClasses.Slice.Companion.asSlice
import subit.database.*
import subit.router.home.AdvancedSearchData
import java.util.*
import kotlin.math.pow

class PostsImpl: Posts, KoinComponent
{
    private val map = Collections.synchronizedMap(hashMapOf<PostId, Pair<PostInfo, Boolean>>())
    private val blocks: Blocks by inject()
    private val permissions: Permissions by inject()
    private val likes: Likes by inject()
    private val stars: Stars by inject()
    private val postVersions: PostVersions by inject()
    override suspend fun createPost(
        author: UserId,
        anonymous: Boolean,
        block: BlockId,
        parent: PostId?,
        top: Boolean
    ): PostId
    {
        val id = (map.size + 1).toPostId()
        map[id] = PostInfo(
            id = id,
            author = author,
            anonymous = anonymous,
            block = block,
            state = State.NORMAL,
            view = 0,
            parent = parent,
            root = parent?.let { getPostInfo(it)?.root ?: it } ?: id

        ) to top
        return id
    }

    override suspend fun isAncestor(parent: PostId, child: PostId): Boolean
    {
        var current = child
        while (current != parent)
        {
            val post = map[current] ?: return false
            current = post.first.parent ?: return false
        }
        return true
    }

    private fun sortBy(sortBy: Posts.PostListSort): (PostFull)->Long = {
        when (sortBy)
        {
            Posts.PostListSort.NEW          -> -it.create
            Posts.PostListSort.OLD          -> it.create
            Posts.PostListSort.MORE_VIEW    -> -it.view
            Posts.PostListSort.MORE_LIKE    -> runBlocking { -stars.getStarsCount(it.id) }
            Posts.PostListSort.MORE_STAR    -> runBlocking { -likes.getLikes(it.id) }
            Posts.PostListSort.MORE_COMMENT -> map.values.count { post -> post.first.root == it.id }.toLong()
        }
    }

    override suspend fun getDescendants(
        pid: PostId,
        sortBy: Posts.PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFull>
    {
        val post = map[pid]?.first ?: return Slice.empty()
        val descendants = map.values
            .filter { isAncestor(pid, it.first.id) }
            .filter { it.first.state == State.NORMAL }
            .filter {
                val blockFull = blocks.getBlock(it.first.block) ?: return@filter false
                val permission = permissions.getPermission(blockFull.id, post.author)
                permission >= blockFull.reading
            }
            .map { it.first }
            .map { getPostFull(it.id)!! }
            .sortedBy(sortBy(sortBy))
            .asSequence()
            .asSlice(begin, count)
        return descendants
    }

    override suspend fun getChildPosts(
        pid: PostId,
        sortBy: Posts.PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFull>
    {
        val post = map[pid]?.first ?: return Slice.empty()
        val children = map.values
            .filter { it.first.parent == pid }
            .filter { it.first.state == State.NORMAL }
            .filter {
                val blockFull = blocks.getBlock(it.first.block) ?: return@filter false
                val permission = permissions.getPermission(blockFull.id, post.author)
                permission >= blockFull.reading
            }
            .map { it.first }
            .map { getPostFull(it.id)!! }
            .sortedBy(sortBy(sortBy))
            .asSequence()
            .asSlice(begin, count)
        return children
    }

    override suspend fun setTop(pid: PostId, top: Boolean): Boolean
    {
        val post = map[pid] ?: return false
        map[pid] = post.first to top
        return true
    }

    override suspend fun setPostState(pid: PostId, state: State)
    {
        val post = map[pid] ?: return
        map[pid] = post.first.copy(state = state) to post.second
    }

    override suspend fun getPostInfo(pid: PostId): PostInfo?
    {
        return map[pid]?.first
    }

    override suspend fun getPostFull(pid: PostId): PostFull?
    {
        val post = map[pid]?.first ?: return null
        val lastVersionId = postVersions.getPostVersions(pid, 0, 1).list.firstOrNull()?.id ?: return null
        val lastVersion = postVersions.getPostVersion(lastVersionId) ?: return null

        return post.toPostFull(
            title = lastVersion.title,
            content = lastVersion.content,
            lastModified = (postVersions as PostVersionsImpl).getLastModified(pid).toEpochMilliseconds(),
            create = (postVersions as PostVersionsImpl).getCreate(pid).toEpochMilliseconds(),
            like = likes.getLikes(pid),
            star = stars.getStarsCount(pid),
            lastVersionId = lastVersionId
        )
    }

    override suspend fun getPostFullBasicInfo(pid: PostId): PostFullBasicInfo? = getPostFull(pid)?.toPostFullBasicInfo()

    @Suppress("ConvertCallChainIntoSequence")
    override suspend fun getUserPosts(
        loginUser: DatabaseUser?,
        author: UserId,
        sortBy: Posts.PostListSort,
        begin: Long,
        limit: Int
    ): Slice<PostFullBasicInfo> =
        map.values
            .filter { it.first.author == author }
            .filter {
                val blockFull = blocks.getBlock(it.first.block) ?: return@filter false
                val permission = loginUser?.let { permissions.getPermission(blockFull.id, loginUser.id) }
                                 ?: PermissionLevel.NORMAL
                permission >= blockFull.reading && (it.first.state == State.NORMAL)
            }
            .map { it.first }
            .map { getPostFull(it.id)!! }
            .sortedBy(sortBy(sortBy))
            .asSequence()
            .asSlice(begin, limit)
            .map { it.toPostFullBasicInfo() }

    @Suppress("ConvertCallChainIntoSequence")
    override suspend fun getBlockPosts(
        block: BlockId,
        sortBy: Posts.PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo> = map.values
        .filter { it.first.block == block }
        .map { it.first }
        .map { getPostFull(it.id)!! }
        .sortedBy(sortBy(sortBy))
        .asSequence()
        .asSlice(begin, count)
        .map { it.toPostFullBasicInfo() }

    override suspend fun getBlockTopPosts(block: BlockId, begin: Long, count: Int): Slice<PostFullBasicInfo> =
        map.values
            .filter { it.first.block == block && it.second }
            .map { it.first }
            .map { getPostFull(it.id)!! }
            .asSequence()
            .asSlice(begin, count)
            .map { it.toPostFullBasicInfo() }

    override suspend fun searchPosts(
        loginUser: DatabaseUser?,
        key: String,
        advancedSearchData: AdvancedSearchData,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo> =
        map.values
            .map { it.first }
            .map { getPostFull(it.id)!! }
            .filter { it.title.contains(key) || it.content.contains(key) }
            .filter {
                val blockFull = blocks.getBlock(it.block) ?: return@filter false
                val permission =
                    loginUser?.let { permissions.getPermission(blockFull.id, loginUser.id) }
                    ?: PermissionLevel.NORMAL
                permission >= blockFull.reading
            }
            .filter {
                val post = it
                val blockConstraint =
                    if (advancedSearchData.blockIdList != null) (post.block in advancedSearchData.blockIdList)
                    else true
                val userConstraint =
                    if (advancedSearchData.authorIdList != null) (post.author in advancedSearchData.authorIdList)
                    else true
                val contentConstraint =
                    if (advancedSearchData.isOnlyTitle == true) (post.title.contains(key))
                    else ((post.title.contains(key)) || (post.content.contains(key)))
                val lastModifiedConstraint =
                    if (advancedSearchData.lastModifiedAfter != null)
                            (post.lastModified >= advancedSearchData.lastModifiedAfter)
                    else true
                val createTimeConstraint =
                    if (advancedSearchData.createTime != null)
                            (post.create >= advancedSearchData.createTime.first && post.create <= advancedSearchData.createTime.second)
                    else true
                blockConstraint && userConstraint && contentConstraint && lastModifiedConstraint && createTimeConstraint
            }
            .asSequence()
            .asSlice(begin, count)
            .map { it.toPostFullBasicInfo() }

    override suspend fun addView(pid: PostId)
    {
        val post = map[pid] ?: return
        map[pid] = post.first.copy(view = post.first.view + 1) to post.second
    }

    private fun getHotScore(pid: PostId): Double
    {
        val post = map[pid]?.first ?: return 0.0
        val likesCount = runBlocking { likes.getLikes(pid) }
        val starsCount = runBlocking { stars.getStarsCount(pid) }
        val commentsCount: Int = map.values.count { it.first.root == pid }
        val createTime = (postVersions as PostVersionsImpl).getCreate(pid).toEpochMilliseconds()
        val time = (System.currentTimeMillis() - createTime).toDouble() /1000/*s*/ /60/*m*/ /60/*h*/
        return (post.view + likesCount * 3 + starsCount * 5 + commentsCount * 2) / time.pow(1.8)
    }

    @Suppress("ConvertCallChainIntoSequence")
    override suspend fun getRecommendPosts(loginUser: UserId?, count: Int): Slice<PostFullBasicInfo> = map.values
        .filter { it.first.state == State.NORMAL }
        .filter {
            val blockFull = blocks.getBlock(it.first.block) ?: return@filter false
            val permission = loginUser?.let { permissions.getPermission(blockFull.id, loginUser) }
                             ?: PermissionLevel.NORMAL
            permission >= blockFull.reading
        }
        .sortedByDescending { getHotScore(it.first.id) }
        .map { it.first }
        .map { getPostFull(it.id)!! }
        .map { it.toPostFullBasicInfo() }
        .asSequence()
        .asSlice(1, count)
}
