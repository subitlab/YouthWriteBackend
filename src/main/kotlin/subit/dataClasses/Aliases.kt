@file:Suppress("unused")

package subit.dataClasses

import kotlinx.serialization.Serializable

/// 各种类型别名, 和相关类型转换等方法. 在需要用到这些类型的地方尽量使用别名, 以便于需要修改时全局修改 ///

interface Id<ID, T>: Comparable<ID> where T: Number, T: Comparable<T>, ID: Id<ID, T>
{
    val value: T
    override fun compareTo(other: ID): Int = value.compareTo(other.value)

    companion object
    {
        /**
         * 未知的ID
         */
        fun <T> unknown(id: T): Id<*, T> where T: Number, T: Comparable<T> = object: Id<Nothing, T>
        {
            override val value: T = id
            override fun toString(): String = id.toString()
        }
    }
}

@JvmInline
@Serializable
value class BlockId(override val value: Int): Id<BlockId, Int>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toBlockId() = BlockId(toInt())
        fun String.toBlockIdOrNull() = toIntOrNull()?.let(::BlockId)
        fun Number.toBlockId() = BlockId(toInt())
    }
}

@JvmInline
@Serializable
value class UserId(override val value: Int): Id<UserId, Int>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toUserId() = UserId(toInt())
        fun String.toUserIdOrNull() = toIntOrNull()?.let(::UserId)
        fun Number.toUserId() = UserId(toInt())
    }
}

@JvmInline
@Serializable
value class PostId(override val value: Long): Id<PostId, Long>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toPostId() = PostId(toLong())
        fun String.toPostIdOrNull() = toLongOrNull()?.let(::PostId)
        fun Number.toPostId() = PostId(toLong())
    }
}

@JvmInline
@Serializable
value class PostVersionId(override val value: Long): Id<PostVersionId, Long>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toPostVersionId() = PostVersionId(toLong())
        fun String.toPostVersionIdOrNull() = toLongOrNull()?.let(::PostVersionId)
        fun Number.toPostVersionId() = PostVersionId(toLong())
    }
}

@JvmInline
@Serializable
value class WordMarkingId(override val value: Long): Id<WordMarkingId, Long>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toWordMarkingId() = WordMarkingId(toLong())
        fun String.toWordMarkingIdOrNull() = toLongOrNull()?.let(::WordMarkingId)
        fun Number.toWordMarkingId() = WordMarkingId(toLong())
    }
}

@JvmInline
@Serializable
value class ReportId(override val value: Long): Id<ReportId, Long>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toReportId() = ReportId(toLong())
        fun String.toReportIdOrNull() = toLongOrNull()?.let(::ReportId)
        fun Number.toReportId() = ReportId(toLong())
    }
}

@JvmInline
@Serializable
value class NoticeId(override val value: Long): Id<NoticeId, Long>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toNoticeId() = NoticeId(toLong())
        fun String.toNoticeIdOrNull() = toLongOrNull()?.let(::NoticeId)
        fun Number.toNoticeId() = NoticeId(toLong())
    }
}

@JvmInline
@Serializable
value class PrivateChatId(override val value: Long): Id<PrivateChatId, Long>
{
    override fun toString(): String = value.toString()

    companion object
    {
        fun String.toPrivateChatId() = PrivateChatId(toLong())
        fun String.toPrivateChatIdOrNull() = toLongOrNull()?.let(::PrivateChatId)
        fun Number.toPrivateChatId() = PrivateChatId(toLong())
    }
}