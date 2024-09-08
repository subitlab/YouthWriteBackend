package subit.dataClasses

import kotlinx.serialization.Serializable

@Serializable
data class WordMarkingInfo(
    val id: WordMarkingId,
    val postVersion: PostVersionId,
    val comment: PostId,
    val start: Int,
    val end: Int,
    val state: WordMarkingState,
)
{
    companion object
    {
        val example = WordMarkingInfo(
            WordMarkingId(1),
            PostVersionId(1),
            PostId(1),
            0,
            1,
            WordMarkingState.NORMAL
        )
    }
}

@Serializable
enum class WordMarkingState
{
    /**
     * 表示标记位置已经不存在
     */
    DELETED,
    /**
     * 表示正常标记
     */
    NORMAL,
}