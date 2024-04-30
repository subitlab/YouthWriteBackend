@file:Suppress("PackageDirectoryMismatch")
package subit.router.comment

import io.github.smiley4.ktorswaggerui.dsl.delete
import io.github.smiley4.ktorswaggerui.dsl.get
import io.github.smiley4.ktorswaggerui.dsl.post
import io.github.smiley4.ktorswaggerui.dsl.route
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import subit.JWTAuth.getLoginUser
import subit.dataClasses.*
import subit.database.Comments
import subit.database.Posts
import subit.database.checkPermission
import subit.router.Context
import subit.router.authenticated
import subit.router.get
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses

fun Route.comment()
{
    route("/comment", {
        tags = listOf("评论")
    })
    {
        post("/post/{postId}", {
            description = "评论一个帖子"
            request {
                authenticated(true)
                pathParameter<PostId>("postId"){ required = true; description = "帖子id" }
                body<CommentContent> { description = "评论内容"}
            }
            response {
                statuses<CommentIdResponse>(HttpStatus.OK)
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }) { commentPost() }

        post("/comment/{commentId}", {
            description = "评论一个评论"
            request {
                authenticated(true)
                pathParameter<CommentId>("commentId"){ required = true; description = "评论id" }
                body<CommentContent> { description = "评论内容"}
            }
            response {
                statuses<CommentIdResponse>(HttpStatus.OK)
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }) { commentComment() }

        delete("/{commentId}", {
            description = "删除一个评论, 需要板块管理员权限"
            request {
                authenticated(true)
                pathParameter<CommentId>("commentId"){ required = true; description = "评论id" }
            }
            response {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }) { deleteComment() }

        get("/post/{postId}", {
            description = "获取一个帖子的评论列表"
            request {
                authenticated(false)
                pathParameter<PostId>("postId"){ required = true; description = "帖子id" }
            }
            response {
                statuses<List<CommentId>>(HttpStatus.OK)
                statuses(HttpStatus.NotFound)
            }
        }) { getPostComments() }

        get("/comment/{commentId}", {
            description = "获取一个评论的评论列表"
            request {
                authenticated(false)
                pathParameter<CommentId>("commentId"){ required = true; description = "评论id" }
            }
            response {
                statuses<List<CommentId>>(HttpStatus.OK)
                statuses(HttpStatus.NotFound)
            }
        }) { getCommentComments() }

        get("/{commentId}", {
            description = "获取一个评论的信息"
            request {
                authenticated(false)
                pathParameter<CommentId>("commentId"){ description = "评论id" }
            }
            response {
                statuses<Comment>(HttpStatus.OK)
                statuses(HttpStatus.NotFound)
            }
        }) { getComment() }
    }
}

@JvmInline
private value class CommentContent(val content: String)
@JvmInline
private value class CommentIdResponse(val id: CommentId)

private suspend fun Context.commentPost()
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val content = call.receive<CommentContent>().content
    get<Posts>().getPost(postId)?.let { postInfo ->
        checkPermission { checkCanComment(postInfo) }
    } ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)

    get<Comments>().createComment(post = postId, parent = null, author = loginUser.id, content = content)
    ?: return call.respond(HttpStatus.NotFound)
}

private suspend fun Context.commentComment()
{
    val commentId = call.parameters["commentId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val content = call.receive<CommentContent>().content
    get<Comments>().getComment(commentId)?.let { comment ->
        get<Posts>().getPost(comment.post)?.let { postInfo ->
            checkPermission { checkCanComment(postInfo) }
        }
    } ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)

    get<Comments>().createComment(post = null, parent = commentId, author = loginUser.id, content = content)
    ?: return call.respond(HttpStatus.NotFound)
}

private suspend fun Context.deleteComment()
{
    val commentId = call.parameters["commentId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    get<Comments>().getComment(commentId)?.let { comment ->
        get<Posts>().getPost(comment.post)?.let { postInfo ->
            checkPermission { checkCanDelete(postInfo) }
        }
    } ?: return call.respond(HttpStatus.NotFound)
    get<Comments>().setCommentState(commentId, State.DELETED)
}

private suspend fun Context.getPostComments()
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    get<Posts>().getPost(postId)?.let { postInfo ->
        checkPermission { checkCanRead(postInfo) }
    } ?: return call.respond(HttpStatus.NotFound)
    get<Comments>().getComments(post = postId)?.map(Comment::id)?.let { call.respond(it) } ?: call.respond(HttpStatus.NotFound)
}

private suspend fun Context.getCommentComments()
{
    val commentId = call.parameters["commentId"]?.toCommentIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    get<Comments>().getComment(commentId)?.let { comment ->
        get<Posts>().getPost(comment.post)?.let { postInfo ->
            checkPermission { checkCanRead(postInfo) }
        }
    } ?: return call.respond(HttpStatus.NotFound)
    get<Comments>().getComments(parent = commentId)?.map(Comment::id)?.let { call.respond(it) } ?: call.respond(HttpStatus.NotFound)
}

private suspend fun Context.getComment()
{
    val commentId = call.parameters["commentId"]?.toCommentIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val comment = get<Comments>().getComment(commentId) ?: return call.respond(HttpStatus.NotFound)
    get<Posts>().getPost(comment.post)?.let { postInfo ->
        checkPermission { checkCanRead(postInfo) }
    } ?: return call.respond(HttpStatus.NotFound)
    if (comment.state != State.NORMAL) checkPermission { checkHasGlobalAdmin() }
    call.respond(comment)
}
