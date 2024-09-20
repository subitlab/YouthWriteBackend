package subit.dataClasses

import kotlinx.serialization.Serializable

/**
 * 通知
 */
sealed interface Notice
{
    /**
     * 通知的类型
     */
    @Serializable
    enum class Type
    {
        /**
         * 系统通知
         */
        SYSTEM,

        /**
         * 帖子被评论
         */
        POST_COMMENT,

        /**
         * 评论被回复
         */
        COMMENT_REPLY,

        /**
         * 点赞
         */
        LIKE,

        /**
         * 收藏
         */
        STAR,
    }

    val id: NoticeId
    val time: Long
    val type: Type
    val user: UserId
    val read: Boolean

    @Serializable
    data class PostNotice(
        override val id: NoticeId = NoticeId(0),
        override val time: Long = System.currentTimeMillis(),
        override val type: Type,
        override val user: UserId,
        override val read: Boolean = false,
        val post: PostId,
        val count: Long = 1,
    ): Notice
    {
        init
        {
            require(count > 0) { "count must be positive" }
            require(type != Type.SYSTEM) { "type must not be SYSTEM" }
        }

        companion object
        {
            val example =
                PostNotice(NoticeId(1), System.currentTimeMillis(), Type.POST_COMMENT, UserId(1), false, PostId(1), 1)
        }
    }

    /**
     * 系统通知
     * @property content 内容
     */
    @Serializable
    data class SystemNotice(
        override val id: NoticeId = NoticeId(0),
        override val time: Long = System.currentTimeMillis(),
        override val user: UserId,
        override val read: Boolean = false,
        val content: String,
    ): Notice
    {
        override val type: Type get() = Type.SYSTEM

        companion object
        {
            val example = SystemNotice(user = UserId(1), content = "系统通知内容")
        }
    }

    fun copy(
        id: NoticeId = this.id,
        time: Long = this.time,
        user: UserId = this.user,
        read: Boolean = this.read,
    ): Notice = when (this)
    {
        // 使用判断类型使用data class的copy方法
        // 设置content和count是为了保证调用data class的copy方法而不是该接口的copy方法
        is SystemNotice -> this.copy(id = id, time = time, user = user, read = read, content = content)
        is PostNotice   -> this.copy(id = id, time = time, user = user, read = read, count = count)
    }
}