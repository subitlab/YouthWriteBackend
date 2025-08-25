@file:Suppress("PackageDirectoryMismatch")

package subit.router.user

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.*
import subit.logger.YouthWriteLogger
import subit.plugin.rateLimit.RateLimit
import subit.router.utils.*
import subit.utils.*

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
            pathParameter<Boolean>("useOldData")
            {
                required = false
                description = "默认为false，传true时获取已绑定新用户的旧用户，获取到的是旧信息"
            }
        }
        response {
            statuses<UserFull>(
                HttpStatus.OK.subStatus(message = "获取完整用户信息成功", subStatus = 1),
                bodyDescription = "当id为0时或当前用户拥有全局管理员",
                example = UserFull.example
            )
            statuses<BasicUserInfo>(
                HttpStatus.OK.subStatus(message = "获取基础用户的信息成功", subStatus = 0),
                bodyDescription = "当id不为0即获取其他用户的信息且user权限低于ADMIN时返回",
                example = BasicUserInfo.example
            )
            statuses(HttpStatus.NotFound, HttpStatus.Unauthorized)
        }
    }) { getUserInfo() }

    post("/information/{id}", {
        description = "修改/删除个人信息, 删除他人个人信息需要全局管理员, 不能修改他人简介"
        request {
            pathParameter<UserId>("id")
            {
                required = true
                description = """
                        要修改的用户ID, 0为当前登陆用户，修改的话必须传0
                    """.trimIndent()
            }
            body<ChangeInformation>
            {
                required = true
                description = "全局管理员删除时用空字符串表示，不传/null表示不变，全不变会400"
                example("example", ChangeInformation("金粟酒","个人简介"))
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
                
                若目标用户被封禁且当前用户不是全局管理员则返回空列表, 
                若收藏夹中的某篇帖子当前用户无权查看(可能因权限修改或者帖子被删除)则返回的post为null
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
            statuses<Slice<StarPost>>(HttpStatus.OK, example = sliceOf(StarPost.example))
        }
    }) { getStars(true) }

    get("/likes/{id}", {
        description = """
                获取用户点赞的帖子
                
                若目标用户不是当前用户, 且当前登陆用户不是管理员, 则目标用户需要展示收藏, 否则返回Forbidden
                
                若目标用户被封禁且当前用户不是全局管理员则返回空列表,
                若收藏夹中的某篇帖子当前用户无权查看(可能因权限修改或者帖子被删除)则返回的post为null
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
            statuses<Slice<StarPost>>(HttpStatus.OK, example = sliceOf(StarPost.example))
        }
    }) { getStars(false) }

    post("/setting",  {
        description = "修改设置"
        request {
            body<Settings>
            {
                required = true
                description = "设置选项，不传/null表示不修改，全不修改会400"
                example("example", Settings(true, listOf(BlockId(1), BlockId(2))))
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.Unauthorized)
        }
    }) { changeSettings() }

    rateLimit(RateLimit.SendEmail.rateLimitName)
    {
        post("/sendEmailCode", {
            description = "发送邮箱验证码"
            request {
                body<EmailInfo>
                {
                    required = true
                    description = "邮箱信息, 认领旧账户时为旧帐户绑定的邮箱"
                    example("example", EmailInfo("email@abc.com", EmailCodes.EmailCodeUsage.BIND_NEW_ACCOUNT))
                }
            }
            response {
                statuses(HttpStatus.OK)
                statuses(
                    HttpStatus.EmailFormatError.subStatus(subStatus = 1),
                    HttpStatus.TooManyRequests.subStatus(subStatus = 2),
                )
            }
        }) { sendEmailCode() }
    }

    post("/bindNewAccount", {
        description = "旧帐户绑定新用户，需要验证码"
        request {
            body<BindNewAccount>
            {
                required = true
                description = "旧用户邮箱和验证码"
                example("example", BindNewAccount("example@abc.com", "code"))
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Unauthorized, HttpStatus.NotFound)
        }
    }) { bindNewUser() }

    get("/getOldAccountAvatar/{id}", {
        description = "获取旧用户头像URL, 仅限旧用户, 没有头像返回空字符串"
        request {
            pathParameter<UserId>("id")
            {
                required = true
                description = "旧用户ID,负数"
            }
        }
        response {
            statuses<String>(HttpStatus.OK, example = "https://www.youthwrite.pro/wp-content/uploads/2025/03/头像2.png")
        }
    }) { getOldAccountAvatar() }

    get("/searchByUsername", {
        description = "通过用户名搜索用户"
        request {
            queryParameter<String>("username")
            {
                required = true
                allowEmptyValue = true
                description = "用户名"
            }
            queryParameter<Boolean>("oldUser")
            {
                required = false
                description = "是否搜索旧用户, 默认为true"
            }
            queryParameter<Boolean>("newUser")
            {
                required = false
                description = "是否搜索新用户, 默认为true"
            }
            queryParameter<Boolean>("penName")
            {
                required = false
                description = "使用笔名搜索, 默认为true"
            }
            paged()
        }
        response {
            statuses<Slice<BasicUserInfo>>(HttpStatus.OK, example = sliceOf(BasicUserInfo.example))
            statuses(HttpStatus.BadRequest, HttpStatus.NotFound)
        }
    }, Context::searchByUsername)

    get("/oldInfo/{id}", {
        description = "获取旧用户信息, 仅限旧用户, id为负数"
        request {
            pathParameter<UserId>("id")
            {
                required = true
                description = "旧用户ID, 负数"
            }
        }
        response {
            statuses<OldUserInfo>(HttpStatus.OK, example = OldUserInfo.example)
            statuses(HttpStatus.BadRequest, HttpStatus.NotFound)
        }
    }) { getOldUserInfo() }

    get("/statistics/{id}", {
        description = "获取用户统计信息, 包括发帖数、评论数、文章点赞数、文章收藏数、文章浏览数"
        request {
            pathParameter<UserId>("id")
            {
                required = true
                description = "用户ID, 0为当前登录用户"
            }
        }
        response {
            statuses<UserStatistics>(HttpStatus.OK, example = UserStatistics(10, 20, 30, 40, 50))
            statuses(HttpStatus.BadRequest, HttpStatus.NotFound)
        }
    }) { getUserStatistics() }
}

private suspend fun Context.getUserInfo()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.NotFound)
    val useOldData = call.parameters["useOldData"]?.toBooleanStrictOrNull() ?: false
    val loginUser = getLoginUser()
    logger.config("user=${loginUser?.id} get user info id=$id")
    if (id == UserId(0))
    {
        if (loginUser == null) return call.respond(HttpStatus.Unauthorized)
        return call.respond(HttpStatus.OK, loginUser)
    }
    else
    {
        val user = SSO.getUserFullById(id, useOldData) ?: return call.respond(HttpStatus.NotFound)
        if (loginUser.hasGlobalAdmin()) finishCall(HttpStatus.OK, user)
        if (user.checkPermission { isProhibit() }) finishCall(
            HttpStatus.OK,
            BasicUserInfo(
                user.id,
                "该用户已被封禁",
                user.registrationTime,
                user.email,
                null,
                null,
                PermissionLevel.BANNED,
                false,
            )
        )
        else finishCall(HttpStatus.OK, user.toBasicUserInfo())
    }
}

private suspend fun Context.getOldUserInfo()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    if (id >= UserId(0)) return call.respond(HttpStatus.BadRequest.subStatus("只能获取旧用户的信息,id为负数"))
    val oldUser = get<OldUsers>().getOldUser(id) ?: return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK, oldUser)
}

@Serializable
private data class ChangeInformation(
    val penName: String? = null,
    val introduction: String? = null,
)

private suspend fun Context.changeIntroduction()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changeInformation = call.receiveAndCheckBody<ChangeInformation>()

    if(changeInformation.isAllPropertiesNull())
        return call.respond(HttpStatus.BadRequest.subStatus("至少需要修改一个字段"))

    changeInformation.penName?.let{ if(it.length > 32) finishCall(HttpStatus.BadRequest.subStatus("笔名长度不能超过32个字符")) }

    if (id == UserId(0))
    {
        get<Users>().changeInformation(loginUser.id, changeInformation.introduction, changeInformation.penName)
        return call.respond(HttpStatus.OK)
    }
    else{
        checkPermission { checkHasGlobalAdmin() }
        if(changeInformation.introduction?.isNotEmpty() ?: false || changeInformation.penName?.isNotEmpty() ?: false)
            finishCall(HttpStatus.Forbidden.subStatus("管理员只能删除他人信息不能修改"))
        else
        {
            if(get<Users>().changeInformation(loginUser.id, changeInformation.introduction, changeInformation.penName)) {
                get<Operations>().addOperation(loginUser.id, changeInformation)
                finishCall(HttpStatus.OK)
            }
            else finishCall(HttpStatus.NotFound)
        }
    }
}

@Serializable
private data class StarPost(val time: Long, val post: PostFullBasicInfo?)
{
    companion object
    {
        val example = StarPost(System.currentTimeMillis(), PostFullBasicInfo.example)
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
            val stars = get<Stars>().getStars(user = loginUser.id, begin = begin, limit = count).map(Star::post)
            return call.respond(HttpStatus.OK, stars)
        }
        else
        {
            val likes = get<Likes>().getLikes(user = loginUser.id, begin = begin, limit = count).map(Like::post)
            return call.respond(HttpStatus.OK, likes)
        }
    }
    // 查询其他用户的收藏
    val user = SSO.getDbUser(id) ?: return call.respond(HttpStatus.NotFound)
    // 若对方不展示收藏, 而当前用户未登录或不是管理员, 返回Forbidden
    if (!user.showStars && (loginUser == null || loginUser.permission < PermissionLevel.ADMIN))
        return call.respond(HttpStatus.Forbidden)
    val likes =
        if (isStar) get<Stars>().getStars(user = user.id, begin = begin, limit = count)
        else get<Likes>().getLikes(user = user.id, begin = begin, limit = count)


    val posts = get<Posts>()
        .mapToPostFullBasicInfo(loginUser, likes.list.map { it.post })
        .filterNotNull()
        .map { checkAnonymous(it) }
        .associateBy(PostFullBasicInfo::id)
    call.respond(HttpStatus.OK, likes.map { StarPost(it.time, posts[it.post]) })
}

@Serializable
private data class Settings(
    val showStars: Boolean? = null,
    val likeBlocksList: List<BlockId>? = null,
)

/*
 * null为不修改
 */
private suspend fun Context.changeSettings()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val settings = call.receiveAndCheckBody<Settings>()
    if( settings.isAllPropertiesNull() ) finishCall(HttpStatus.BadRequest.subStatus("至少需要修改一个字段") )

    val likeBlocksList = settings.likeBlocksList?.distinct()?.let{
        if( it.size !in 0..5) finishCall(HttpStatus.BadRequest.subStatus("收藏的板块不能超过5个"))
        it
    }

    get<Users>().changeSettings(
        loginUser.id,
        showStars = settings.showStars,
        likeBlocks = likeBlocksList
    )

    call.respond(HttpStatus.OK)
}

@Serializable
private data class BindNewAccount(
    val oldEmail: String,
    val code: String
)

private suspend fun Context.bindNewUser(){
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receiveAndCheckBody<BindNewAccount>()
    if (!get<EmailCodes>().verifyEmailCode(body.oldEmail, body.code, EmailCodes.EmailCodeUsage.BIND_NEW_ACCOUNT))
        finishCall(HttpStatus.WrongEmailCode)

    val oldUserTable = get<OldUsers>()
    val id = oldUserTable.getEmailUser(body.oldEmail) ?: finishCall(HttpStatus.AccountNotExist)
    if(oldUserTable.getNewId(id) != null) finishCall(HttpStatus.EmailExist.copy(message = "该账户已被其他用户认领"))
    oldUserTable.setNewId(id, loginUser.id)
    get<Posts>().bindNewAccount(id, loginUser.id)
    get<Likes>().bindNewAccount(id, loginUser.id)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.sendEmailCode()
{
    val emailInfo = call.receive<EmailInfo>()
    if (!checkEmail(emailInfo.email))
        finishCall(HttpStatus.EmailFormatError)
    if (emailInfo.usage == EmailCodes.EmailCodeUsage.BIND_NEW_ACCOUNT)
    {
        val oldUserTable = get<OldUsers>()
        val id = oldUserTable.getEmailUser(emailInfo.email) ?:
            finishCall(HttpStatus.AccountNotExist)
        if(oldUserTable.getNewId(id) != null)
            finishCall(HttpStatus.EmailExist.copy(message = "该账户已被其他用户认领"))
    }
    get<EmailCodes>().  sendEmailCode(emailInfo.email, emailInfo.usage)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getOldAccountAvatar()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    if (id >= UserId(0)) finishCall(HttpStatus.BadRequest.subStatus("只能获取旧用户的头像,id为负数"))
    val avatar = get<OldUsers>().getAvatar(id) ?: ""
    call.respond(HttpStatus.OK, avatar)
}

private suspend fun Context.searchByUsername()
{
    val username = call.parameters["username"] ?: return call.respond(HttpStatus.BadRequest)
    val oldUser = call.parameters["oldUser"]?.toBooleanStrictOrNull() ?: true
    val newUser = call.parameters["newUser"]?.toBooleanStrictOrNull() ?: true
    val penName = call.parameters["penName"]?.toBooleanStrictOrNull() ?: true
    if (!oldUser && !newUser) return call.respond(HttpStatus.BadRequest.subStatus("至少需要搜索旧用户或新用户"))
    val (begin, count) = call.getPage()
    val ssoUsers =
        if (newUser)
            if(penName) get<Users>().searchUserByPenName(username, begin, count)
            else SSO.searchUser(username, begin, count)
        else sliceOf()
    val oldUsers =
        if (oldUser && !penName) get<OldUsers>().searchUser(username, begin - ssoUsers.totalSize, count) // 用笔名搜索会导致老用户被搜到两次
        else sliceOf()
    val res = Slice(ssoUsers.totalSize + oldUsers.totalSize, begin, ssoUsers.list + oldUsers.list)
        .map { SSO.getUserFullById(it, true)!! }

    call.respond(HttpStatus.OK, res)
}

@Serializable
data class UserStatistics(
    val postCount: Long,
    val commentCount: Long,
    val likeCount: Long,
    val starCount: Long,
    val viewCount: Long,
)

private suspend fun Context.getUserStatistics()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val user =
        if( id == UserId(0) ) getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
        else SSO.getUserFullById(id) ?: return call.respond(HttpStatus.NotFound)

    val posts = get<Posts>()

    val statistic = UserStatistics(
        postCount = posts.getPostsCounts(false,user.id),
        commentCount = posts.getPostsCounts(true,user.id),
        likeCount = posts.getLikesCount(user.id),
        starCount = posts.getStarsCount(user.id),
        viewCount = posts.getViewsCount(user.id),
    )
    call.respond(HttpStatus.OK, statistic)
}