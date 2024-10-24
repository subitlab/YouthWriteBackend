package subit.router.utils

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import org.koin.core.component.get
import subit.database.BannedWords
import subit.utils.HttpStatus
import subit.utils.getKoin

suspend fun checkBannedWords(str: String)
{
    val bannedWords = getKoin().get<BannedWords>()
    if (bannedWords.check(str))
    {
        finishCall(HttpStatus.ContainsBannedWords)
    }
}
suspend fun ApplicationCall.checkParameters()
{
    val parameterValues = this.parameters.toMap().values.flatten()
    // 全部拼接在一起, 一次查询搞定
    val str = parameterValues.joinToString("/") { it }
    checkBannedWords(str)
}

// 接受body并检查是否包含违禁词汇
suspend inline fun <reified T : Any> ApplicationCall.receiveAndCheckBody(): T
{
    val body = this.receive<T>()
    checkBannedWords(body.toString())
    return body
}