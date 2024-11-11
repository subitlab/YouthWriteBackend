@file:Suppress("PackageDirectoryMismatch")

package subit.router.admin

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.database.*
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.SSO
import subit.utils.respond
import subit.utils.statuses
import kotlin.time.Duration.Companion.days

fun Route.admin() = route("/admin", {
    tags = listOf("管理")
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

    get("/globalInfo", {
        description = "获取全局信息, 需要当前用户的user权限大于ADMIN"
        response {
            statuses<GlobalInfo>(HttpStatus.OK, example = GlobalInfo.example)
        }
    }) { globalInfo() }
}

@Serializable
private data class ProhibitUser(val id: UserId, val prohibit: Boolean, val time: Long, val reason: String)

private suspend fun Context.prohibitUser()
{
    val prohibits = get<Prohibits>()
    val operations = get<Operations>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val prohibitUser = call.receiveAndCheckBody<ProhibitUser>()
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
    checkPermission { checkHasGlobalAdmin() }
    val (begin, count) = call.getPage()
    finishCall(HttpStatus.OK, get<Prohibits>().getProhibitList(begin, count))
}

@Serializable
private data class ChangePermission(val id: UserId, val permission: PermissionLevel)

private suspend fun Context.changePermission()
{
    val users = get<Users>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changePermission = call.receiveAndCheckBody<ChangePermission>()
    val user = SSO.getDbUser(changePermission.id) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkChangePermission(null, user, changePermission.permission) }
    users.changePermission(changePermission.id, changePermission.permission)
    get<Operations>().addOperation(loginUser.id, changePermission)
    if (loginUser.id != changePermission.id) get<Notices>().createNotice(
        Notice.SystemNotice(
            user = changePermission.id,
            content = "您的全局权限已被修改为${changePermission.permission}",
        )
    )
    call.respond(HttpStatus.OK)
}

@Serializable
private data class Data(
    val day: Long,
    val month: Long,
    val year: Long,
    val total: Long
)
{
    companion object
    {
        val example = Data(1, 1, 1, 1)
    }
}

@Serializable
private data class GlobalInfo(
    val post: Map<State, Data>,
    val comment: Data,
    val like: Data,
    val star: Data,
    val read: Long,
)
{
    companion object
    {
        val example = GlobalInfo(
            State.entries.associateWith { Data.example },
            Data.example,
            Data.example,
            Data.example,
            1
        )
    }
}

private suspend fun Context.globalInfo()
{
    checkPermission { checkHasGlobalAdmin() }
    val posts = get<Posts>()
    val dayPost = posts.totalPostCount(false, 1.days)
    val monthPost = posts.totalPostCount(false, 30.days)
    val yearPost = posts.totalPostCount(false, 365.days)
    val totalPost = posts.totalPostCount(false, null)
    val post = State.entries.associateWith { Data(dayPost[it]!!, monthPost[it]!!, yearPost[it]!!, totalPost[it]!!) }

    val dayComment = posts.totalPostCount(true, 1.days).values.sum()
    val monthComment = posts.totalPostCount(true, 30.days).values.sum()
    val yearComment = posts.totalPostCount(true, 365.days).values.sum()
    val totalComment = posts.totalPostCount(true, null).values.sum()
    val comment = Data(dayComment, monthComment, yearComment, totalComment)

    val read = posts.totalReadCount()

    val likes = get<Likes>()
    val dayLike = likes.totalLikesCount(1.days)
    val monthLike = likes.totalLikesCount(30.days)
    val yearLike = likes.totalLikesCount(365.days)
    val totalLike = likes.totalLikesCount(null)
    val like = Data(dayLike, monthLike, yearLike, totalLike)

    val stars = get<Stars>()
    val dayStar = stars.totalStarsCount(1.days)
    val monthStar = stars.totalStarsCount(30.days)
    val yearStar = stars.totalStarsCount(365.days)
    val totalStar = stars.totalStarsCount(null)
    val star = Data(dayStar, monthStar, yearStar, totalStar)

    val globalInfo = GlobalInfo(post, comment, like, star, read)
    call.respond(HttpStatus.OK, globalInfo)
}