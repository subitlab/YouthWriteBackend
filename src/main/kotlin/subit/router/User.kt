@file:Suppress("PackageDirectoryMismatch")

package subit.router.user

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
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
                获取当前登陆用户的信息或当前登陆的用户的user权限不低于ADMIN时可以获取完整用户信息, 否则只能获取基础信息.
                
                若该用户被封禁且当前用户不是全局管理员则用户名为"该用户已被封禁", 个性签名为null, 其他信息正常返回
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
                bodyDescription = "当id为0时或当前用户拥有全局管理员",
                example = UserFull.example
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
        description = "修改/删除个人简介, 删除他人个人简介需要全局管理员, 不能修改他人简介"
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
                description = "个人简介, null为删除"
                example("example", ChangeIntroduction("个人简介"))
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound, HttpStatus.Forbidden, HttpStatus.Unauthorized)
        }
    }) { changeIntroduction() }

    get("/stars/{id}", {
        description = """
                获取用户收藏的帖子
                
                若目标用户不是当前用户, 且当前登陆用户不是管理员, 则目标用户需要展示收藏, 否则返回Forbidden
                
                若目标用户被封禁且当前用户不是全局管理员则返回空列表
            """.trimIndent()
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
            statuses(HttpStatus.BadRequest, HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.Forbidden)
            statuses<Slice<PostId>>(HttpStatus.OK, example = sliceOf(PostId(0)))
        }
    }) { getStars(true) }

    get("/likes/{id}", {
        description = """
                获取用户点赞的帖子
                
                若目标用户不是当前用户, 且当前登陆用户不是管理员, 则目标用户需要展示收藏, 否则返回Forbidden
                
                若目标用户被封禁且当前用户不是全局管理员则返回空列表
            """.trimIndent()
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
    }
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
        val token = SSO.getAccessToken(id) ?: return call.respond(HttpStatus.NotFound)
        val user = SSO.getUserFull(token) ?: return call.respond(HttpStatus.NotFound)
        if (loginUser.hasGlobalAdmin()) finishCall(HttpStatus.OK, user)
        if (withPermission(user.toDatabaseUser()) { isProhibit() }) finishCall(
            HttpStatus.OK,
            BasicUserInfo(
                user.id,
                "该用户已被封禁",
                user.registrationTime,
                user.email,
                null,
                false,
            )
        )
        else finishCall(HttpStatus.OK, user.toBasicUserInfo())
    }
}

@Serializable
private data class ChangeIntroduction(val introduction: String?)

private suspend fun Context.changeIntroduction()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    withPermission { checkRealName() }
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changeIntroduction = call.receiveAndCheckBody<ChangeIntroduction>()
    if (id == UserId(0))
    {
        get<Users>().changeIntroduction(loginUser.id, changeIntroduction.introduction)
        return call.respond(HttpStatus.OK)
    }
    else if (changeIntroduction.introduction == null)
    {
        withPermission { checkHasGlobalAdmin() }
        if (get<Users>().changeIntroduction(id, null))
        {
            get<Operations>().addOperation(loginUser.id, changeIntroduction)
            return call.respond(HttpStatus.OK)
        }
        else
            return call.respond(HttpStatus.NotFound)
    }
    else finishCall(HttpStatus.Forbidden.subStatus("只能删除他人的个性签名不能修改"))
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
    val switchStars =call.receiveAndCheckBody<BooleanSetting>()
    get<Users>().changeShowStars(loginUser.id, switchStars.showStars)
    call.respond(HttpStatus.OK)
}