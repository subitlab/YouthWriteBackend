package subit.dataClasses

import kotlinx.serialization.Serializable

@Serializable
data class PostAuthorization(
    val from: UserId,
    val to: UserId,
    val post: PostId,
    val time: Long
)
{
    companion object
    {
        val example = PostAuthorization(
            UserId(1),
            UserId(2),
            PostId(1),
            System.currentTimeMillis()
        )
    }
}