package subit.database.memoryImpl

import subit.dataClasses.Notice
import subit.dataClasses.NoticeId
import subit.dataClasses.NoticeId.Companion.toNoticeId
import subit.dataClasses.Slice
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.UserId
import subit.database.Notices
import java.util.*

class NoticesImpl: Notices
{
    private val notices = Collections.synchronizedMap(hashMapOf<NoticeId, Notice>())

    override suspend fun createNotice(notice: Notice)
    {
        val id = (notices.size + 1).toNoticeId()
        notices[id] = notice.copy(id = id, time = System.currentTimeMillis())
    }

    override suspend fun getNotice(id: NoticeId): Notice? = notices[id]

    override suspend fun getNotices(
        user: UserId,
        type: Notice.Type?,
        read: Boolean?,
        begin: Long,
        count: Int
    ): Slice<Notice> =
        notices.values
            .asSequence()
            .filter { it.user == user }
            .filter { type == null || it.type == type }
            .filter { read == null || it.read == read }
            .asSlice(begin, count)

    override suspend fun readNotice(id: NoticeId)
    {
        notices[id] = notices[id]!!.copy(read = true)
    }

    override suspend fun readNotices(user: UserId)
    {
        notices.values.asSequence().filter { it.user == user }.map(Notice::id).forEach { readNotice(it) }
    }

    override suspend fun deleteNotice(id: NoticeId)
    {
        notices.remove(id)
    }

    override suspend fun deleteNotices(user: UserId)
    {
        notices.entries.removeIf { it.value.user == user }
    }
}