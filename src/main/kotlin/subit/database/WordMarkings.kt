package subit.database

import subit.dataClasses.*

interface WordMarkings
{
    suspend fun getWordMarking(wid: WordMarkingId): WordMarkingInfo?
    suspend fun getWordMarking(postVersion: PostVersionId, comment: PostId): WordMarkingInfo?
    suspend fun getWordMarkings(postVersion: PostVersionId): List<WordMarkingInfo>
    suspend fun addWordMarking(
        postVersion: PostVersionId,
        comment: PostId,
        start: Int,
        end: Int,
        state: WordMarkingState = WordMarkingState.NORMAL
    ): WordMarkingId

    suspend fun batchAddWordMarking(wordMarkings: List<WordMarkingInfo>): List<WordMarkingId> =
        wordMarkings.map { addWordMarking(it.postVersion, it.comment, it.start, it.end, it.state) }
}