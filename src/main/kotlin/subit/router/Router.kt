package subit.router

import io.github.smiley4.ktorswaggerui.dsl.OpenApiRequest
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import subit.JWTAuth.getLoginUser
import subit.database.ProhibitDatabase
import subit.router.admin.admin
import subit.router.auth.auth
import subit.router.block.block
import subit.router.comment.comment
import subit.router.files.files
import subit.router.posts.posts
import subit.router.report.report
import subit.router.user.user
import subit.utils.HttpStatus

typealias Context = PipelineContext<*, ApplicationCall>

/**
 * 辅助方法, 标记此接口需要验证token(需要登陆)
 * @param required 是否必须登陆
 */
fun OpenApiRequest.authenticated(required: Boolean) = headerParameter<String>("Authorization")
{
    this.description = "Bearer token"
    this.required = required
}

/**
 * 辅助方法, 标记此方法返回需要传入begin和count, 用于分页
 */
fun OpenApiRequest.paged()
{
    queryParameter<Long>("begin")
    {
        this.required = true
        this.description = "起始位置"
    }
    queryParameter<Int>("count")
    {
        this.required = true
        this.description = "获取数量"
    }
}

fun Application.router() = routing()
{
    authenticate(optional = true)
    {
        intercept(ApplicationCallPipeline.Call)
        {
            getLoginUser()?.id?.apply {
                ProhibitDatabase.checkProhibit(this).apply {
                    call.respond(HttpStatus.Forbidden)
                    finish()
                }
            }
        }

        admin()
        auth()
        block()
        comment()
        files()
        // todo home() // 热门帖子推荐
        // todo notice()
        posts()
        report()
        user()
    }
}