package subit.dataClasses

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.koin.core.component.KoinComponent
import subit.plugin.contentNegotiation.contentNegotiationJson
import subit.utils.SUB_CONTENT_LENGTH
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

@Serializable
sealed interface IPostFull<P: IPostFull<P, Content>, Content>
{
    val id: PostId
    val title: String?
    val content: Content?
    val author: UserId
    val anonymous: Boolean
    val create: Long?
    val lastModified: Long?
    val lastVersionId: PostVersionId?
    val view: Long
    val block: BlockId
    val top: Boolean
    val state: State
    val like: Long
    val star: Long
    val comment: Long
    val parent: PostId?
    val root: PostId?
    val hotScore: Double
    @Suppress("UNCHECKED_CAST")
    fun copy(
        id: PostId = this.id,
        title: String? = this.title,
        author: UserId = this.author,
        anonymous: Boolean = this.anonymous,
        create: Long? = this.create,
        lastModified: Long? = this.lastModified,
        lastVersionId: PostVersionId? = this.lastVersionId,
        view: Long = this.view,
        block: BlockId = this.block,
        top: Boolean = this.top,
        state: State = this.state,
        like: Long = this.like,
        star: Long = this.star,
        comment: Long = this.comment,
        parent: PostId? = this.parent,
        root: PostId? = this.root,
        hotScore: Double = this.hotScore,
    ): P = when (this)
    {
        is PostFull -> PostFull(
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
        is PostFullBasicInfo -> PostFullBasicInfo(
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
    } as P
}

/**
 * 完整帖子信息, 包含由[PostInfo]的信息和点赞数, 点踩数, 收藏数
 */
@Serializable
data class PostFull(
    override val id: PostId,
    override val title: String?,
    override val content: JsonElement?,
    override val author: UserId,
    override val anonymous: Boolean,
    override val create: Long?,
    override val lastModified: Long?,
    override val lastVersionId: PostVersionId?,
    override val view: Long,
    override val block: BlockId,
    override val top: Boolean,
    override val state: State,
    override val like: Long,
    override val star: Long,
    override val comment: Long,
    override val parent: PostId?,
    override val root: PostId?,
    override val hotScore: Double,
): IPostFull<PostFull, JsonElement>
{
    fun toPostInfo(): PostInfo =
        PostInfo(id, author, anonymous, view, block, top, state, parent, root)

    fun toPostFullBasicInfo(): PostFullBasicInfo =
        PostFullBasicInfo(
            id,
            title,
            content?.let { getContentText(it, SUB_CONTENT_LENGTH) },
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
    override val id: PostId,
    override val title: String?,
    val subContent: String?,
    override val author: UserId,
    override val anonymous: Boolean,
    override val create: Long?,
    override val lastModified: Long?,
    override val lastVersionId: PostVersionId?,
    override val view: Long,
    override val block: BlockId,
    override val top: Boolean,
    override val state: State,
    override val like: Long,
    override val star: Long,
    override val comment: Long,
    override val parent: PostId?,
    override val root: PostId?,
    override val hotScore: Double,
): IPostFull<PostFullBasicInfo, String>
{
    companion object
    {
        val example = PostFull.example.toPostFullBasicInfo()
    }

    override val content: String?
        get() = subContent

    fun toPostInfo(): PostInfo =
        PostInfo(id, author, anonymous, view, block, top, state, parent, root)
}