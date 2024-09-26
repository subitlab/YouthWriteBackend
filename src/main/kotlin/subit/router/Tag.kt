@file:Suppress("PackageDirectoryMismatch")

package subit.router.tags

import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.PostId
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.dataClasses.Slice
import subit.dataClasses.hasGlobalAdmin
import subit.dataClasses.sliceOf
import subit.database.Posts
import subit.database.Tags
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.statuses

fun Route.tag() = route("/tag", {
    tags("标签")
})
{
    route("/post/{id}", {
        request {
            pathParameter<PostId>("id") {
                required = true
                description = "帖子id"
            }
        }
        response {
            statuses(HttpStatus.NotFound)
        }
    })
    {
        get({
            description = "获取一个帖子的标签列表"

            response {
                statuses<List<String>>(HttpStatus.OK, example = listOf("标签1", "标签2"))
            }
        }) { getPostTags() }

        post({
            description = "为一个帖子添加标签"
            request {
                body<Tag>()
                {
                    required = true
                    description = "要添加的标签"
                }
            }
            response {
                statuses(HttpStatus.OK)
            }
        }) { editPostTag(true) }

        delete({
            description = "删除一个帖子的标签"
            request {
                body<Tag>()
                {
                    required = true
                    description = "要删除的标签"
                }
            }
            response {
                statuses(HttpStatus.OK)
            }
        }) { editPostTag(false) }
    }

    get("/search", {
        request {
            queryParameter<String>("key")
            {
                required = true
                description = "搜索关键字"
            }
            paged()
        }
        response {
            statuses<Slice<String>>(HttpStatus.OK, example = sliceOf("标签1", "标签2"))
        }
    }) { searchTags() }

    get("/all", {
        request {
            paged()
        }
        response {
            statuses<Slice<String>>(HttpStatus.OK, example = sliceOf("标签1", "标签2"))
        }
    }) { getAllTags() }
}

@Serializable
data class Tag(val tag: String)

private suspend fun Context.getPostTags(): Nothing
{
    val pid = call.parameters["id"]?.toPostIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(pid) ?: finishCall(HttpStatus.NotFound)
    withPermission { checkRead(post) }
    val tags = get<Tags>().getPostTags(pid)
    finishCall(HttpStatus.OK, tags)
}

private suspend fun Context.editPostTag(add: Boolean): Nothing
{
    val pid = call.parameters["id"]?.toPostIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(pid) ?: finishCall(HttpStatus.NotFound)
    val loginUser = getLoginUser()
    if (post.author != loginUser?.id && !loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    if (post.parent != null) finishCall(HttpStatus.NotAcceptable.subStatus("不能为评论添加标签"))
    val tag = call.receive<Tag>().tag
    if (tag.isBlank()) finishCall(HttpStatus.BadRequest)
    val success =
        if (add) get<Tags>().addPostTag(pid, tag)
        else get<Tags>().removePostTag(pid, tag)
    if (!success && add) finishCall(HttpStatus.Conflict.subStatus("标签已存在"))
    else if (!success) finishCall(HttpStatus.NotFound.subStatus("标签不存在"))
    finishCall(HttpStatus.OK)
}

private suspend fun Context.searchTags(): Nothing
{
    val key = call.parameters["key"] ?: finishCall(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val tags = get<Tags>().searchTags(key, begin, count)
    finishCall(HttpStatus.OK, tags)
}

private suspend fun Context.getAllTags(): Nothing
{
    val (begin, count) = call.getPage()
    val tags = get<Tags>().getAllTags(begin, count)
    finishCall(HttpStatus.OK, tags)
}