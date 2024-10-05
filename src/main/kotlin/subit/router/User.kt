@file:Suppress("PackageDirectoryMismatch")

package subit.router.user

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.*
import subit.logger.YouthWriteLogger
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.SSO
import subit.utils.respond
import subit.utils.statuses

private val logger = YouthWriteLogger.getLogger()
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
            pathParameter<UserId>("id")
            {
                required = true
                description = "用户ID"
            }
        }
        response {
            statuses<UserFull>(
                HttpStatus.OK.subStatus(message = "获取完整用户信息成功"),
                bodyDescription = "当id为0时",
                example = UserFull.example
            )
            statuses<UserProfile>(
                HttpStatus.OK.subStatus(message = "获取用户信息成功"),
                bodyDescription = "当id不为0即获取其他用户的信息且user权限低于ADMIN时返回",
                example = UserProfile(
                    UserId(1),
                    "username",
                    System.currentTimeMillis(),
                    listOf("email"),
                    "introduction",
                    true,
                    PermissionLevel.NORMAL,
                    PermissionLevel.NORMAL
                )
            )
            statuses<BasicUserInfo>(
                HttpStatus.OK.subStatus(message = "获取基础用户的信息成功"),
                bodyDescription = "当id不为0即获取其他用户的信息且user权限低于ADMIN时返回",
                example = BasicUserInfo.example
            )
            statuses(HttpStatus.NotFound, HttpStatus.Unauthorized)
        }
    }) { getUserInfo() }

    post("/introduce/{id}", {
        description = "修改个人简介, 修改自己的需要user权限在NORMAL以上, 修改他人需要在ADMIN以上"
        request {
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
    }) { getStars(true) }

    get("/likes/{id}", {
        description = "获取用户点赞的帖子"
        request {
            pathParameter<UserId>("id")
            {
                required = true
                description = """
                        要获取的用户ID, 0为当前登陆用户
                    """.trimIndent()
            }
            paged()
        }
        response {
            statuses(HttpStatus.BadRequest, HttpStatus.Unauthorized, HttpStatus.NotFound)
            statuses<Slice<PostId>>(HttpStatus.OK, example = sliceOf(PostId(0)))
        }
    }) { getStars(false) }

    route("/setting")
    {
        post("/showStars", {
            description = "切换是否公开收藏"
            request {
                body<BooleanSetting>
                {
                    required = true
                    description = "是否公开收藏"
                    example("example", BooleanSetting(true))
                }
            }
            response {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.Unauthorized)
            }
        }) { switchStars() }

        post("/mergeNotice", {
            description = "切换通知是否合并"
            request {
                body<BooleanSetting>
                {
                    required = true
                    description = "是否合并通知"
                    example("example", BooleanSetting(true))
                }
            }
            response {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.NotFound, HttpStatus.Forbidden)
            }
        }) { mergeNotice() }
    }
}

@Serializable
/**
 * 全局管理员能获取到信息是[UserFull]的子集, [BasicUserInfo]的超集
 */
private data class UserProfile(
    val id: UserId,
    val username: String,
    val registrationTime: Long,
    val email: List<String>,
    val introduction: String?,
    val showStars: Boolean,
    val permission: PermissionLevel,
    val filePermission: PermissionLevel
)

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
        val user = SSO.getUserAndDbUser(id) ?: return call.respond(HttpStatus.NotFound)
        if (loginUser.hasGlobalAdmin())
        {
            call.respond(
                HttpStatus.OK,
                UserProfile(
                    user.first.id,
                    user.first.username,
                    user.first.registrationTime,
                    user.first.email,
                    user.second.introduction,
                    user.second.showStars,
                    user.second.permission,
                    user.second.filePermission
                )
            )
        }
        call.respond(HttpStatus.OK, BasicUserInfo.from(user.first, user.second))
    }
}

@Serializable
private data class ChangeIntroduction(val introduction: String)

private suspend fun Context.changeIntroduction()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    withPermission { checkRealName() }
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changeIntroduction = receiveAndCheckBody<ChangeIntroduction>()
    if (id == UserId(0))
    {
        get<Users>().changeIntroduction(loginUser.id, changeIntroduction.introduction)
        return call.respond(HttpStatus.OK)
    }
    else
    {
        withPermission { checkHasGlobalAdmin() }
        println(call.response.responseType)
        if (get<Users>().changeIntroduction(id, changeIntroduction.introduction))
        {
            get<Operations>().addOperation(loginUser.id, changeIntroduction)
            return call.respond(HttpStatus.OK)
        }
        else
            return call.respond(HttpStatus.NotFound)
    }
}

private suspend fun Context.getStars(isStar: Boolean)
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val begin = call.parameters["begin"]?.toLongOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val count = call.parameters["count"]?.toIntOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser()
    // 若查询自己的收藏
    if (id == UserId(0))
    {
        if (loginUser == null) return call.respond(HttpStatus.Unauthorized)
        if (isStar)
        {
            val stars = get<Stars>().getStars(user = loginUser.id, begin = begin, limit = count).map { it.post }
            return call.respond(HttpStatus.OK, stars)
        }
        else
        {
            val likes = get<Likes>().getLikes(user = loginUser.id, begin = begin, limit = count).map { it.post }
            return call.respond(HttpStatus.OK, likes)
        }
    }
    // 查询其他用户的收藏
    val user = SSO.getDbUser(id) ?: return call.respond(HttpStatus.NotFound)
    // 若对方不展示收藏, 而当前用户未登录或不是管理员, 返回Forbidden
    if (!user.showStars && (loginUser == null || loginUser.permission < PermissionLevel.ADMIN))
        return call.respond(HttpStatus.Forbidden)
    if (isStar)
    {
        val stars = get<Stars>().getStars(user = user.id, begin = begin, limit = count).map { it.post }
        call.respond(HttpStatus.OK, stars)
    }
    else
    {
        val likes = get<Likes>().getLikes(user = user.id, begin = begin, limit = count).map { it.post }
        call.respond(HttpStatus.OK, likes)
    }
}

@Serializable
private data class BooleanSetting(val showStars: Boolean)

private suspend fun Context.switchStars()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val switchStars = receiveAndCheckBody<BooleanSetting>()
    get<Users>().changeShowStars(loginUser.id, switchStars.showStars)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.mergeNotice()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val mergeNotice = receiveAndCheckBody<BooleanSetting>()
    get<Users>().changeMergeNotice(loginUser.id, mergeNotice.showStars)
    call.respond(HttpStatus.OK)
}