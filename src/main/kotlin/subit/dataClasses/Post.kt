package subit.dataClasses

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.koin.core.component.KoinComponent
import subit.plugin.contentNegotiation.contentNegotiationJson
import subit.utils.getContentText

@Serializable
data class PostVersionInfo(
    val id: PostVersionId,
    val post: PostId,
    val title: String,
    val content: JsonElement,
    val time: Long,
    val draft: Boolean,
)
{
    companion object
    {
        val example = PostVersionInfo(
            PostVersionId(1),
            PostId(1),
            "标题",
            contentNegotiationJson.parseToJsonElement("""
                [
                  {
                    "id":"ff6br",
                    "children":[
                      {
                        "text":"1"
                      }
                    ],
                    "type":"p"
                  }
                ]""".trimIndent()),
            System.currentTimeMillis(),
            false,
        )
    }

    fun toPostVersionBasicInfo(): PostVersionBasicInfo =
        PostVersionBasicInfo(id, post, title, time, draft)
}

@Serializable
data class PostVersionBasicInfo(
    val id: PostVersionId,
    val post: PostId,
    val title: String,
    val time: Long,
    val draft: Boolean,
)
{
    companion object
    {
        val example = PostVersionInfo.example.toPostVersionBasicInfo()
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
    val top: Boolean,
    val state: State,
    val parent: PostId?,
    val root: PostId?
)
{
    companion object: KoinComponent
    {
        val example = PostFull.example.toPostInfo()
    }

    fun toPostFull(
        title: String,
        content: JsonElement,
        create: Long,
        lastModified: Long,
        lastVersionId: PostVersionId,
        like: Long,
        star: Long,
        comment: Long,
        hotScore: Double,
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
            top,
            state,
            like,
            star,
            comment,
            parent,
            root,
            hotScore,
        )
}

/**
 * 完整帖子信息, 包含由[PostInfo]的信息和点赞数, 点踩数, 收藏数
 */
@Serializable
data class PostFull(
    val id: PostId,
    val title: String?,
    val content: JsonElement?,
    val author: UserId,
    val anonymous: Boolean,
    val create: Long?,
    val lastModified: Long?,
    val lastVersionId: PostVersionId?,
    val view: Long,
    val block: BlockId,
    val top: Boolean,
    val state: State,
    val like: Long,
    val star: Long,
    val comment: Long,
    val parent: PostId?,
    val root: PostId?,
    val hotScore: Double,
)
{
    fun toPostInfo(): PostInfo =
        PostInfo(id, author, anonymous, view, block, top, state, parent, root)

    fun toPostFullBasicInfo(): PostFullBasicInfo =
        PostFullBasicInfo(
            id,
            title,
            content?.let { getContentText(it) },
            author,
            anonymous,
            create,
            lastModified,
            lastVersionId,
            view,
            block,
            top,
            state,
            like,
            star,
            comment,
            parent,
            root,
            hotScore,
        )

    companion object
    {
        val example = PostFull(
            PostId(1),
            "帖子标题",
            PostVersionInfo.example.content,
            UserId(1),
            false,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            PostVersionId(0),
            0,
            BlockId(1),
            false,
            State.NORMAL,
            0,
            0,
            0,
            PostId(1),
            PostId(1),
            1.0,
        )
    }
}

@Serializable
data class PostFullBasicInfo(
    val id: PostId,
    val title: String?,
    val subContent: String?,
    val author: UserId,
    val anonymous: Boolean,
    val create: Long?,
    val lastModified: Long?,
    val lastVersionId: PostVersionId?,
    val view: Long,
    val block: BlockId,
    val top: Boolean,
    val state: State,
    val like: Long,
    val star: Long,
    val comment: Long,
    val parent: PostId?,
    val root: PostId?,
    val hotScore: Double,
)
{
    companion object
    {
        val example = PostFull.example.toPostFullBasicInfo()
    }

    fun toPostInfo(): PostInfo =
        PostInfo(id, author, anonymous, view, block, top, state, parent, root)
}