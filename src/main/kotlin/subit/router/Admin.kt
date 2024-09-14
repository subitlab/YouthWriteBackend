@file:Suppress("PackageDirectoryMismatch")

package subit.router.admin

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.database.*
import subit.router.*
import subit.utils.HttpStatus
import subit.utils.SSO
import subit.utils.respond
import subit.utils.statuses

fun Route.admin() = route("/admin", {
    tags = listOf("用户管理")
    response {
        statuses(HttpStatus.Unauthorized, HttpStatus.Forbidden)
    }
})
{
    post("/prohibitUser", {
        description = "封禁用户, 需要当前用户的权限大于ADMIN且大于对方的权限"
        request {
            body<ProhibitUser>
            {
                required = true
                description = "封禁信息, 其中time是封禁结束的时间戳"
                example(
                    "example",
                    ProhibitUser(UserId(1), true, System.currentTimeMillis() + 1000 * 60 * 60 * 24, "reason")
                )
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { prohibitUser() }

    get("/prohibitList", {
        description = "获取封禁列表, 需要当前用户的user权限大于ADMIN"
        request {
            paged()
        }
        response {
            statuses<Slice<Prohibit>>(HttpStatus.OK, example = sliceOf(Prohibit.example))
        }
    }) { prohibitList() }

    post("/changePermission", {
        description = "修改用户权限, 需要当前用户的权限大于ADMIN且大于对方的权限"
        request {
            body<ChangePermission>
            {
                required = true
                description = "修改信息"
                example("example", ChangePermission(UserId(1), PermissionLevel.ADMIN))
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { changePermission() }
}

@Serializable
private data class ProhibitUser(val id: UserId, val prohibit: Boolean, val time: Long, val reason: String)

private suspend fun Context.prohibitUser()
{
    val prohibits = get<Prohibits>()
    val operations = get<Operations>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val prohibitUser = receiveAndCheckBody<ProhibitUser>()
    val user = SSO.getDbUser(prohibitUser.id) ?: return call.respond(HttpStatus.NotFound)
    if (loginUser.permission < PermissionLevel.ADMIN || loginUser.permission <= user.permission)
        return call.respond(HttpStatus.Forbidden)
    if (prohibitUser.prohibit) prohibits.addProhibit(
        Prohibit(
            user = prohibitUser.id,
            time = prohibitUser.time,
            reason = prohibitUser.reason,
            operator = loginUser.id
        )
    )
    else prohibits.removeProhibit(prohibitUser.id)
    operations.addOperation(loginUser.id, prohibitUser)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.prohibitList()
{
    withPermission { checkHasGlobalAdmin() }
    val (begin, count) = call.getPage()
    call.respond(HttpStatus.OK, get<Prohibits>().getProhibitList(begin, count))
}

@Serializable
private data class ChangePermission(val id: UserId, val permission: PermissionLevel)

private suspend fun Context.changePermission()
{
    val users = get<Users>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changePermission = receiveAndCheckBody<ChangePermission>()
    val user = SSO.getDbUser(changePermission.id) ?: return call.respond(HttpStatus.NotFound)
    withPermission { checkChangePermission(null, user, changePermission.permission) }
    users.changePermission(changePermission.id, changePermission.permission)
    get<Operations>().addOperation(loginUser.id, changePermission)
    if (loginUser.id != changePermission.id) get<Notices>().createNotice(
        Notice.makeSystemNotice(
            user = changePermission.id,
            content = "您的全局权限已被修改"
        )
    )
    call.respond(HttpStatus.OK)
}
