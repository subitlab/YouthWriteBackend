package subit.plugin

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import subit.debug

/**
 * 安装反序列化/序列化服务(用于处理json)
 */
@OptIn(ExperimentalSerializationApi::class)
fun Application.installContentNegotiation() = install(ContentNegotiation)
{
    json(Json()
    {
        // 设置默认值也序列化, 否则不默认值不会被序列化
        encodeDefaults = true
        // 若debug模式开启, 则将json序列化为可读性更高的格式
        prettyPrint = debug
        // 忽略未知字段
        ignoreUnknownKeys = true
        // 宽松模式, 若字段类型不匹配, 则尝试转换
        isLenient = true
        // 编码null
        explicitNulls = true
        // 允许特殊的浮点数值, 如NaN, Infinity
        allowSpecialFloatingPointValues = true
        // 忽略枚举大小写
        decodeEnumsCaseInsensitive = true
        // 允许尾随逗号
        allowTrailingComma = true
    })
}