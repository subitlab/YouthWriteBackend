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
    private val notices = Collections.synchronizedMap(hashMapOf<NoticeId, Pair<Notice, Boolean>>())

    override suspend fun createNotice(notice: Notice, merge: Boolean)
    {
        val id = (notices.size + 1).toNoticeId()
        if (!merge || notice is Notice.SystemNotice)
            notices[id] = notice.copy(id = id, time = System.currentTimeMillis()) to false
        else if (notice is Notice.PostNotice)
        {
            val same = notices.values.singleOrNull {
                it.second &&
                it.first is Notice.PostNotice &&
                it.first.user == notice.user &&
                it.first.type == notice.type &&
                (it.first as? Notice.PostNotice)?.post == notice.post &&
                !it.first.read
            }
            if (same == null)
                notices[id] = notice.copy(id = id, time = System.currentTimeMillis()) to true
            else
            {
                val samePost = (same.first as Notice.PostNotice)
                val count = samePost.count + notice.count
                notices[samePost.id] = Notice.PostNotice.brief(samePost.id, samePost.type, samePost.time, samePost.user, samePost.post, count) to true
            }
        }
    }

    override suspend fun getNotice(id: NoticeId): Notice? = notices[id]?.first

    override suspend fun getNotices(
        user: UserId,
        type: Notice.Type?,
        read: Boolean?,
        begin: Long,
        count: Int
    ): Slice<Notice> =
        notices.values
            .asSequence()
            .map { it.first }
            .filter { it.user == user }
            .filter { type == null || it.type == type }
            .filter { read == null || it.read == read }
            .asSlice(begin, count)

    override suspend fun readNotice(id: NoticeId)
    {
        notices[id] = notices[id]!!.let { it.first.copy(read = true) to it.second }
    }

    override suspend fun readNotices(user: UserId)
    {
        notices.values.filter { it.first.user == user }.forEach { readNotice(it.first.id) }
    }

    override suspend fun deleteNotice(id: NoticeId)
    {
        notices.remove(id)
    }

    override suspend fun deleteNotices(user: UserId)
    {
        notices.entries.removeIf { it.value.first.user == user }
    }
}