package subit.database

import subit.dataClasses.Notice
import subit.dataClasses.NoticeId
import subit.dataClasses.Slice
import subit.dataClasses.UserId

interface Notices
{
    /**
     * 依据[notice]创建一条新的通知, 但[Notice.id]和[Notice.time]会被忽略(由数据库自动生成)
     * @param notice 通知
     */
    suspend fun createNotice(notice: Notice)
    suspend fun getNotice(id: NoticeId): Notice?
    suspend fun getNotices(user: UserId, type: Notice.Type?, read: Boolean?, begin: Long, count: Int): Slice<Notice>
    suspend fun readNotice(id: NoticeId)
    suspend fun readNotices(user: UserId)
    suspend fun deleteNotice(id: NoticeId)
    suspend fun deleteNotices(user: UserId)
}