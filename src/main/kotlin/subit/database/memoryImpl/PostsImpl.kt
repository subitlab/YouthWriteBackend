@file:Suppress("ConvertCallChainIntoSequence")

package subit.database.memoryImpl

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.PostId.Companion.toPostId
import subit.dataClasses.Slice.Companion.asSlice
import subit.database.*
import subit.router.home.AdvancedSearchData
import java.util.*
import kotlin.math.pow
import kotlin.time.Duration.Companion.days

class PostsImpl: Posts, KoinComponent
{
    private val map = Collections.synchronizedMap(hashMapOf<PostId, Pair<PostInfo, Boolean>>())
    private val likes: Likes by inject()
    private val stars: Stars by inject()
    private val postVersions: PostVersions by inject()
    private val tags: Tags by inject()
    override suspend fun createPost(
        author: UserId,
        anonymous: Boolean,
        block: BlockId,
        parent: PostId?,
        state: State,
        top: Boolean
    ): PostId?
    {
        val id = (map.size + 1).toPostId()
        map[id] = PostInfo(
            id = id,
            author = author,
            anonymous = anonymous,
            block = block,
            state = state,
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
            Posts.PostListSort.NEW          -> -(it.create ?: 0)
            Posts.PostListSort.OLD          -> it.create ?: 0
            Posts.PostListSort.MORE_VIEW    -> -it.view
            Posts.PostListSort.MORE_LIKE    -> runBlocking { -stars.getStarsCount(it.id) }
            Posts.PostListSort.MORE_STAR    -> runBlocking { -likes.getLikes(it.id) }
            Posts.PostListSort.MORE_COMMENT -> map.values.count { post -> post.first.root == it.id }.toLong()
            Posts.PostListSort.HOT          -> -getHotScore(it.id).toLong()
            Posts.PostListSort.RANDOM_HOT   -> -getHotScore(it.id).toLong() + Random().nextInt(100)
        }
    }

    override suspend fun getDescendants(
        pid: PostId,
        sortBy: Posts.PostListSort,
        begin: Long,
        count: Int
    ): Slice<PostFull>
    {
        val descendants = map.values
            .filter { isAncestor(pid, it.first.id) }
            .filter { it.first.state == State.NORMAL }
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
        val children = map.values
            .filter { it.first.parent == pid }
            .filter { it.first.state == State.NORMAL }
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
        val lastVersionId = postVersions.getPostVersions(pid, false, 0, 1).list.firstOrNull()?.id ?: return null
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
    override suspend fun getPosts(
        loginUser: DatabaseUser?,
        author: UserId?,
        block: BlockId?,
        top: Boolean?,
        state: State?,
        tag: String?,
        sortBy: Posts.PostListSort,
        begin: Long,
        limit: Int
    ): Slice<PostFullBasicInfo> = withPermission(loginUser)
    {
        map.values
            .filter {
                @Suppress("SimplifyBooleanWithConstants")
                true
                && (author == null || it.first.author == author)
                && (block == null || it.first.block == block)
                && (top == null || it.second == top)
                && (state == null || it.first.state == state)
                && (tag == null || tags.getPostTags(it.first.id).contains(tag))
            }
            .filter { canRead(it.first) }
            .map { getPostFull(it.first.id)!! }
            .sortedBy(sortBy(sortBy))
            .asSequence()
            .asSlice(begin, limit)
            .map { it.toPostFullBasicInfo() }
    }

    override suspend fun searchPosts(
        loginUser: DatabaseUser?,
        key: String,
        advancedSearchData: AdvancedSearchData,
        begin: Long,
        count: Int
    ): Slice<PostFullBasicInfo> = withPermission(loginUser)
    {
        map.values
            .map { it.first }
            .map { getPostFull(it.id)!! }
            .filter { it.lastVersionId != null }
            .filter { it.title!!.contains(key) || it.content!!.contains(key) }
            .filter { canRead(it.toPostInfo()) }
            .filter {
                val post = it
                val blockConstraint =
                    if (advancedSearchData.blockIdList != null) (post.block in advancedSearchData.blockIdList)
                    else true
                val userConstraint =
                    if (advancedSearchData.authorIdList != null) (post.author in advancedSearchData.authorIdList)
                    else true
                val contentConstraint =
                    if (advancedSearchData.isOnlyTitle == true) (post.title!!.contains(key))
                    else ((post.title!!.contains(key)) || (post.content!!.contains(key)))
                val lastModifiedConstraint =
                    if (advancedSearchData.lastModifiedAfter != null)
                        (post.lastModified!! >= advancedSearchData.lastModifiedAfter)
                    else true
                val createTimeConstraint =
                    if (advancedSearchData.createTime != null)
                        (post.create!! >= advancedSearchData.createTime.first && post.create <= advancedSearchData.createTime.second)
                    else true
                blockConstraint && userConstraint && contentConstraint && lastModifiedConstraint && createTimeConstraint
            }
            .asSequence()
            .asSlice(begin, count)
            .map { it.toPostFullBasicInfo() }
    }

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

    override suspend fun monthly(loginUser: DatabaseUser?, begin: Long, count: Int): Slice<PostFullBasicInfo> = withPermission(loginUser)
    {
        val likes = likes as LikesImpl
        val after = Clock.System.now() - 30.days
        map.values
            .filter { canRead(it.first) }
            .map { getPostFull(it.first.id)!! }
            .sortedBy { -likes.getLikesAfter(it.id, after) }
            .asSequence()
            .asSlice(begin, count)
            .map { it.toPostFullBasicInfo() }
    }
}
