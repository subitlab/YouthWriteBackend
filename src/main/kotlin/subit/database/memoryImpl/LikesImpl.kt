package subit.database.memoryImpl

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import subit.dataClasses.Like
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.UserId
import subit.database.Likes
import java.util.*

class LikesImpl: Likes
{
    private val map = Collections.synchronizedMap(hashMapOf<Pair<UserId,PostId>,Instant>())

    override suspend fun addLike(uid: UserId, pid: PostId)
    {
        map[uid to pid] = Clock.System.now()
    }
    override suspend fun removeLike(uid: UserId, pid: PostId)
    {
        map.remove(uid to pid)
    }
    override suspend fun getLike(uid: UserId, pid: PostId): Boolean = map[uid to pid] != null
    override suspend fun getLikesCount(pid: PostId): Long
    {
        val likes = map.entries.filter { it.key.second == pid }
        return likes.size.toLong()
    }

    override suspend fun getLikes(user: UserId?, post: PostId?, begin: Long, limit: Int): Slice<Like>
    {
        val likes = map.entries.filter { (user == null || it.key.first == user) && (post == null || it.key.second == post) }
        return likes.asSequence().map { Like(it.key.first, it.key.second, it.value.toEpochMilliseconds()) }.asSlice(begin, limit)
    }

    fun getLikesAfter(post: PostId, time: Instant): Long
    {
        val likes = map.entries.filter { it.key.second == post && it.value > time }
        return likes.size.toLong()
    }
}