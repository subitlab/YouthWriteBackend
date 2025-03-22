package subit.database

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import subit.dataClasses.Slice
import subit.database.utils.asSlice

class BannedWords: DaoSqlImpl<BannedWords.BannedWordsTable>(BannedWordsTable)
{
    object BannedWordsTable: IdTable<String>("banned_words")
    {
        val word = varchar("word", 255).entityId()
        override val id = word
        override val primaryKey = PrimaryKey(word)
    }

    suspend fun addBannedWord(word: String): Unit = query()
    {
        insert { it[this.word] = word }
    }
    suspend fun removeBannedWord(word: String): Unit = query()
    {
        deleteWhere { table.word eq word }
    }
    suspend fun updateBannedWord(oldWord: String, newWord: String): Unit = query()
    {
        update({ table.word eq oldWord }) { it[word] = newWord }
    }
    suspend fun getBannedWords(begin: Long, count: Int): Slice<String> = query()
    {
        selectAll().asSlice(begin,count).map { it[word].value }
    }
    /**
     * 检查字符串是否包含违禁词汇
     * @param str 待检查字符串
     * @return 是否包含违禁词汇, true为包含
     */
    suspend fun check(str: String): Boolean = query()
    {
        //可以优化查询方式
        selectAll().any { row -> str.contains(row[word].value) }
    }
}