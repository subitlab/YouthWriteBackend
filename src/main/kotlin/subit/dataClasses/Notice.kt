package subit.dataClasses

import kotlinx.serialization.Serializable
import subit.utils.SUB_CONTENT_LENGTH

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
        ;

        /**
         * 描述
         */
        fun description(): String = when (this)
        {
            SYSTEM        -> "系统通知"
            POST_COMMENT  -> "帖子评论"
            COMMENT_REPLY -> "评论回复"
            LIKE          -> "点赞"
            STAR          -> "收藏"
        }
    }

    val id: NoticeId
    val time: Long
    val type: Type
    val user: UserId
    val read: Boolean
    val content: String

    @Serializable
    data class PostNotice(
        override val id: NoticeId = NoticeId(0),
        override val time: Long = System.currentTimeMillis(),
        override val type: Type,
        override val user: UserId,
        override val read: Boolean = false,
        val post: PostId,
        val count: Long = 1,
        override val content: String = "您收到了${count}条${type.description()}",
    ): Notice
    {
        init
        {
            require(count > 0) { "count must be positive" }
            require(type != Type.SYSTEM) { "type must not be SYSTEM" }
        }

        companion object
        {
            val example = PostNotice(NoticeId(1), System.currentTimeMillis(), Type.POST_COMMENT, UserId(1), false, PostId(1), 1)

            /**
             * 简要, 即只提示有多少条通知
             * @param id 通知ID
             * @param type 通知类型
             * @param time 时间
             * @param user 用户ID
             * @param post 帖子ID
             * @param count 通知数量
             */
            fun brief(id: NoticeId = NoticeId(1), type: Type, time: Long = System.currentTimeMillis(), user: UserId, post: PostId, count: Long): PostNotice
            {
                return PostNotice(id, time, type, user, false, post, count)
            }

            /**
             * 详细, 只表示一条通知, 且会显示内容
             * @param id 通知ID
             * @param type 通知类型
             * @param time 时间
             * @param user 用户ID
             * @param post 帖子ID
             * @param operatorName 操作者(评论/点赞/收藏的用户)
             * @param content 内容, 仅在评论时有效
             */
            fun detail(id: NoticeId = NoticeId(1), type: Type, time: Long = System.currentTimeMillis(), user: UserId, post: PostFullBasicInfo, operatorName: String, content: String?): PostNotice
            {
                val tail =
                    if (content == null) ""
                    else if (content.length > SUB_CONTENT_LENGTH) "：${content.substring(0, SUB_CONTENT_LENGTH)}..."
                    else "：$content"
                val op = when(type)
                {
                    Type.POST_COMMENT  -> "评论了"
                    Type.COMMENT_REPLY -> "回复了"
                    Type.LIKE          -> "点赞了"
                    Type.STAR          -> "收藏了"
                    else               -> ""
                }
                val message = "${operatorName}${op}您的帖子${post.title}$tail"
                return PostNotice(id, time, type, user, false, post.id, 1, message)
            }
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
        override val content: String,
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