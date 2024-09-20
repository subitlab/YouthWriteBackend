@file:Suppress("PackageDirectoryMismatch")

package subit.router.notice

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.Notice.*
import subit.dataClasses.NoticeId.Companion.toNoticeIdOrNull
import subit.database.Notices
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses
import subit.utils.toEnumOrNull

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
                example(Type.SYSTEM)
            }
            queryParameter<Boolean>("read")
            {
                required = false
                description = "是否已读, 不填则获取所有通知"
                example(true)
            }
        }
        response {
            statuses<Slice<NoticeResponse>>(HttpStatus.OK, example = NoticeResponse.example)
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
                - count: 通知数量, 若type为SYSTEM则恒为1, 否则为当前被点赞/收藏/评论的数量
                - content: 通知内容, 若type为SYSTEM则为通知内容, 否则为null
                """.trimIndent()
        request {
            pathParameter<NoticeId>("id")
            {
                required = true
                description = "通知ID"
            }
        }
        response {
            statuses<NoticeResponse>(HttpStatus.OK, examples = NoticeResponse.example.list)
            statuses(HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.BadRequest)
        }
    }) { getNotice() }

    post("/{id}", {
        description = "标记通知为已读/未读"
        request {
            pathParameter<NoticeId>("id")
            {
                required = true
                description = "通知ID"
            }
            queryParameter<Boolean>("read")
            {
                required = true
                description = "是否已读"
                example(true)
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.BadRequest)
        }
    }) { readNotice() }

    post("/all", {
        description = "标记所有通知为已读/未读"
        request {
            queryParameter<Boolean>("read")
            {
                required = true
                description = "是否已读"
                example(true)
            }
        }
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
}

/**
 * 注意由于[Notice.type]不在构造函数中等问题, 无法序列化, 故手动转为[NoticeResponse]
 */
@Serializable
private data class NoticeResponse(
    val id: NoticeId,
    val time: Long,
    val user: UserId,
    val type: Type,
    val read: Boolean,
    val post: PostId?,
    val count: Long,
    val content: String?,
)
{
    companion object
    {
        fun fromNotice(notice: Notice): NoticeResponse
        {
            return when (notice)
            {
                is SystemNotice -> NoticeResponse(
                    notice.id,
                    notice.time,
                    notice.user,
                    notice.type,
                    notice.read,
                    null,
                    1,
                    notice.content
                )
                is PostNotice -> NoticeResponse(
                    notice.id,
                    notice.time,
                    notice.user,
                    notice.type,
                    notice.read,
                    notice.post,
                    notice.count,
                    null
                )
            }
        }

        val example = sliceOf(
            fromNotice(SystemNotice.example),
            fromNotice(PostNotice.example)
        )
    }
}

private suspend fun Context.getList()
{
    val (begin, count) = call.getPage()
    val type = call.parameters["type"].toEnumOrNull<Type>() ?: return call.respond(HttpStatus.BadRequest)
    val read = call.parameters["read"]?.toBooleanStrictOrNull()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val notices = get<Notices>()
    notices
        .getNotices(loginUser.id, type, read, begin, count)
        .map { NoticeResponse.fromNotice(it) }
        .let { call.respond(HttpStatus.OK, it) }
}

private suspend fun Context.getNotice()
{
    val id = call.parameters["id"]?.toNoticeIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val notices = get<Notices>()
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val notice = notices.getNotice(id)?.takeIf { it.user == user.id } ?: return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK, NoticeResponse.fromNotice(notice))
}

private suspend fun Context.readNotice()
{
    val id = call.parameters["id"]?.toNoticeIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val read = call.parameters["read"]?.toBooleanStrictOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val notices = get<Notices>()
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    notices.getNotice(id)
        ?.takeIf { it.user == user.id }
        ?.let { if (read) notices.readNotice(id) else notices.unreadNotice(id) }
    call.respond(HttpStatus.OK)
}

private suspend fun Context.readAll()
{
    val read = call.parameters["read"]?.toBooleanStrictOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val notices = get<Notices>()
    if (read) notices.readNotices(user.id) else notices.unreadNotices(user.id)
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