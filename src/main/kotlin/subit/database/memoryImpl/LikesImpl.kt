package subit.database.memoryImpl

import kotlinx.datetime.Instant
import subit.dataClasses.Like
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.UserId
import subit.database.Likes
import java.util.*
import kotlin.time.Duration

class LikesImpl: Likes
{
    private val set = Collections.synchronizedSet(hashSetOf<Like>())
    override suspend fun addLike(uid: UserId, pid: PostId)
    {
        set.add(Like(uid, pid, System.currentTimeMillis()))
    }

    override suspend fun removeLike(uid: UserId, pid: PostId)
    {
        set.removeIf { it.user == uid && it.post == pid }
    }

    override suspend fun getLike(uid: UserId, pid: PostId): Boolean =
        set.count { it.user == uid && it.post == pid } > 0

    override suspend fun getLikesCount(pid: PostId): Long =
        set.count { it.post == pid }.toLong()

    override suspend fun getLikes(user: UserId?, post: PostId?, begin: Long, limit: Int): Slice<Like> =
        set.filter { (user == null || it.user == user) && (post == null || it.post == post) }
            .sortedByDescending(Like::time).asSequence().asSlice(begin, limit)

    override suspend fun totalLikesCount(duration: Duration?): Long
    {
        val time = duration?.let { System.currentTimeMillis() - it.inWholeMilliseconds } ?: 0
        return set.count { it.time > time }.toLong()
    }

    fun getLikesAfter(pid: PostId, time: Instant): Long =
        set.count { it.post == pid && it.time > time.toEpochMilliseconds() }.toLong()
}