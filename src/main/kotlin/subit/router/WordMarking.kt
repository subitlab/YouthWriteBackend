@file:Suppress("PackageDirectoryMismatch")

package subit.router.wordMarkings

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.application.*
import io.ktor.server.routing.*
import subit.dataClasses.PostId
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.dataClasses.PostVersionId
import subit.dataClasses.PostVersionId.Companion.toPostVersionIdOrNull
import subit.dataClasses.WordMarkingId
import subit.dataClasses.WordMarkingInfo
import subit.database.PostVersions
import subit.database.Posts
import subit.database.WordMarkings
import subit.router.utils.Context
import subit.router.utils.get
import subit.router.utils.withPermission
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses

fun Route.wordMarking() = route("/wordMarking",{
    tags = listOf("划词评论")
    description = "划词评论接口"
})
{
    get("/list/{postVersionId}", {
        description = "获取一个帖子版本的划词评论列表"
        request {
            pathParameter<PostVersionId>("postVersionId")
            {
                required = true
                description = "帖子版本id"
            }
        }
        response {
            statuses<List<WordMarkingInfo>>(HttpStatus.OK, example = listOf(WordMarkingInfo.example))
        }
    }) { getWordMarkings() }

    get("/comment/{commentId}", {
        description = "获取一个评论对于某一个文章版本的划词评论"
        request {
            pathParameter<PostId>("commentId")
            {
                required = true
                description = "划词评论id"
            }
            queryParameter<PostVersionId>("postVersionId")
            {
                required = true
                description = "帖子版本id"
            }
        }
        response {
            statuses<WordMarkingInfo>(HttpStatus.OK, example = WordMarkingInfo.example)
            statuses(HttpStatus.NotFound)
        }
    }) { getWordMarking() }

    get("/{id}",{
        description = "获取一个划词评论"
        request {
            pathParameter<WordMarkingId>("id")
            {
                required = true
                description = "划词评论id"
            }
        }
        response {
            statuses<WordMarkingInfo>(HttpStatus.OK, example = WordMarkingInfo.example)
            statuses(HttpStatus.NotFound)
        }
    }) { getWordMarkingById() }
}

suspend fun Context.getWordMarkings()
{
    val postVersionId = call.parameters["postVersionId"]?.toPostVersionIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val postVersions = get<PostVersions>()
    val postVersion = postVersions.getPostVersion(postVersionId) ?: return call.respond(HttpStatus.NotFound)
    val post = get<Posts>().getPostInfo(postVersion.post) ?: return call.respond(HttpStatus.NotFound)
    withPermission { checkRead(post) }
    val wordMarkings = get<WordMarkings>().getWordMarkings(postVersionId)
    return call.respond(HttpStatus.OK, wordMarkings)
}

suspend fun Context.getWordMarking()
{
    val commentId = call.parameters["commentId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val postVersionId = call.parameters["postVersionId"]?.toPostVersionIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val postVersions = get<PostVersions>()
    val postVersion = postVersions.getPostVersion(postVersionId) ?: return call.respond(HttpStatus.NotFound)
    val post = get<Posts>().getPostInfo(postVersion.post) ?: return call.respond(HttpStatus.NotFound)
    withPermission { checkRead(post) }
    val wordMarking = get<WordMarkings>().getWordMarking(postVersionId, commentId) ?: return call.respond(HttpStatus.NotFound)
    return call.respond(HttpStatus.OK, wordMarking)
}

suspend fun Context.getWordMarkingById()
{
    val id = call.parameters["id"]?.toLongOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val wordMarking = get<WordMarkings>().getWordMarking(WordMarkingId(id)) ?: return call.respond(HttpStatus.NotFound)
    val postVersion = get<PostVersions>().getPostVersion(wordMarking.postVersion) ?: return call.respond(HttpStatus.NotFound)
    val post = get<Posts>().getPostInfo(postVersion.post) ?: return call.respond(HttpStatus.NotFound)
    withPermission { checkRead(post) }
    return call.respond(HttpStatus.OK, wordMarking)
}