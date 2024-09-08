package subit.database.memoryImpl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.database.PostVersions
import subit.database.Posts
import subit.dataClasses.Slice.Companion.asSlice
import subit.utils.toInstant

class PostVersionsImpl: PostVersions, KoinComponent
{
    private val posts: Posts by inject()

    private val lock = Mutex()
    internal val list: MutableList<PostVersionInfo> = mutableListOf()
    private val versionMap: MutableMap<PostId, MutableList<PostVersionId>> = hashMapOf()

    override suspend fun createPostVersion(post: PostId, title: String, content: String): PostVersionId = lock.withLock()
    {
        val postVersionInfo = PostVersionInfo(
            PostVersionId(list.size + 1L),
            post,
            title,
            content,
            System.currentTimeMillis()
        )
        list += postVersionInfo
        versionMap.getOrDefault(post, mutableListOf()).add(postVersionInfo.id)
        postVersionInfo.id
    }

    override suspend fun getPostVersion(pid: PostVersionId): PostVersionInfo?
    {
        return list.find { it.id == pid }
    }

    override suspend fun getPostVersions(post: PostId, begin: Long, count: Int): Slice<PostVersionBasicInfo>
    {
        val versions = versionMap[post] ?: return Slice.empty()
        return versions
            .asSequence()
            .asSlice(begin, count)
            .map { list[it.value.toInt()] }
            .map { it.toPostVersionBasicInfo() }
    }

    override suspend fun getLatestPostVersion(post: PostId): PostVersionId?
    {
        return versionMap[post]?.lastOrNull()
    }

    internal fun getCreate(post: PostId): Instant
    {
        val version = versionMap[post]?.first() ?: return 0L.toInstant()
        val time = list[version.value.toInt()].time
        return time.toInstant()
    }

    internal fun getLastModified(post: PostId): Instant
    {
        val version = versionMap[post]?.last() ?: return 0L.toInstant()
        val time = list[version.value.toInt()].time
        return time.toInstant()
    }
}