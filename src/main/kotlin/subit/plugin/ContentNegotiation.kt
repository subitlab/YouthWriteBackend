@file:Suppress("PackageDirectoryMismatch")

package subit.plugin.contentNegotiation

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import subit.debug

/**
 * 用作请求/响应的json序列化/反序列化
 */
@OptIn(ExperimentalSerializationApi::class)
val contentNegotiationJson = Json()
{
    encodeDefaults = true
    prettyPrint = debug
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = true
    allowSpecialFloatingPointValues = true
    decodeEnumsCaseInsensitive = true
    allowTrailingComma = true
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
}

/**
 * 用作数据处理的json序列化/反序列化
 */
val dataJson = Json(contentNegotiationJson)
{
    prettyPrint = false
    explicitNulls = false
}

/**
 * 用作api文档等展示的json序列化/反序列化
 */
val showJson = Json(contentNegotiationJson)
{
    prettyPrint = true
}

/**
 * 安装反序列化/序列化服务(用于处理json)
 */
fun Application.installContentNegotiation() = install(ContentNegotiation)
{
    json(contentNegotiationJson)
}