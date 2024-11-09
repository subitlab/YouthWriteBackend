@file:Suppress("PackageDirectoryMismatch")

package subit.router.notice

import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.Notice.*
import subit.dataClasses.NoticeId.Companion.toNoticeIdOrNull
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.Notices
import subit.router.utils.*
import subit.utils.*

fun Route.notice() = route("/notice", {
    tags = listOf("通知")
})
{
    get("/list", {
        description = """
                获取通知列表
                
                除去此接口获取的通知外, 待处理的举报和未读的私信也应在通知中显示, 
                详细请参阅 获取举报列表接口(/report/list) 和 获取所有未读私信数量接口(/privateChat/unread/all)
                """.trimIndent()
        request {
            paged()
            queryParameter<Type>("type")
            {
                required = false
                description = "通知类型, 可选值为${Type.entries.joinToString { it.name }}, 不填则获取所有通知"
            }
            queryParameter<Boolean>("read")
            {
                required = false
                description = "是否已读, 不填则获取所有通知"
            }
        }
        response {
            statuses<Slice<Notice>>(HttpStatus.OK, example = sliceOf(PostNotice.example, SystemNotice.example))
            statuses(HttpStatus.Unauthorized)
        }
    }) { getList() }

    get("/{id}", {
        description = """
                获取通知, 通知有多种类型, 每种类型有结构不同, 可通过type区分. 请注意处理.
                
                相应中的type字段为通知类型, 可能为${Type.entries.joinToString { it.name }}
                
                - id: 通知ID
                - time: 通知时间
                - user: 用户ID
                - type: 通知类型
                - read: 是否已读
                - post: 帖子ID, 若type为SYSTEM则为null, 否则为被点赞/收藏/评论的帖子ID
                - content: 通知内容
                """.trimIndent()
        request {
            pathParameter<NoticeId>("id")
            {
                required = true
                description = "通知ID"
            }
        }
        response {
            statuses<Notice>(HttpStatus.OK, examples = listOf(PostNotice.example, SystemNotice.example))
            statuses(HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.BadRequest)
        }
    }) { getNotice() }

    post("/{id}", {
        description = "标记通知为已读"
        request {
            pathParameter<NoticeId>("id")
            {
                required = true
                description = "通知ID"
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.BadRequest)
        }
    }) { readNotice() }

    post("/all", {
        description = "标记所有通知为已读/未读"
        response {
            statuses(HttpStatus.OK, HttpStatus.Unauthorized, HttpStatus.BadRequest)
        }
    }) { readAll() }

    delete("/{id}", {
        description = "删除通知"
        request {
            pathParameter<NoticeId>("id")
            {
                required = true
                description = "通知ID"
                example(NoticeId(0))
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.BadRequest)
        }
    }) { deleteNotice() }

    delete("/all", {
        description = "删除所有通知"
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.Unauthorized)
        }
    }) { deleteAll() }

    post("/send/{id}", {
        description = "向某位用户发送一条系统通知, 需要全局管理员"
        request {
            pathParameter<UserId>("id")
            {
                required = true
                description = "用户ID"
            }
            body<SendNotice>()
            {
                required = true
                description = "通知内容"
                example("example", SendNotice("通知内容"))
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.BadRequest, HttpStatus.Forbidden)
        }
    }) { sendNotice() }
}

private suspend fun Context.getList()
{
    val (begin, count) = call.getPage()
    val type = call.parameters["type"].toEnumOrNull<Type>()
    val read = call.parameters["read"]?.toBooleanStrictOrNull()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val notices = get<Notices>()
    notices
        .getNotices(loginUser.id, type, read, begin, count)
        .let { call.respond(HttpStatus.OK, it) }
}

private suspend fun Context.getNotice()
{
    val id = call.parameters["id"]?.toNoticeIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val notices = get<Notices>()
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val notice = notices.getNotice(id)?.takeIf { it.user == user.id } ?: return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK, notice)
}

private suspend fun Context.readNotice()
{
    val id = call.parameters["id"]?.toNoticeIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val notices = get<Notices>()
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    notices.getNotice(id)
        ?.takeIf { it.user == user.id }
        ?.let { notices.readNotice(id) }
    call.respond(HttpStatus.OK)
}

private suspend fun Context.readAll()
{
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val notices = get<Notices>()
    notices.readNotices(user.id)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.deleteNotice()
{
    val id = call.parameters["id"]?.toNoticeIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val notices = get<Notices>()
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    notices.getNotice(id)?.takeIf { it.user == user.id }?.let { notices.deleteNotice(id) }
    call.respond(HttpStatus.OK)
}

private suspend fun Context.deleteAll()
{
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val notices = get<Notices>()
    notices.deleteNotices(user.id)
    call.respond(HttpStatus.OK)
}

@Serializable
private data class SendNotice(val content: String)

private suspend fun Context.sendNotice()
{
    withPermission { checkHasGlobalAdmin() }
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    if (!SSO.hasUser(id)) finishCall(HttpStatus.NotFound)
    val content = call.receiveAndCheckBody<SendNotice>().content
    get<Notices>().createNotice(SystemNotice(user = id, content = content))
    finishCall(HttpStatus.OK)
}