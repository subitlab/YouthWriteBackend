package subit.database.memoryImpl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import org.koin.core.component.KoinComponent
import subit.dataClasses.*
import subit.database.PostVersions
import subit.dataClasses.Slice.Companion.asSlice
import subit.plugin.contentNegotiationJson
import subit.utils.toInstant

class PostVersionsImpl: PostVersions, KoinComponent
{

    private val lock = Mutex()
    internal val list: MutableList<PostVersionInfo> = mutableListOf()
    private val versionMap: MutableMap<PostId, MutableList<PostVersionId>> = hashMapOf()

    override suspend fun createPostVersion(
        post: PostId,
        title: String,
        content: String,
        draft: Boolean
    ): PostVersionId = lock.withLock()
    {
        val postVersionInfo = PostVersionInfo(
            PostVersionId(list.size + 1L),
            post,
            title,
            if (draft) null else content,
            if (!draft) contentNegotiationJson.decodeFromString(content) else null,
            System.currentTimeMillis(),
            draft,
        )
        list += postVersionInfo
        versionMap.getOrDefault(post, mutableListOf()).add(postVersionInfo.id)
        postVersionInfo.id
    }

    override suspend fun getPostVersion(pid: PostVersionId): PostVersionInfo?
    {
        return list.find { it.id == pid }
    }

    override suspend fun getPostVersions(
        post: PostId,
        containsDraft: Boolean,
        begin: Long,
        count: Int
    ): Slice<PostVersionBasicInfo>
    {
        val versions = versionMap[post] ?: return Slice.empty()
        return versions
            .asSequence()
            .filter { containsDraft || !list[it.value.toInt()].draft }
            .asSlice(begin, count)
            .map { list[it.value.toInt()] }
            .map { it.toPostVersionBasicInfo() }
    }

    override suspend fun getLatestPostVersion(post: PostId, containsDraft: Boolean): PostVersionId?
    {
        return versionMap[post]?.lastOrNull { containsDraft || !list[it.value.toInt()].draft }
    }

    internal fun getCreate(post: PostId): Instant
    {
        val version = versionMap[post]?.first { !list[it.value.toInt()].draft } ?: return 0L.toInstant()
        val time = list[version.value.toInt()].time
        return time.toInstant()
    }

    internal fun getLastModified(post: PostId): Instant
    {
        val version = versionMap[post]?.last { !list[it.value.toInt()].draft } ?: return 0L.toInstant()
        val time = list[version.value.toInt()].time
        return time.toInstant()
    }
}