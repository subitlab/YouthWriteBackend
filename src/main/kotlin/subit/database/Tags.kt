package subit.database

import subit.dataClasses.PostId
import subit.dataClasses.Slice

interface Tags
{
    suspend fun getPostTags(pid: PostId): List<String>
    suspend fun removePostTag(pid: PostId, tag: String): Boolean
    suspend fun addPostTag(pid: PostId, tag: String): Boolean
    suspend fun searchTags(key: String, begin: Long, count: Int): Slice<String>
    suspend fun getAllTags(begin: Long, count: Int): Slice<String>
}