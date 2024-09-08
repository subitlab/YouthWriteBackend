@file:Suppress("PackageDirectoryMismatch")

package subit.router.user

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.JWTAuth.getLoginUser
import subit.dataClasses.*
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.*
import subit.logger.ForumLogger
import subit.router.*
import subit.utils.HttpStatus
import subit.utils.SSO
import subit.utils.respond
import subit.utils.statuses

private val logger = ForumLogger.getLogger()
fun Route.user() = route("/user", {
    tags = listOf("用户")
    description = "用户接口"
})
{
    get("/info/{id}", {
        description = """
                获取用户信息, id为0时获取当前登陆用户的信息。
                获取当前登陆用户的信息或当前登陆的用户的user权限不低于ADMIN时可以获取完整用户信息, 否则只能获取基础信息
                """.trimIndent()
        request {
            authenticated(false)
            pathParameter<UserId>("id")
            {
                required = true
                description = "用户ID"
            }
        }
        response {
            statuses<UserFull>(
                HttpStatus.OK.copy(message = "获取完整用户信息成功"),
                bodyDescription = "当id为0, 即获取当前用户信息或user权限不低于ADMIN时返回",
                example = UserFull.example
            )
            statuses<BasicUserInfo>(
                HttpStatus.OK.copy(message = "获取基础用户的信息成功"),
                bodyDescription = "当id不为0即获取其他用户的信息且user权限低于ADMIN时返回",
                example = BasicUserInfo.example
            )
            statuses(HttpStatus.NotFound, HttpStatus.Unauthorized)
        }
    }) { getUserInfo() }

    post("/introduce/{id}", {
        description = "修改个人简介, 修改自己的需要user权限在NORMAL以上, 修改他人需要在ADMIN以上"
        request {
            authenticated(true)
            pathParameter<UserId>("id")
            {
                required = true
                description = """
                        要修改的用户ID, 0为当前登陆用户
                    """.trimIndent()
            }
            body<ChangeIntroduction>
            {
                required = true
                description = "个人简介"
                example("example", ChangeIntroduction("个人简介"))
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound, HttpStatus.Forbidden, HttpStatus.Unauthorized)
        }
    }) { changeIntroduction() }

    get("/stars/{id}", {
        description = "获取用户收藏的帖子"
        request {
            authenticated(false)
            pathParameter<UserId>("id")
            {
                required = true
                description = """
                        要获取的用户ID, 0为当前登陆用户
                        
                        若目标用户ID不是0, 且当前登陆用户不是管理员, 则目标用户需要展示收藏, 否则返回Forbidden
                    """.trimIndent()
            }
            paged()
        }
        response {
            statuses(HttpStatus.BadRequest, HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.Forbidden)
            statuses<Slice<PostId>>(HttpStatus.OK, example = sliceOf(PostId(0)))
        }
    }) { getStars() }

    post("/switchStars", {
        description = "切换是否公开收藏"
        request {
            authenticated(true)
            body<SwitchStars>
            {
                required = true
                description = "是否公开收藏"
                example("example", SwitchStars(true))
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.Unauthorized)
        }
    }) { switchStars() }
}

private suspend fun Context.getUserInfo()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser()
    logger.config("user=${loginUser?.id} get user info id=$id")
    if (id == UserId(0))
    {
        if (loginUser == null) return call.respond(HttpStatus.Unauthorized)
        return call.respond(HttpStatus.OK, loginUser)
    }
    else
    {
        val user = SSO.getBasicUserInfo(id) ?: return call.respond(HttpStatus.NotFound)
        // 这里需要判断类型并转换再返回, 因为respond的返回体类型是编译时确定的
        if (user is UserFull) return call.respond<UserFull>(HttpStatus.OK, user)
        return call.respond(HttpStatus.OK, user as BasicUserInfo)
    }
}

@Serializable
private data class ChangeIntroduction(val introduction: String)

private suspend fun Context.changeIntroduction()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changeIntroduction = receiveAndCheckBody<ChangeIntroduction>()
    if (id == UserId(0))
    {
        get<Users>().changeIntroduction(loginUser.id, changeIntroduction.introduction)
        call.respond(HttpStatus.OK)
    }
    else
    {
        checkPermission { checkHasGlobalAdmin() }
        if (get<Users>().changeIntroduction(id, changeIntroduction.introduction))
        {
            get<Operations>().addOperation(loginUser.id, changeIntroduction)
            call.respond(HttpStatus.OK)
        }
        else
            call.respond(HttpStatus.NotFound)
    }
}

private suspend fun Context.getStars()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val begin = call.parameters["begin"]?.toLongOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val count = call.parameters["count"]?.toIntOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser()
    // 若查询自己的收藏
    if (id == UserId(0))
    {
        if (loginUser == null) return call.respond(HttpStatus.Unauthorized)
        val stars = get<Stars>().getStars(user = loginUser.id, begin = begin, limit = count).map { it.post }
        return call.respond(HttpStatus.OK, stars)
    }
    // 查询其他用户的收藏
    val user = SSO.getDbUser(id) ?: return call.respond(HttpStatus.NotFound)
    // 若对方不展示收藏, 而当前用户未登录或不是管理员, 返回Forbidden
    if (!user.showStars && (loginUser == null || loginUser.permission < PermissionLevel.ADMIN))
        return call.respond(HttpStatus.Forbidden)
    val stars = get<Stars>().getStars(user = user.id, begin = begin, limit = count).map { it.post }
    call.respond(HttpStatus.OK, stars)
}

@Serializable
private data class SwitchStars(val showStars: Boolean)

private suspend fun Context.switchStars()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val switchStars = receiveAndCheckBody<SwitchStars>()
    get<Users>().changeShowStars(loginUser.id, switchStars.showStars)
    call.respond(HttpStatus.OK)
}