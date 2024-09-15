package subit.database

import subit.dataClasses.*
import kotlin.time.Duration

/**
 * @author nullaqua
 */
interface Likes
{
    suspend fun addLike(uid: UserId, pid: PostId)
    suspend fun removeLike(uid: UserId, pid: PostId)
    suspend fun getLike(uid: UserId, pid: PostId): Boolean
    suspend fun getLikesCount(pid: PostId): Long
    suspend fun getLikes(
        user: UserId? = null,
        post: PostId? = null,
        begin: Long = 1,
        limit: Int = Int.MAX_VALUE,
    ): Slice<Like>

    suspend fun totalLikesCount(duration: Duration?): Long
}