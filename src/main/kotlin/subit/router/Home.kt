@file:Suppress("PackageDirectoryMismatch")

package subit.router.home

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import subit.dataClasses.BlockId
import subit.dataClasses.PostFullBasicInfo
import subit.dataClasses.Slice
import subit.dataClasses.sliceOf
import subit.database.Blocks
import subit.database.Posts
import subit.database.receiveAndCheckBody
import subit.plugin.RateLimit
import subit.router.utils.*
import subit.utils.HomeFilesUtils
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

fun Route.home() = route("/home", {
    tags = listOf("首页")
})
{
    rateLimit(RateLimit.Search.rateLimitName)
    {
        route("/search", {
            request {
                queryParameter<String>("key")
                {
                    required = true
                    description = "关键字"
                    example("关键字")
                }
                paged()
            }
            response {
                statuses(HttpStatus.TooManyRequests)
            }
        })
        {

            get("/block", {
                description = "搜索板块, 会返回板块名称或介绍包含关键词的板块"
                response {
                    statuses<Slice<BlockId>>(HttpStatus.OK, example = sliceOf(BlockId(0)))
                }
            }) { searchBlock() }
        }
    }

    get("/monthly", {
        description = "最近一个月内的点赞数量排行榜"
        request {
            paged()
        }
        response {
            statuses<Slice<PostFullBasicInfo>>(HttpStatus.OK, example = sliceOf(PostFullBasicInfo.example))
        }
    }) { getMonthly() }

    route("/message")
    {
        get({
            description = "获取首页配文"
            response {
                statuses<String>(HttpStatus.OK)
            }
        }) { getMessage() }

        put({
            description = "修改首页配文"
            request {
                body<Message>
                {
                    required = true
                    description = "新的首页配文"
                    example("example", Message("新的首页配文"))
                }
            }
            response {
                statuses(HttpStatus.OK)
            }
        }) { putMessage() }
    }

    route("/image")
    {
        get({
            description = "获取首页图片"
            response {
                HttpStatus.OK.message to {
                    body<File>()
                    {
                        mediaTypes(ContentType.Application.OctetStream, ContentType.Image.PNG)
                    }
                }
            }
        }) { getImage() }

        put({
            description = "修改首页图片"
            request {
                body<File>
                {
                    required = true
                    description = "新的首页图片"
                    mediaTypes(ContentType.Application.OctetStream, ContentType.Image.PNG)
                }
            }
            response {
                statuses(HttpStatus.OK)
            }
        }) { putImage() }
    }

    route("/announcement")
    {
        get({
            description = "获取公告"
            response {
                statuses<String>(HttpStatus.OK)
            }
        }) { getAnnouncement() }

        put({
            description = "修改公告"
            request {
                body<Message>
                {
                    required = true
                    description = "新的公告"
                    example("example", Message("新的公告"))
                }
            }
            response {
                statuses(HttpStatus.OK)
            }
        }) { putAnnouncement() }
    }
}

private suspend fun Context.searchBlock()
{
    val key = call.parameters["key"] ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val blocks = get<Blocks>().searchBlock(getLoginUser()?.id, key, begin, count)
    call.respond(HttpStatus.OK, blocks)
}

private suspend fun Context.getMonthly()
{
    val (begin, count) = call.getPage()
    val posts = get<Posts>().monthly(getLoginUser()?.toDatabaseUser(), begin, count)
    call.respond(HttpStatus.OK, checkAnonymous(posts))
}

@Serializable
private data class Message(val message: String)

private suspend fun Context.getMessage()
{
    call.respond(HttpStatus.OK, HomeFilesUtils.homeMd)
}

private suspend fun Context.putMessage()
{
    withPermission()
    {
        checkHasGlobalAdmin()
        checkRealName()
    }
    val message = receiveAndCheckBody<Message>()
    HomeFilesUtils.homeMd = message.message
    call.respond(HttpStatus.OK)
}

private suspend fun Context.getImage()
{
    val image = HomeFilesUtils.homePng
    val bytes = ByteArrayOutputStream().use {
        ImageIO.write(image, "png", it)
        it.toByteArray()
    }
    call.respondBytes(bytes, ContentType.Image.PNG, HttpStatusCode.OK)
}

private suspend fun Context.putImage()
{
    withPermission()
    {
        checkHasGlobalAdmin()
        checkRealName()
    }
    val image = call.receiveStream()
    HomeFilesUtils.homePng = withContext(Dispatchers.IO) { ImageIO.read(image) }
    call.respond(HttpStatus.OK)
}

private suspend fun Context.getAnnouncement()
{
    call.respond(HttpStatus.OK, HomeFilesUtils.announcementMd)
}

private suspend fun Context.putAnnouncement()
{
    withPermission()
    {
        checkHasGlobalAdmin()
        checkRealName()
    }
    val message = receiveAndCheckBody<Message>()
    HomeFilesUtils.announcementMd = message.message
    call.respond(HttpStatus.OK)
}