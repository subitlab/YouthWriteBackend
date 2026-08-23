package subit.database

import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.koin.core.component.KoinComponent
import subit.dataClasses.PostId
import subit.dataClasses.TagId

class TagRelations: DaoSqlImpl<TagRelations.TagRelationsTable>(TagRelationsTable), KoinComponent
{

    object TagRelationsTable: CompositeIdTable("tag_relations")
    {
        val post = reference("post", Posts.PostTable).index()
        val tag = reference("tag", Tags.TagsTable, ReferenceOption.CASCADE).index()
        override val primaryKey = PrimaryKey(post, tag)

        init {
            addIdColumn(post)
            addIdColumn(tag)
        }
    }

    suspend fun removePostTag(pid: PostId, tag: TagId): Boolean = query()
    {
        table.deleteWhere { (post eq pid) and (TagRelationsTable.tag eq tag) } > 0
    }

    suspend fun addPostTag(pid: PostId, tag: TagId): Boolean = query()
    {
        table.insertIgnoreAndGetId {
            it[post] = pid
            it[TagRelationsTable.tag] = tag
        } != null
    }

}