@file:Suppress("ConvertCallChainIntoSequence")

package subit.database.memoryImpl

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.PostId.Companion.toPostId
import subit.dataClasses.Slice.Companion.asSlice
import subit.database.*
import subit.router.utils.withPermission
import subit.utils.getContentText
import subit.utils.toInstant
import java.util.*
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class PostsImpl: Posts, KoinComponent
{
    private val map = Collections.synchronizedMap(hashMapOf<PostId, PostInfo>())
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
    ): PostId
    {
        val id = (map.size + 1).toPostId()
        map[id] = PostInfo(
            id = id,
            author = author,
            anonymous = anonymous,
            block = block,
            top = top,
            state = state,
            view = 0,
            parent = parent,
            root = parent?.let { getPostInfo(it)?.root ?: it } ?: id

        )
        return id
    }

    override suspend fun isAncestor(parent: PostId, child: PostId): Boolean
    {
        var current = child
        while (current != parent)
        {
            val post = map[current] ?: return false
            current = post.parent ?: return false
        }
        return true
    }

    private fun sortBy(sortBy: Posts.PostListSort): (PostFull)->Long = {
        when (sortBy)
        {
            Posts.PostListSort.NEW          -> -(it.create ?: Long.MAX_VALUE)
            Posts.PostListSort.OLD          -> it.create ?: 0
            Posts.PostListSort.NEW_EDIT     -> -(it.lastModified ?: Long.MAX_VALUE)
            Posts.PostListSort.OLD_EDIT     -> it.lastModified ?: 0
            Posts.PostListSort.MORE_VIEW    -> -it.view
            Posts.PostListSort.MORE_LIKE    -> runBlocking { -stars.getStarsCount(it.id) }
            Posts.PostListSort.MORE_STAR    -> runBlocking { -likes.getLikesCount(it.id) }
            Posts.PostListSort.MORE_COMMENT -> map.values.count { post -> post.root == it.id }.toLong()
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
            .filter { isAncestor(pid, it.id) }
            .filter { it.state == State.NORMAL }
            .map { it }
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
            .filter { it.parent == pid }
            .filter { it.state == State.NORMAL }
            .map { getPostFull(it.id)!! }
            .sortedBy(sortBy(sortBy))
            .asSequence()
            .asSlice(begin, count)
        return children
    }

    override suspend fun setTop(pid: PostId, top: Boolean): Boolean
    {
        val post = map[pid] ?: return false
        map[pid] = post.copy(top = top)
        return true
    }

    override suspend fun setPostState(pid: PostId, state: State)
    {
        val post = map[pid] ?: return
        map[pid] = post.copy(state = state)
    }

    override suspend fun getPostInfo(pid: PostId): PostInfo?
    {
        return map[pid]
    }

    override suspend fun getPostFull(pid: PostId): PostFull? = getPostFull(pid, false)?.first

    private suspend fun getPostFull(pid: PostId, containsDraft: Boolean): Pair<PostFull, PostVersionInfo?>?
    {
        val post = map[pid] ?: return null
        val lastVersionId = postVersions.getLatestPostVersion(pid, containsDraft) ?: return null
        val lastVersion = postVersions.getPostVersion(lastVersionId) ?: return null

        return post.toPostFull(
            title = lastVersion.title,
            content = lastVersion.content,
            lastModified = (postVersions as PostVersionsImpl).getLastModified(pid).toEpochMilliseconds(),
            create = (postVersions as PostVersionsImpl).getCreate(pid).toEpochMilliseconds(),
            like = likes.getLikesCount(pid),
            star = stars.getStarsCount(pid),
            comment = map.values.count { it.root == pid }.toLong(),
            lastVersionId = lastVersionId,
            hotScore = getHotScore(pid),
        ) to lastVersion
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
    ): Slice<PostFullBasicInfo> = withPermission(loginUser, null)
    {
        @Suppress("NAME_SHADOWING")
        val draft =
            if (createBefore != null || createAfter != null || lastModifiedBefore != null || lastModifiedAfter != null) false
            else draft
        map.values
            .filter {
                @Suppress("SimplifyBooleanWithConstants")
                true
                && (author == null || it.author == author)
                && (block == null || it.block == block)
                && (top == null || it.top == top)
                && (state == null || it.state == state)
                && (tag == null || tags.getPostTags(it.id).contains(tag))
                && (comment == null || (it.parent != null) == comment)
            }
            .filter { canRead(it) }
            .mapNotNull { getPostFull(it.id, draft == true) }
            .mapNotNull {
                it.takeIf { _ ->
                    when (draft)
                    {
                        true  -> it.second == null || it.second?.draft == true
                        false -> it.second != null
                        else  -> true
                    }
                }?.first
            }
            .filter {
                @Suppress("SimplifyBooleanWithConstants")
                true
                && (createBefore == null || it.create!! <= createBefore.toEpochMilliseconds())
                && (createAfter == null || it.create!! >= createAfter.toEpochMilliseconds())
                && (lastModifiedBefore == null || it.lastModified!! <= lastModifiedBefore.toEpochMilliseconds())
                && (lastModifiedAfter == null || it.lastModified!! >= lastModifiedAfter.toEpochMilliseconds())
                && (containsKeyWord == null || (it.title?.contains(containsKeyWord) == true) || (it.content?.let(::getContentText)?.contains(containsKeyWord)) == true)

            }
            .sortedBy(sortBy(sortBy))
            .asSequence()
            .asSlice(begin, limit)
            .map { it.toPostFullBasicInfo() }
    }

    override suspend fun addView(pid: PostId)
    {
        val post = map[pid] ?: return
        map[pid] = post.copy(view = post.view + 1)
    }

    private fun getHotScore(pid: PostId): Double
    {
        val post = map[pid] ?: return 0.0
        val likesCount = runBlocking { likes.getLikesCount(pid) }
        val starsCount = runBlocking { stars.getStarsCount(pid) }
        val commentsCount: Int = map.values.count { it.root == pid }
        val createTime = (postVersions as PostVersionsImpl).getCreate(pid).toEpochMilliseconds()
        val time = (System.currentTimeMillis() - createTime).toDouble() /1000/*s*/ /60/*m*/ /60/*h*/
        return (post.view + likesCount * 3 + starsCount * 5 + commentsCount * 2) / time.pow(1.8)
    }

    override suspend fun monthly(loginUser: DatabaseUser?, begin: Long, count: Int): Slice<PostFullBasicInfo> = withPermission(loginUser, null)
    {
        val likes = likes as LikesImpl
        val after = Clock.System.now() - 30.days
        map.values
            .filter { canRead(it) }
            .map { getPostFull(it.id)!! }
            .sortedBy { -likes.getLikesAfter(it.id, after) }
            .asSequence()
            .asSlice(begin, count)
            .map { it.toPostFullBasicInfo() }
    }

    override suspend fun totalPostCount(comment: Boolean, duration: Duration?): Map<State, Long>
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        val res = map.values
            .filter { (it.parent != null) == comment }
            .map { getPostFull(it.id)!! }
            .filter { it.create != null && it.create >= time.toEpochMilliseconds() }
            .groupBy { it.state }
            .mapValues { it.value.size.toLong() }
        return State.entries.associateWith { res[it] ?: 0 }
    }

    override suspend fun totalReadCount(): Long = map.values.sumOf { it.view }
}
