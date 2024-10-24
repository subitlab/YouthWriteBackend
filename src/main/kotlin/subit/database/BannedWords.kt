package subit.database

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import subit.dataClasses.Slice
import subit.router.utils.Context
import subit.router.utils.get
import subit.utils.HttpStatus
import subit.utils.respond

interface BannedWords
{
    suspend fun addBannedWord(word: String)
    suspend fun removeBannedWord(word: String)
    suspend fun updateBannedWord(oldWord: String, newWord: String)
    suspend fun getBannedWords(begin: Long, count: Int): Slice<String>

    /**
     * 检查字符串是否包含违禁词汇
     * @param str 待检查字符串
     * @return 是否包含违禁词汇, true为包含
     */
    suspend fun check(str: String): Boolean
}