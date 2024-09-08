package subit.dataClasses

import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent

@Serializable
data class PostVersionInfo(
    val id: PostVersionId,
    val post: PostId,
    val title: String,
    val content: String,
    val time: Long,
)
{
    companion object
    {
        val example = PostVersionInfo(
            PostVersionId(1),
            PostId(1),
            "标题",
            "内容",
            System.currentTimeMillis()
        )
    }

    fun toPostVersionBasicInfo(): PostVersionBasicInfo =
        PostVersionBasicInfo(id, post, title, time)
}

@Serializable
data class PostVersionBasicInfo(
    val id: PostVersionId,
    val post: PostId,
    val title: String,
    val time: Long,
)
{
    companion object
    {
        val example = PostVersionBasicInfo(
            PostVersionId(1),
            PostId(1),
            "标题",
            System.currentTimeMillis()
        )
    }
}

/**
 * 帖子信息
 * @property id 帖子ID
 * @property author 帖子作者
 * @property anonymous 此贴作者匿名
 * @property view 帖子浏览量
 * @property block 帖子所属板块
 * @property state 帖子当前状态
 */
@Serializable
data class PostInfo(
    val id: PostId,
    val author: UserId,
    val anonymous: Boolean,
    val view: Long,
    val block: BlockId,
    val state: State,
    val parent: PostId?,
    val root: PostId?
)
{
    companion object: KoinComponent
    {
        val example = PostInfo(
            PostId(1),
            UserId(1),
            false,
            0,
            BlockId(1),
            State.NORMAL,
            PostId(1),
            PostId(1)
        )
    }

    fun toPostFull(
        title: String,
        content: String,
        create: Long,
        lastModified: Long,
        lastVersionId: PostVersionId,
        like: Long,
        star: Long,
    ): PostFull =
        PostFull(
            id,
            title,
            content,
            author,
            anonymous,
            create,
            lastModified,
            lastVersionId,
            view,
            block,
            state,
            like,
            star,
            parent,
            root
        )
}

/**
 * 完整帖子信息, 包含由[PostInfo]的信息和点赞数, 点踩数, 收藏数
 */
@Serializable
data class PostFull(
    val id: PostId,
    val title: String,
    val content: String,
    val author: UserId,
    val anonymous: Boolean,
    val create: Long,
    val lastModified: Long,
    val lastVersionId: PostVersionId,
    val view: Long,
    val block: BlockId,
    val state: State,
    val like: Long,
    val star: Long,
    val parent: PostId?,
    val root: PostId?
)
{
    fun toPostInfo(): PostInfo =
        PostInfo(id, author, anonymous, create, block, state, parent, root)

    fun toPostFullBasicInfo(): PostFullBasicInfo =
        PostFullBasicInfo(
            id,
            title,
            author,
            anonymous,
            create,
            lastModified,
            lastVersionId,
            view,
            block,
            state,
            like,
            star,
            parent,
            root
        )

    companion object
    {
        val example = PostFull(
            PostId(1),
            "帖子标题",
            "帖子内容",
            UserId(1),
            false,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            PostVersionId(0),
            0,
            BlockId(1),
            State.NORMAL,
            0,
            0,
            PostId(1),
            PostId(1)
        )
    }
}

@Serializable
data class PostFullBasicInfo(
    val id: PostId,
    val title: String,
    val author: UserId,
    val anonymous: Boolean,
    val create: Long,
    val lastModified: Long,
    val lastVersionId: PostVersionId,
    val view: Long,
    val block: BlockId,
    val state: State,
    val like: Long,
    val star: Long,
    val parent: PostId?,
    val root: PostId?
)
{
    companion object
    {
        val example = PostFullBasicInfo(
            PostId(1),
            "帖子标题",
            UserId(1),
            false,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            PostVersionId(0),
            0,
            BlockId(1),
            State.NORMAL,
            0,
            0,
            PostId(1),
            PostId(1)
        )
    }
}