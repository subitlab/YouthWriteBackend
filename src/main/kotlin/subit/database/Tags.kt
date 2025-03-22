package subit.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.database.utils.asSlice

class Tags: DaoSqlImpl<Tags.TagsTable>(TagsTable)
{
    object TagsTable: Table("tags")
    {
        val post = reference("post", Posts.PostsTable).index()
        val tag = varchar("tag", 100).index()
    }

    suspend fun getPostTags(pid: PostId): List<String> = query()
    {
        table.select(tag).where { post eq pid }.map { it[tag] }
    }

    suspend fun removePostTag(pid: PostId, tag: String): Boolean = query()
    {
        table.deleteWhere { (post eq pid) and (TagsTable.tag eq tag) } > 0
    }

    suspend fun addPostTag(pid: PostId, tag: String): Boolean = query()
    {
        if (selectAll().where { (post eq pid) and (TagsTable.tag eq tag) }.count() > 0)
            return@query false
        table.insert {
            it[post] = pid
            it[TagsTable.tag] = tag
        }
        true
    }

    suspend fun searchTags(key: String, begin: Long, count: Int): Slice<String> = query()
    {
        table.select(tag).where { tag like "%${key}%" }.asSlice(begin, count).map { it[tag] }
    }

    suspend fun getAllTags(begin: Long, count: Int): Slice<String> = query()
    {
        table.selectAll().withDistinct(true).orderBy(tag).asSlice(begin, count).map { it[tag] }
    }
}