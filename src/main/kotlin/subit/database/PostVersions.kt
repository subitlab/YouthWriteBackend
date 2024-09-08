package subit.database

import subit.dataClasses.*

interface PostVersions
{
    /**
     * 创建新的帖子版本, 时间为当前时间.
     * @param post 帖子ID
     * @param title 标题
     * @param content 内容
     * @return 帖子版本ID
     */
    suspend fun createPostVersion(
        post: PostId,
        title: String,
        content: String
    ): PostVersionId

    /**
     * 获取帖子版本信息
     * @param pid 帖子版本ID
     * @return 帖子版本信息, 不存在返回null
     */
    suspend fun getPostVersion(pid: PostVersionId): PostVersionInfo?

    /**
     * 获取帖子的所有版本, 按时间倒序排列, 即最新的版本在前.
     * @param post 帖子ID
     * @return 帖子版本ID列表
     */
    suspend fun getPostVersions(post: PostId, begin: Long, count: Int): Slice<PostVersionBasicInfo>

    /**
     * 获取最新的帖子版本
     */
    suspend fun getLatestPostVersion(post: PostId): PostVersionId?
}