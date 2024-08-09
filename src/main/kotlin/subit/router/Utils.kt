package subit.router

import io.github.smiley4.ktorswaggerui.data.ValueExampleDescriptor
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiRequest
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiRequestParameter
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiSimpleBody
import io.ktor.server.application.*
import io.ktor.util.pipeline.*
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.ktor.ext.get

typealias Context = PipelineContext<*, ApplicationCall>

inline fun <reified T: Any> Context.get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
) = application.get<T>(qualifier, parameters)

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
        this.example = ValueExampleDescriptor("example", 0)
    }
    queryParameter<Int>("count")
    {
        this.required = true
        this.description = "获取数量"
        this.example = ValueExampleDescriptor("example", 10)
    }
}

fun ApplicationCall.getPage(): Pair<Long, Int>
{
    val begin = request.queryParameters["begin"]?.toLongOrNull() ?: 0
    val count = request.queryParameters["count"]?.toIntOrNull() ?: 10
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