package subit.database.memoryImpl

import subit.dataClasses.PostId
import subit.dataClasses.UserId
import subit.database.Likes
import java.util.*

class LikesImpl: Likes
{
    private val map = Collections.synchronizedMap(hashMapOf<Pair<UserId,PostId>,Boolean>())

    override suspend fun like(uid: UserId, pid: PostId)
    {
        map[uid to pid] = true
    }
    override suspend fun unlike(uid: UserId, pid: PostId)
    {
        map.remove(uid to pid)
    }
    override suspend fun getLike(uid: UserId, pid: PostId): Boolean = map[uid to pid] != null
    override suspend fun getLikes(post: PostId): Long
    {
        val likes = map.entries.filter { it.key.second == post }
        return likes.count { it.value }.toLong()
    }
}