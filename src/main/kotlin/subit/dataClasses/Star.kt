package subit.dataClasses

import kotlinx.serialization.Serializable

/**
 * 表示一个用户的收藏或点赞
 */
@Serializable
sealed interface UserAction
{
    val user: UserId
    val post: PostId
    val time: Long
}

/**
 * 收藏信息
 * @property user 收藏用户
 * @property post 收藏帖子
 * @property time 收藏时间
 */
@Serializable
data class Star(
    override val user: UserId,
    override val post: PostId,
    override val time: Long
): UserAction
{
    companion object
    {
        val example = Star(
            UserId(1),
            PostId(1),
            System.currentTimeMillis()
        )
    }
}

@Serializable
data class Like(
    override val user: UserId,
    override val post: PostId,
    override val time: Long
): UserAction
{
    companion object
    {
        val example = Like(
            UserId(1),
            PostId(1),
            System.currentTimeMillis()
        )
    }
}