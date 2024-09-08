@file:Suppress("PackageDirectoryMismatch")

package subit.router.comment

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.JWTAuth.getLoginUser
import subit.dataClasses.*
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.database.*
import subit.plugin.RateLimit
import subit.router.*
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses

fun Route.comment() = route("/comment", {
    tags = listOf("评论")
})
{
    rateLimit(RateLimit.Post.rateLimitName)
    {
        post("/{postId}", {
            description = "评论一个帖子/回复一个评论"
            request {
                authenticated(true)
                pathParameter<PostId>("postId")
                {
                    required = true
                    description = "帖子id/评论id"
                }
                body<NewComment>
                {
                    description = "评论内容"
                    example("example", NewComment("评论内容", WordMarking(PostId(1), 0, 10), false))
                }
            }
            response {
                statuses<PostId>(HttpStatus.OK, example = PostId(0), bodyDescription = "创建的评论的id")
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }) { commentPost() }
    }

    get("/post/{postId}", {
        description = "获取一个帖子的评论列表(仅包含一级评论, 不包括回复即2~n级评论)"
        request {
            authenticated(false)
            paged()
            pathParameter<PostId>("postId")
            {
                required = true
                description = "帖子id"
            }
            queryParameter<Posts.PostListSort>("sort")
            {
                description = "排序方式"
                required = false
                example(Posts.PostListSort.NEW)
            }
        }
        response {
            statuses<Slice<PostFull>>(HttpStatus.OK, example = sliceOf(PostFull.example))
            statuses(HttpStatus.NotFound)
        }
    }) { getPostComments() }

    get("/comment/{commentId}", {
        description = "获取一个评论的回复列表, 即该评论下的所有回复, 包括2~n级评论"
        request {
            authenticated(false)
            paged()
            pathParameter<PostId>("commentId")
            {
                required = true
                description = "评论id"
            }
            queryParameter<Posts.PostListSort>("sort")
            {
                description = "排序方式"
                required = false
                example(Posts.PostListSort.NEW)
            }
        }
        response {
            statuses<Slice<PostFull>>(HttpStatus.OK, example = sliceOf(PostFull.example))
            statuses(HttpStatus.NotFound)
        }
    }) { getCommentComments() }

    get("/{commentId}", {
        description = "获取一个评论的信息"
        request {
            authenticated(false)
            pathParameter<PostId>("commentId")
            {
                required = true
                description = "评论id"
            }
        }
        response {
            statuses<PostFull>(HttpStatus.OK, example = PostFull.example)
            statuses(HttpStatus.NotFound)
        }
    }) { getComment() }
}

@Serializable
private data class NewComment(val content: String, val wordMarking: WordMarking? = null, val anonymous: Boolean)

@Serializable
private data class WordMarking(val postId: PostId, val start: Int, val end: Int)

private suspend fun Context.commentPost()
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val newComment = receiveAndCheckBody<NewComment>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val posts = get<Posts>()

    val parent = posts.getPostInfo(postId) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkCanComment(parent) }
    val commentId = posts.createPost(parent = postId, author = loginUser.id, block = parent.block, anonymous = newComment.anonymous) ?: return call.respond(HttpStatus.NotFound)
    if (newComment.wordMarking != null)
    {
        val markingPost = posts.getPostFullBasicInfo(newComment.wordMarking.postId) ?: return call.respond(HttpStatus.NotFound)
        get<WordMarkings>().addWordMarking(
            postVersion = markingPost.lastVersionId,
            comment = commentId,
            start = newComment.wordMarking.start,
            end = newComment.wordMarking.end,
            state = WordMarkingState.NORMAL
        )
    }

    if (loginUser.id != parent.author) get<Notices>().createNotice(
        Notice.makeObjectMessage(
            type = if (postId == commentId) Notice.Type.POST_COMMENT else Notice.Type.COMMENT_REPLY,
            user = parent.author,
            obj = postId,
        )
    )

    call.respond(HttpStatus.OK)
}

private suspend fun Context.getPostComments()
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val type = call.parameters["sort"]
                   ?.runCatching { Posts.PostListSort.valueOf(this) }
                   ?.getOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val posts = get<Posts>()
    val post = posts.getPostInfo(postId) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkCanRead(post) }
    val comments = posts.getChildPosts(postId, type, begin, count)
    if (getLoginUser().hasGlobalAdmin())
        call.respond(HttpStatus.OK, comments)
    else
        call.respond(HttpStatus.OK, comments.map { if (it.anonymous) it.copy(author = UserId(0)) else it })
}

private suspend fun Context.getCommentComments()
{
    val commentId = call.parameters["commentId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val type = call.parameters["sort"]
                   ?.runCatching { Posts.PostListSort.valueOf(this) }
                   ?.getOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val posts = get<Posts>()
    val comment = posts.getPostInfo(commentId) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkCanRead(comment) }
    val comments = posts.getDescendants(commentId, type, begin, count)
    if (getLoginUser().hasGlobalAdmin())
        call.respond(HttpStatus.OK, comments)
    else
        call.respond(HttpStatus.OK, comments.map { if (it.anonymous) it.copy(author = UserId(0)) else it })
}

private suspend fun Context.getComment()
{
    val commentId = call.parameters["commentId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val posts = get<Posts>()
    val comment = posts.getPostFull(commentId) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkCanRead(comment.toPostInfo()) }
    call.respond(HttpStatus.OK, if (comment.anonymous) comment.copy(author = UserId(0)) else comment)
}
