@file:Suppress("PackageDirectoryMismatch")

package subit.router.home

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.database.Blocks
import subit.database.Posts
import subit.database.receiveAndCheckBody
import subit.plugin.RateLimit
import subit.router.*
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses

fun Route.home() = route("/home", {
    tags = listOf("首页")
})
{
    rateLimit(RateLimit.Search.rateLimitName)
    {
        get("/recommend", {
            description = "获取首页推荐帖子"
            request {
                queryParameter<Int>("count")
                {
                    required = false
                    description = "获取数量, 不填为10"
                    example(10)
                }
            }
            response {
                statuses<Slice<PostId>>(HttpStatus.OK, example = sliceOf(PostId(0)))
                statuses(HttpStatus.NotFound)
            }
        }) { getHotPosts() }

        route("/search", {
            request {
                authenticated(false)
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

            get("/post", {
                description = "搜索帖子, 会返回所有符合条件的帖子"
                request {
                    queryParameter<Boolean>("openAdvancedSearch")
                    {
                        required = false
                        description = "是否开启高级搜索, 默认为否"
                        example(false)
                    }
                    body<AdvancedSearchData>{
                        required = false
                        description = """
                            高级搜索条件 不需要的选项可不传,不传时选项为默认值
                            需要openAdvancedSearch为true时传入
                            blockIdList: 指定在哪些板块及其子版块中搜索, 默认为不限制
                            isOnlyTitle: 是否只在标题中搜索关键字, 默认为否
                            authorIdList: 指定在哪些用户为作者的帖子中搜索, 默认为不限制
                            lastModifiedAfter: 指定帖子最后修改时间需在哪个时间点之后, 传入时间戳, 默认为不限制
                            createTime: 指定帖子创建时间需在哪个时间段内, 传入时间戳组, 默认为不限制
                        """.trimIndent()
                        example("example", AdvancedSearchData(
                            blockIdList = listOf(BlockId(0)),
                            authorIdList = listOf(UserId(0)),
                            isOnlyTitle = false,
                            lastModifiedAfter = System.currentTimeMillis(),
                            createTime = Pair(System.currentTimeMillis(),System.currentTimeMillis())
                            )
                        )
                    }
                }
                response {
                    statuses<Slice<PostId>>(HttpStatus.OK, example = sliceOf(PostId(0)))
                }
            }) { searchPost() }
        }
    }
}

private suspend fun Context.getHotPosts()
{
    val posts = get<Posts>()
    val count = call.parameters["count"]?.toIntOrNull() ?: 10
    val result = posts.getRecommendPosts(getLoginUser()?.id, count)
    call.respond(HttpStatusCode.OK, result)
}

private suspend fun Context.searchBlock()
{
    val key = call.parameters["key"] ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val blocks = get<Blocks>().searchBlock(getLoginUser()?.id, key, begin, count)
    call.respond(HttpStatus.OK, blocks)
}

@Serializable
data class AdvancedSearchData(
    val blockIdList: List<BlockId>? = null,
    val authorIdList: List<UserId>? = null,
    val isOnlyTitle: Boolean? = null,
    val lastModifiedAfter: Long? = null,
    val createTime: Pair<Long, Long>? = null,
    val isOnlyPost: Boolean? = null
)

private suspend fun Context.searchPost()
{
    val key = call.parameters["key"] ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val openAdvancedSearch = call.parameters["openAdvancedSearch"].toBoolean()
    val advancedSearchData =
        if(openAdvancedSearch) receiveAndCheckBody<AdvancedSearchData>()
        else AdvancedSearchData()
    val posts = get<Posts>().searchPosts(getLoginUser()?.toDatabaseUser(), key, advancedSearchData, begin, count)
    call.respond(HttpStatus.OK, posts)
}