package subit.database.memoryImpl

import kotlinx.datetime.Instant
import subit.dataClasses.PrivateChat
import subit.dataClasses.PrivateChatId
import subit.dataClasses.PrivateChatId.Companion.toPrivateChatId
import subit.dataClasses.Slice
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.UserId
import subit.database.PrivateChats
import java.util.*

class PrivateChatsImpl: PrivateChats
{
    private val privateChats = Collections.synchronizedMap(hashMapOf<PrivateChatId, PrivateChat>())
    private val unread = Collections.synchronizedMap(hashMapOf<Long, Long>())
    private val block = Collections.synchronizedMap(hashMapOf<Long, Boolean>())
    private infix fun UserId.link(other: UserId): Long = (this.value.toLong() shl 32) or other.value.toLong()
    override suspend fun addPrivateChat(from: UserId, to: UserId, content: String): PrivateChat
    {
        val id = privateChats.size.toPrivateChatId()
        val chat = PrivateChat(id, from, to, System.currentTimeMillis(), content)
        privateChats[id] = chat
        unread[from link to] = (unread[from link to] ?: 0) + 1
        return chat
    }

    private fun getPrivateChats(
        user1: UserId,
        user2: UserId,
        before: Long? = null,
        after: Long? = null,
        begin: Long,
        count: Int
    ): Slice<PrivateChat>
    {
        val list = privateChats.values.filter { it.from == user1 && it.to == user2 || it.from == user2 && it.to == user1 }
        return list.let { list ->
            if (before != null) list.filter { it.time <= before }
            else if (after != null) list.filter { it.time >= after }
            else list
        }.let { list ->
            if (before != null) list.sortedByDescending { it.time }
            else if (after != null) list.sortedBy { it.time }
            else list
        }.asSequence().asSlice(begin, count)
    }

    override suspend fun getPrivateChatsBefore(
        user1: UserId,
        user2: UserId,
        time: Instant,
        begin: Long,
        count: Int
    ): Slice<PrivateChat> =
        getPrivateChats(user1, user2, before = time.toEpochMilliseconds(), begin = begin, count = count)

    override suspend fun getPrivateChatsAfter(
        user1: UserId,
        user2: UserId,
        time: Instant,
        begin: Long,
        count: Int
    ): Slice<PrivateChat> =
        getPrivateChats(user1, user2, after = time.toEpochMilliseconds(), begin = begin, count = count)

    override suspend fun getChatUsers(uid: UserId, begin: Long, count: Int): Slice<UserId> =
        privateChats.values.asSequence()
            .filter { it.from == uid || it.to == uid }
            .groupBy { if (it.from == uid) it.to else it.from }
            .map { it.key to it.value.maxOfOrNull { it.time } }
            .sortedByDescending { it.second }
            .asSequence()
            .map { it.first }
            .asSlice(begin, count)

    override suspend fun getUnreadCount(uid: UserId, other: UserId): Long = unread[other link uid] ?: 0
    override suspend fun getUnreadCount(uid: UserId): Long = unread.values.filter { it.toInt() == uid.value }.sum()
    override suspend fun setRead(uid: UserId, other: UserId)
    {
        unread[other link uid] = 0
    }

    override suspend fun setReadAll(uid: UserId)
    {
        unread.keys.removeIf { it.toInt() == uid.value }
    }

    override suspend fun setIsBlock(from: UserId, to: UserId, isBlock: Boolean)
    {
        block[from link to] = isBlock
    }

    override suspend fun getIsBlock(from: UserId, to: UserId): Boolean = block[from link to] ?: false
}