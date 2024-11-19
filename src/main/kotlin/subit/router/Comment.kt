@file:Suppress("PackageDirectoryMismatch")

package subit.router.comment

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import subit.dataClasses.*
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.database.*
import subit.plugin.rateLimit.RateLimit
import subit.router.posts.editPostLock
import subit.router.utils.*
import subit.utils.*

fun Route.comment() = route("/comment", {
    tags = listOf("评论")
})
{
    rateLimit(RateLimit.Post.rateLimitName)
    {
        post("/{postId}", {
            description = "评论一个帖子"
            request {
                pathParameter<PostId>("postId")
                {
                    required = true
                    description = "帖子id/评论id"
                }
                body<NewComment>
                {
                    description = "评论内容"
                    example(
                        "example",
                        NewComment(
                            content = PostVersionInfo.example.content,
                            wordMarking = WordMarking(PostId(1), 0, 10),
                            draft = false,
                            anonymous = false
                        )
                    )
                }
            }
            response {
                statuses<PostId>(HttpStatus.OK, example = PostId(0), bodyDescription = "创建的评论的id")
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }) { commentPost() }
    }

    route("/list/{postId}", {
        request {
            paged()
            pathParameter<PostId>("postId")
            {
                required = true
                description = "帖子id"
            }
            queryParameter<Posts.PostListSort>("sort")
            {
                description = "排序方式"
                required = true
                example(Posts.PostListSort.NEW)
            }
        }
        response {
            statuses<Slice<PostFull>>(HttpStatus.OK, example = sliceOf(PostFull.example))
            statuses(HttpStatus.NotFound)
        }
    })
    {
        get("", {
            description = "获取一个帖子的评论列表, 即获得所有parent为{postId}的帖子"

        }) { getComments(all = false) }

        get("/all", {
            description = "获取一个帖子的所有评论, 即获得所有parent为{postId}的帖子及其所有后代"
        }) { getComments(all = true) }
    }
}

@Serializable
private data class NewComment(
    val content: JsonElement,
    val wordMarking: WordMarking? = null,
    val draft: Boolean,
    val anonymous: Boolean
)

@Serializable
private data class WordMarking(val postId: PostId, val start: Int, val end: Int)

private suspend fun Context.commentPost()
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val newComment = call.receiveAndCheckBody<NewComment>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val posts = get<Posts>()

    if (getContentText(newComment.content).isBlank())
        return call.respond(HttpStatus.BadRequest.subStatus("评论内容不能为空"))

    val parent = posts.getPostFullBasicInfo(postId) ?: return call.respond(HttpStatus.NotFound.subStatus("目标帖子不存在"))
    val block = get<Blocks>().getBlock(parent.block) ?: return call.respond(HttpStatus.NotFound.subStatus("目标板块不存在"))

    val markingPost = newComment.wordMarking?.let { posts.getPostFullBasicInfo(it.postId) }
    val markingPostVersion = markingPost?.lastVersionId
    checkPermission {
        checkComment(parent.toPostInfo())
        if (markingPost != null) checkComment(markingPost.toPostInfo())
        if (newComment.anonymous) checkAnonymous(block)
    }

    if (newComment.wordMarking != null && markingPostVersion == null)
        return call.respond(HttpStatus.NotFound.subStatus("标记的帖子不存在"))

    val commentId = posts.createPost(
        parent = postId,
        author = loginUser.id,
        block = parent.block,
        anonymous = newComment.anonymous,
        state = State.NORMAL,
    ) ?: return call.respond(HttpStatus.NotFound.subStatus("目标帖子不存在"))

    get<PostVersions>().createPostVersion(
        post = commentId,
        content = if (newComment.draft) newComment.content else clearAndMerge(newComment.content),
        title = "",
        draft = newComment.draft,
    )

    if (markingPostVersion != null) editPostLock.withLock(markingPost.id)
    {
        get<WordMarkings>().addWordMarking(
            postVersion = markingPostVersion,
            comment = commentId,
            start = newComment.wordMarking.start,
            end = newComment.wordMarking.end,
            state = WordMarkingState.NORMAL
        )
    }

    if (loginUser.id != parent.author)
    {
        val notices = get<Notices>()
        val notice =
            Notice.PostNotice.build(
                user = parent.author,
                type = if (parent.parent == null) Notice.Type.POST_COMMENT else Notice.Type.COMMENT_REPLY,
                post = parent.id,
                operator = loginUser,
                comment = getContentText(newComment.content, SUB_CONTENT_LENGTH),
            )
        notices.createNotice(notice)
    }

    call.respond(HttpStatus.OK)
}

private suspend fun Context.getComments(all: Boolean)
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val type = call.parameters["sort"].decodeOrElse { Posts.PostListSort.NEW }
    val (begin, count) = call.getPage()
    val posts = get<Posts>()
    val post = posts.getPostInfo(postId) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkRead(post) }
    val comments =
        if (all) posts.getDescendants(postId, type, begin, count)
        else posts.getChildPosts(postId, type, begin, count)
    val wordMarkings = get<WordMarkings>()
    val res = comments.map {
        if (it.lastVersionId == null || it.content == null) return@map it
        it.copy(content = withWordMarkings(it.content, wordMarkings.getWordMarkings(it.lastVersionId)))
    }
    call.respond(HttpStatus.OK, checkAnonymous(res))
}