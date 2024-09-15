package subit.database.sqlImpl

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.database.Tags
import subit.database.sqlImpl.utils.asSlice

class TagsImpl: Tags, DaoSqlImpl<TagsImpl.TagsTable>(TagsTable)
{
    object TagsTable: Table("tags")
    {
        val post = reference("post", PostsImpl.PostsTable).index()
        val tag = varchar("tag", 100).index()
    }

    override suspend fun getPostTags(pid: PostId): List<String> = query()
    {
        table.select(tag).where { post eq pid }.map { it[tag] }
    }

    override suspend fun removePostTag(pid: PostId, tag: String): Unit = query()
    {
        table.deleteWhere { (post eq pid) and (TagsTable.tag eq tag) }
    }

    override suspend fun addPostTag(pid: PostId, tag: String): Unit = query()
    {
        table.deleteWhere { (post eq pid) and (TagsTable.tag eq tag) }
        table.insert { it[post] = pid; it[TagsTable.tag] = tag }
    }

    override suspend fun searchTags(key: String, begin: Long, count: Int): Slice<String> = query()
    {
        table.select(tag).where { tag like "%${key}%" }.asSlice(begin, count).map { it[tag] }
    }

    override suspend fun getAllTags(begin: Long, count: Int): Slice<String> = query()
    {
        table.selectAll().withDistinct(true).orderBy(tag).asSlice(begin, count).map { it[tag] }
    }
}