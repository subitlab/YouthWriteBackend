package subit.database.memoryImpl

import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.Slice.Companion.asSlice
import subit.database.Tags

class TagsImpl: Tags
{
    val tags = mutableMapOf<PostId, MutableSet<String>>()

    override suspend fun getPostTags(pid: PostId): List<String> = tags[pid]?.toList() ?: emptyList()

    override suspend fun removePostTag(pid: PostId, tag: String): Boolean
    {
        return tags[pid]?.remove(tag) ?: false
    }

    override suspend fun addPostTag(pid: PostId, tag: String): Boolean
    {
        return tags.getOrPut(pid) { mutableSetOf() }.add(tag)
    }

    override suspend fun searchTags(key: String, begin: Long, count: Int): Slice<String> =
        tags.values.flatten().filter { it.contains(key) }.toSortedSet().asSequence().asSlice(begin, count)

    override suspend fun getAllTags(begin: Long, count: Int): Slice<String> =
        tags.values.flatten().toSortedSet().asSequence().asSlice(begin, count)
}