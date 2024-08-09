package subit.plugin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.request.*

/**
 * 双接收插件, 一般在ktor中一个请求体只能被读取([ApplicationCall.receive])一次, 多次读取会抛出异常.
 *
 * 该插件可以让请求体被多次读取, 但是也会消耗更多的内存.
 */
fun Application.installDoubleReceive() = install(DoubleReceive)
{
    cacheRawRequest = true
    excludeFromCache { call, _ -> !call.request.contentType().match(ContentType.Application.Json) } // 不缓存非json请求
    useFileForCache { call -> (call.request.contentLength() ?: 0) > 64 * (1 shl 20) } // 超过64MB的请求体使用文件缓存
}