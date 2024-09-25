package subit.plugin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.utils.HttpStatus
import subit.utils.respond
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface RateLimit
{
    val rawRateLimitName: String
    val limit: Int
    val duration: Duration
    val rateLimitName: RateLimitName
        get() = RateLimitName(rawRateLimitName)
    suspend fun customResponse(call: ApplicationCall, duration: Duration)
    suspend fun getKey(call: ApplicationCall): Any

    /**
     * 一个与任何对象(包括自己)都不相等的对象, 用于在getKey中返回一个不相等的对象
     */
    @Suppress("EqualsOrHashCode")
    private object NotEqual
    {
        override fun toString(): String = "NotEqual"
        override fun equals(other: Any?): Boolean = false
    }

    companion object
    {
        val list = listOf(Search, Post, AddView)
    }

    data object Search: RateLimit
    {
        override val rawRateLimitName = "search"
        override val limit = 60
        override val duration = 15.seconds
        override suspend fun customResponse(call: ApplicationCall, duration: Duration)
        {
            call.respond(HttpStatus.TooManyRequests.subStatus(message = "搜索操作过于频繁, 请${duration}后再试"))
        }

        override suspend fun getKey(call: ApplicationCall): Any
        {
            val auth = call.request.headers["Authorization"]
            if (auth != null) return auth
            return call.request.local.remoteHost
        }
    }

    data object Post: RateLimit
    {
        override val rawRateLimitName = "post"
        override val limit = 1
        override val duration = 10.seconds
        override suspend fun customResponse(call: ApplicationCall, duration: Duration)
        {
            call.respond(HttpStatus.TooManyRequests.subStatus(message = "发布操作过于频繁, 请${duration}后再试"))
        }

        /**
         * 如果登陆了就按照登陆的用户来限制, 否则的话发帖会因未登陆而返回401(就没必要在这里限制了),
         * 所以这里用随机UUID这样相当于在这里没有限制
         */
        override suspend fun getKey(call: ApplicationCall): Any =
            call.parameters["Authorization"] ?: NotEqual
    }

    data object AddView: RateLimit
    {
        override val rawRateLimitName = "addView"
        override val limit = 1
        override val duration = 5.minutes
        override suspend fun customResponse(call: ApplicationCall, duration: Duration)
        {
            call.respond(HttpStatus.TooManyRequests.subStatus(message = "添加浏览量过于频繁, 请${duration}后再试"))
        }

        override suspend fun getKey(call: ApplicationCall): Any
        {
            val auth = call.request.headers["Authorization"] ?: return NotEqual
            val postId = call.parameters["id"]?.toPostIdOrNull() ?: return NotEqual
            return auth to postId
        }
    }
}

/**
 * 安装速率限制插件, 该插件可以限制请求的速率, 防止恶意请求
 */
fun Application.installRateLimit() = install(io.ktor.server.plugins.ratelimit.RateLimit)
{
    RateLimit.list.forEach()
    { rateLimit ->
        register(rateLimit.rateLimitName)
        {
            rateLimiter(limit = rateLimit.limit, refillPeriod = rateLimit.duration)
            requestKey { rateLimit.getKey(call = it) }
            modifyResponse { call, state ->
                call.response.headers.appendIfAbsent("X-RateLimit-Type", rateLimit.rawRateLimitName, false)
                when (state)
                {
                    is RateLimiter.State.Available ->
                    {
                        call.response.headers.appendIfAbsent("X-RateLimit-Limit", state.limit.toString())
                        call.response.headers.appendIfAbsent("X-RateLimit-Remaining", state.remainingTokens.toString())
                        call.response.headers.appendIfAbsent(
                            "X-RateLimit-Reset",
                            (state.refillAtTimeMillis / 1000).toString()
                        )
                    }

                    is RateLimiter.State.Exhausted ->
                    {
                        call.response.headers.appendIfAbsent(HttpHeaders.RetryAfter, state.toWait.inWholeSeconds.toString())
                    }
                }
            }
        }
    }

    global {
        rateLimiter(limit = 150, refillPeriod = 1.seconds)
        requestKey { call -> call.request.local.remoteHost }
    }
}