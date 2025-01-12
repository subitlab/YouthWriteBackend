@file:Suppress("PackageDirectoryMismatch")

package subit.router.report

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.ReportId.Companion.toReportIdOrNull
import subit.database.Reports
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses

fun Route.report() = route("/report", {
    tags = listOf("举报")
})
{
    post("/{type}/{id}", {
        description = "举报一个帖子/用户/板块/评论"
        request {
            pathParameter<ReportObject>("type")
            {
                required = true
                description = "举报对象"
                example(ReportObject.POST)
            }
            pathParameter<PostId>("id")
            {
                required = true
                description = "帖子id"
            }
            body<ReportContent>
            {
                required = true
                description = "举报内容"
                example("example", ReportContent("举报内容"))
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Forbidden, HttpStatus.NotFound)
        }
    }) { newReport() }

    get("/list", {
        description = "获取举报列表, 需要全局管理员"
        request {
            queryParameter<String>("filter")
            {
                required = true
                description = "all表示全部, true是已受理, false未受理"
                example("all")
            }
            paged()
        }
        response {
            statuses<Slice<ReportId>>(HttpStatus.OK, example = sliceOf(ReportId(0)))
            statuses(HttpStatus.Forbidden)
        }
    }) { getReports() }

    get("/{id}", {
        description = "获取一个举报, 需要全局管理员"
        request {
            pathParameter<ReportId>("id")
            {
                required = true
                description = "举报id"
            }
        }
        response {
            statuses<Report>(HttpStatus.OK, example = Report.example)
            statuses(HttpStatus.NotFound, HttpStatus.Forbidden)
        }
    }) { getReport() }

    post("/handled/{id}", {
        description = "处理一个举报, 需要全局管理员"
        request {
            pathParameter<ReportId>("id")
            {
                required = true
                description = "举报id"
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound, HttpStatus.Forbidden)
        }
    }) { handleReport() }
}

@Serializable
private data class ReportContent(val content: String)

private suspend fun Context.newReport()
{
    val id = call.parameters["id"]?.toLongOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val type = call.parameters["type"]?.runCatching { ReportObject.valueOf(this) }?.getOrNull()
               ?: return call.respond(HttpStatus.BadRequest)
    val content = call.receiveAndCheckBody<ReportContent>().content
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    get<Reports>().addReport(type, id, loginUser.id, content)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.getReports()
{
    checkPermission { checkHasGlobalAdmin() }
    val begin = call.parameters["begin"]?.toLongOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val count = call.parameters["count"]?.toIntOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val handled = when (call.parameters["filter"])
    {
        "true"  -> true
        "false" -> false
        "all"   -> null
        else    -> return call.respond(HttpStatus.BadRequest)
    }
    call.respond(HttpStatus.OK, get<Reports>().getReports(begin, count, handled).map(Report::id))
}

private suspend fun Context.getReport()
{
    checkPermission { checkHasGlobalAdmin() }
    val id = call.parameters["id"]?.toReportIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val report = get<Reports>().getReport(id) ?: return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK, report)
}

private suspend fun Context.handleReport()
{
    checkPermission { checkHasGlobalAdmin() }
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val id = call.parameters["id"]?.toReportIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    get<Reports>().handleReport(id, loginUser.id)
    call.respond(HttpStatus.OK)
}