@file:Suppress("NOTHING_TO_INLINE")

package subit.router.utils

import io.github.smiley4.ktorswaggerui.data.ValueExampleDescriptor
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiRequest
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiRequestParameter
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiSimpleBody
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.ktor.ext.get
import subit.dataClasses.*
import subit.utils.HttpStatus

typealias Context = RoutingContext

inline fun <reified T: Any> Context.get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
) = call.application.get<T>(qualifier, parameters)

/**
 * 辅助方法, 标记此方法返回需要传入begin和count, 用于分页
 */
inline fun OpenApiRequest.paged()
{
    queryParameter<Long>("begin")
    {
        this.required = true
        this.description = "起始位置"
        this.example = ValueExampleDescriptor("example", 0)
    }
    queryParameter<Int>("count")
    {
        this.required = true
        this.description = "获取数量"
        this.example = ValueExampleDescriptor("example", 10)
    }
}

inline fun ApplicationCall.getPage(): Pair<Long, Int>
{
    val begin = request.queryParameters["begin"]?.toLongOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("begin is required"))
    val count = request.queryParameters["count"]?.toIntOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("count is required"))
    if (begin < 0 || count < 0) finishCall(HttpStatus.BadRequest.subStatus("begin and count must be non-negative"))
    return begin to count
}

inline fun <reified T> OpenApiSimpleBody.example(name: String, example: T)
{
    example(name) { value = example }
}

inline fun <reified T> OpenApiRequestParameter.example(any: T)
{
    this.example = ValueExampleDescriptor("example", any)
}

inline fun Context.getLoginUser(): UserFull? = call.getLoginUser()
inline fun ApplicationCall.getLoginUser(): UserFull? = this.principal<UserFull>()

@JvmName("checkAnonymousPostFull")
inline fun Context.checkAnonymous(posts: Slice<PostFull>) = withPermission()
{
    if (hasGlobalAdmin) posts
    else posts.map { if (it.anonymous && it.author != user?.id) it.copy(author = UserId(0)) else it }
}

@JvmName("checkAnonymousPostFullBasicInfo")
inline fun Context.checkAnonymous(post: PostFull) = withPermission()
{
    if (hasGlobalAdmin) post
    else if (post.anonymous && post.author != user?.id) post.copy(author = UserId(0)) else post
}

@JvmName("checkAnonymousPostFullBasicInfo")
inline fun Context.checkAnonymous(posts: Slice<PostFullBasicInfo>) = withPermission()
{
    if (hasGlobalAdmin) posts
    else posts.map { if (it.anonymous && it.author != user?.id) it.copy(author = UserId(0)) else it }
}

@JvmName("checkAnonymousPostFullBasicInfo")
inline fun Context.checkAnonymous(post: PostFullBasicInfo) = withPermission()
{
    if (hasGlobalAdmin) post
    else if (post.anonymous && post.author != user?.id) post.copy(author = UserId(0)) else post
}
