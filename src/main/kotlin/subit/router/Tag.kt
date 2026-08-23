@file:Suppress("PackageDirectoryMismatch")

package subit.router.tags

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.dataClasses.TagId.Companion.toTagIdOrNull
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

    route("/{id}", {
        request {
            pathParameter<TagId>("id") {
                required = true
                description = "标签id"
            }
        }
    }) {
        delete( {
            description = "删除标签, 需要全局管理员"
            response {
                statuses(HttpStatus.OK, HttpStatus.NotFound)
            }
        } ) { deleteTagById()  }

        put({
            description = "修改标签, 需要全局管理员"
            request {
                body<EditTagBody>()
                {
                    required = true
                    description = "要修改的标签信息"
                }
            }
            response {
                statuses(HttpStatus.OK)
            }
        }) { editTag() }

        put ( "/folder", {
            description = "设置标签的文件夹, 需要全局管理员"
            request {
                queryParameter<TagId>("folder") {
                    description = "文件夹id, 不传表示无文件夹，文件夹类型需要与tag相同"
                }
            }
            response {
                statuses(HttpStatus.OK, HttpStatus.BadRequest, HttpStatus.NotFound)
            }
        }) { setTagFolder() }
    }

    route ("/list", {
        description = "获取标签列表"
        request {
            queryParameter<Tag>("type")
            {
                required = true
                description = "标签类型, NORMAL: 普通标签, CLASS: 课程标签"
            }
            queryParameter<String>("key")
            {
                description = "要求包含某一关键字"
                required = false
            }
            queryParameter<TagId>("childOf")
            {
                description = "获取某一folder的子tag, 填0表示获取根folder们, 不能和descendantOf同时填写"
                required = false
            }
            queryParameter<TagId>("descendantOf")
            {
                description = "获取某一folder的所有子孙tag, 包括自己, 不能和childOf同时填写"
                required = false
            }
        }
    }) {
        get("/flat",{
            description = "获得板块列表, 展平输出"
            request {
                paged()
            }
            response {
                statuses<Slice<Tag>>(HttpStatus.OK, example = sliceOf(Tag.example))
            }
        }) { getTagsList(false) }
        get("/tree",{
            description = "获得板块列表, 以树形结构输出"
            response {
                statuses<Set<TagTree>>(HttpStatus.OK, example = setOf(TagTree.leafExample, TagTree.example))
            }
        }) { getTagsList(true) }
    }

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
data class EditTagBody(val name: String?, val description: String?)

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

private suspend fun Context.deleteTagById(): Nothing
{
    checkPermission {
        checkHasGlobalAdmin()
    }

    val id = call.parameters["id"]?.toTagIdOrNull() ?: finishCall(HttpStatus.BadRequest)

    if (!get<Tags>().removeTagById(id)) finishCall(HttpStatus.NotFound.subStatus("标签不存在"))

    finishCall(HttpStatus.OK)
}

private suspend fun Context.editTag(): Nothing
{
    checkPermission {
        checkHasGlobalAdmin()
    }

    val id = call.parameters["id"]?.toTagIdOrNull() ?: finishCall(HttpStatus.BadRequest)

    val body = call.receiveAndCheckBody<EditTagBody>()

    if (!get<Tags>().editTag(id, body.name, body.description))
        finishCall(HttpStatus.NotFound.subStatus("标签不存在"))

    finishCall(HttpStatus.OK)
}

private suspend fun Context.setTagFolder(): Nothing
{
    val id = call.parameters["id"]?.toTagIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val folder = call.request.queryParameters["folder"]?.toTagIdOrNull()

    checkPermission {
        checkHasGlobalAdmin()
    }

    val target = get<Tags>().getTagById(id) ?: finishCall(HttpStatus.NotFound)

    if (folder != null)
    {
        val folderTag = get<Tags>().getTagById(folder) ?: finishCall(HttpStatus.NotFound)
        if (target.type != folderTag.type) finishCall(HttpStatus.BadRequest.subStatus("文件夹与标签类型不匹配"))
    }

    get<Tags>().setTagFolder(id, folder)

    finishCall(HttpStatus.OK)
}

private suspend fun Context.getTagsList(tree: Boolean): Nothing
{
    val type = call.parameters["type"].decodeOrNull<TagType>() ?: finishCall(HttpStatus.BadRequest)
    val childOf = call.parameters["childOf"]?.toTagIdOrNull()
    val descendantOf = call.parameters["descendantOf"]?.toTagIdOrNull()
    val key = call.parameters["key"]

    if(childOf != null && descendantOf != null){
        finishCall(HttpStatus.BadRequest, "childOf和descendantOf不能同时填写")
    }

    val tags = get<Tags>()

    if(tree){
        finishCall(HttpStatus.OK, tags.getTagsList(type, key, childOf, descendantOf, 0, Int.MAX_VALUE).list.toMutableSet().toTagTree())
    }
    else {
        val (begin, count) = call.getPage()
        finishCall(HttpStatus.OK, tags.getTagsList(type,key,childOf,descendantOf, begin, count))
    }
}

@Serializable
data class TagTree(val info: Tag, val children: Set<TagTree>)
{
    companion object
    {
        val leafExample = TagTree(Tag.example, setOf())
        val example = TagTree(Tag.example, setOf(leafExample))
    }
}

private fun MutableSet<Tag>.toTagTree(root: Tag? = null): Set<TagTree>
{
    return this
        .filter { it.parent == root?.id }
        .also { this.removeAll(it.toSet()) }
        .map { TagTree(it, this.toTagTree(it)) }
        .toSet()
}