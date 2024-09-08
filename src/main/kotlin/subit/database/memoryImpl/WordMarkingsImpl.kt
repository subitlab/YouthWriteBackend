package subit.database.memoryImpl

import subit.dataClasses.*
import subit.dataClasses.WordMarkingId.Companion.toWordMarkingId
import subit.database.WordMarkings
import java.util.Collections

class WordMarkingsImpl: WordMarkings
{
    private val map = Collections.synchronizedMap(hashMapOf<WordMarkingId, WordMarkingInfo>())
    private val map1 = Collections.synchronizedMap(hashMapOf<PostVersionId, MutableList<WordMarkingId>>())

    override suspend fun getWordMarking(wid: WordMarkingId): WordMarkingInfo? = map[wid]
    override suspend fun getWordMarking(postVersion: PostVersionId, comment: PostId): WordMarkingInfo?
    {
        val list = map1[postVersion] ?: return null
        return list.mapNotNull { map[it] }.firstOrNull { it.comment == comment }
    }

    override suspend fun getWordMarkings(postVersion: PostVersionId): List<WordMarkingInfo> =
        map1[postVersion]?.mapNotNull { map[it] } ?: emptyList()

    override suspend fun addWordMarking(
        postVersion: PostVersionId,
        comment: PostId,
        start: Int,
        end: Int,
        state: WordMarkingState
    ): WordMarkingId
    {
        val id = (map.size + 1).toWordMarkingId()
        map[id] = WordMarkingInfo(id, postVersion, comment, start, end, state)
        map1.getOrPut(postVersion) { mutableListOf() }.add(id)
        return id
    }
}