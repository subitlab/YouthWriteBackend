package subit.dataClasses

import kotlinx.serialization.Serializable

@Serializable
enum class TagType {
    NORMAL, CLASS
}


@Serializable
data class Tag(
    val id: TagId,
    val name: String,
    val description: String,
    val type: TagType,
    val parent: TagId? = null
)
{
    companion object {
        val example = Tag(TagId(1), "标签1","这是标签1", TagType.NORMAL)
    }
}