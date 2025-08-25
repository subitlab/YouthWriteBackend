@file:Suppress("PackageDirectoryMismatch")

package subit.router.tags

import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.PostId
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.dataClasses.Slice
import subit.dataClasses.Tag
import subit.dataClasses.TagId
import subit.dataClasses.TagId.Companion.toTagIdOrNull
import subit.dataClasses.TagType
import subit.dataClasses.hasGlobalAdmin
import subit.dataClasses.sliceOf
import subit.database.Posts
import subit.database.TagRelations
import subit.database.Tags
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.decodeOrNull
import subit.utils.statuses

fun Route.tag() = route("/tag", {
    tags("标签")
})
{
    get( "/{id}", {
        request {
            pathParameter<TagId>("id") {
                required = true
                description = "标签id"
            }
        }
        response {
            statuses<Tag>(HttpStatus.OK, example = Tag.example)
            statuses(HttpStatus.NotFound)
        }
    }) { getTagById() }
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
        get( {
            description = "获取一个帖子的标签列表"

            response {
                statuses<Slice<Tag>>(HttpStatus.OK, example = sliceOf(Tag.example))
            }
        }) { getPostTags() }

        post({
            description = "为一个帖子添加标签, 需要是作者或全局管理员"
            request {
                body<PostTag>()
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
            description = "删除一个帖子的标签, 需要是作者或全局管理员"
            request {
                body<PostTag>()
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

    post({
        description = "创建标签, 需要全局管理员"
        request {
            body<AddTagBody>()
            {
                required = true
                description = "标签信息"
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { addTag() }

    delete({
        description = "删除标签, 需要全局管理员"
        request {
            body<DeleteTagBody>()
            {
                required = true
                description = "要删除的标签"
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { deleteTag() }

    put({
        description = "根据名称和种类修改标签, 需要全局管理员"
        request {
            body<AddTagBody>()
            {
                required = true
                description = "要修改的标签信息"
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { editTag() }

    get("/search", {
        request {
            queryParameter<String>("key")
            {
                required = true
                allowEmptyValue = true
                description = "搜索关键字"
            }
            queryParameter<TagType>("type")
            {
                required = true
                description = "标签类型, NORMAL: 普通标签, CLASS: 课程标签"
            }
            paged()
        }
        response {
            statuses<Slice<Tag>>(HttpStatus.OK, example = sliceOf(Tag.example))
        }
    }) { searchTags() }

    get("/all", {
        request {
            queryParameter<TagType>("type")
            {
                required = true
                description = "标签类型, NORMAL: 普通标签, CLASS: 课程标签"
            }
            paged()
        }
        response {
            statuses<Slice<Tag>>(HttpStatus.OK, example = sliceOf(Tag.example))
        }
    }) { getAllTags() }
}

private suspend fun Context.getTagById(): Nothing
{
    val id = call.parameters["id"]?.toTagIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val tag = get<Tags>().getTagById(id) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, tag)
}

@Serializable
data class AddTagBody(val name: String, val description: String, val type: TagType)

@Serializable
data class DeleteTagBody(val name: String, val type: TagType)

@Serializable
data class PostTag(val id: TagId)

private suspend fun Context.getPostTags(): Nothing
{
    val pid = call.parameters["id"]?.toPostIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(pid) ?: finishCall(HttpStatus.NotFound)
    checkPermission { checkRead(post) }
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

    val tag = call.receiveAndCheckBody<PostTag>().id

    val success =
        if (add) get<TagRelations>().addPostTag(pid, tag)
        else get<TagRelations>().removePostTag(pid, tag)

    if (!success && add) finishCall(HttpStatus.Conflict.subStatus("标签已存在"))
    else if (!success) finishCall(HttpStatus.NotFound.subStatus("标签不存在"))

    finishCall(HttpStatus.OK)
}

private suspend fun Context.addTag(): Nothing
{
    checkPermission {
        checkHasGlobalAdmin()
    }

    val body = call.receiveAndCheckBody<AddTagBody>()
    val name = body.name
    val type = body.type
    val description = body.description

    // 标签不能包含空格
    if (name.any { it.isWhitespace() }) finishCall(HttpStatus.BadRequest.subStatus("标签不能包含空格"))
    if (name.isBlank()) finishCall(HttpStatus.BadRequest.subStatus("标签不能为空"))
    if (name.length > 100) finishCall(HttpStatus.BadRequest.subStatus("标签长度不能超过100个字符"))

    if (get<Tags>().hasTag(name, type)) finishCall(HttpStatus.Conflict.subStatus("标签已存在"))

    get<Tags>().addTag(name, type, description)

    finishCall(HttpStatus.OK)
}

private suspend fun Context.deleteTag(): Nothing
{
    checkPermission {
        checkHasGlobalAdmin()
    }

    val body = call.receiveAndCheckBody<DeleteTagBody>()
    val name = body.name
    val type = body.type

    if (!get<Tags>().removeTag(name, type)) finishCall(HttpStatus.NotFound.subStatus("标签不存在"))

    finishCall(HttpStatus.OK)
}

private suspend fun Context.editTag(): Nothing
{
    checkPermission {
        checkHasGlobalAdmin()
    }

    val body = call.receiveAndCheckBody<AddTagBody>()
    val name = body.name
    val type = body.type
    val description = body.description

    if (!get<Tags>().editTag(name, type, description)) finishCall(HttpStatus.NotFound.subStatus("标签不存在"))

    finishCall(HttpStatus.OK)
}

private suspend fun Context.searchTags(): Nothing
{
    val key = call.parameters["key"] ?: finishCall(HttpStatus.BadRequest)
    val type = call.parameters["type"].decodeOrNull<TagType>() ?: finishCall(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val tags = get<Tags>().searchTags(key, type, begin, count)
    finishCall(HttpStatus.OK, tags)
}

private suspend fun Context.getAllTags(): Nothing
{
    val (begin, count) = call.getPage()
    val type = call.parameters["type"].decodeOrNull<TagType>() ?: finishCall(HttpStatus.BadRequest)
    val tags = get<Tags>().getAllTags(type, begin, count)
    finishCall(HttpStatus.OK, tags)
}