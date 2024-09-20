@file:Suppress("PackageDirectoryMismatch")

package subit.router.comment

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.database.*
import subit.plugin.RateLimit
import subit.router.posts.editPostLock
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses
import subit.utils.toEnumOrNull

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
                            content = "评论内容",
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

    get("/list/{postId}", {
        description = "获取一个帖子的评论列表, 即获得所有parent为{postId}的帖子"
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
    }) { getComments(all = false) }

    get("/list/{postId}/all", {
        description = "获取一个帖子的所有评论, 即获得所有parent为{postId}的帖子及其所有后代"
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
    }) { getComments(all = true) }
}

@Serializable
private data class NewComment(
    val content: String,
    val wordMarking: WordMarking? = null,
    val draft: Boolean,
    val anonymous: Boolean
)

@Serializable
private data class WordMarking(val postId: PostId, val start: Int, val end: Int)

private suspend fun Context.commentPost()
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val newComment = receiveAndCheckBody<NewComment>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val posts = get<Posts>()

    val parent = posts.getPostInfo(postId) ?: return call.respond(HttpStatus.NotFound.subStatus("目标帖子不存在"))
    val block = get<Blocks>().getBlock(parent.block) ?: return call.respond(HttpStatus.NotFound.subStatus("目标板块不存在"))
    withPermission {
        checkComment(parent)
        if (newComment.anonymous) checkAnonymous(block)
    }

    val commentId = posts.createPost(
        parent = postId,
        author = loginUser.id,
        block = parent.block,
        anonymous = newComment.anonymous,
        state = State.NORMAL,
    ) ?: return call.respond(HttpStatus.NotFound.subStatus("目标帖子不存在"))

    get<PostVersions>().createPostVersion(
        post = commentId,
        content = newComment.content,
        title = "",
        draft = false,
    )

    if (newComment.wordMarking != null) editPostLock.withLock(newComment.wordMarking.postId)
    {
        val markingPostVersion =
            posts
                .getPostFullBasicInfo(newComment.wordMarking.postId)
                ?.lastVersionId
            ?: return call.respond(HttpStatus.NotFound.subStatus("标记的帖子不存在"))

        get<WordMarkings>().addWordMarking(
            postVersion = markingPostVersion,
            comment = commentId,
            start = newComment.wordMarking.start,
            end = newComment.wordMarking.end,
            state = WordMarkingState.NORMAL
        )
    }

    if (loginUser.id != parent.author) get<Notices>().createNotice(
        Notice.PostNotice(
            type = if (parent.parent == null) Notice.Type.POST_COMMENT else Notice.Type.COMMENT_REPLY,
            user = parent.author,
            post = postId,
        ),
        loginUser.mergeNotice
    )

    call.respond(HttpStatus.OK)
}

private suspend fun Context.getComments(all: Boolean)
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val type = call.parameters["sort"]?.toEnumOrNull<Posts.PostListSort>() ?: Posts.PostListSort.NEW
    val (begin, count) = call.getPage()
    val posts = get<Posts>()
    val post = posts.getPostInfo(postId) ?: return call.respond(HttpStatus.NotFound)
    withPermission { checkRead(post) }
    val comments = if (all) posts.getDescendants(postId, type, begin, count) else posts.getChildPosts(postId, type, begin, count)
    call.respond(HttpStatus.OK, checkAnonymous(comments))
}