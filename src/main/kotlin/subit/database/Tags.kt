package subit.database

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.Slice
import subit.database.TagRelations.TagRelationsTable.post
import subit.database.Tags.TagsTable.id
import subit.database.utils.asSlice

class Tags: DaoSqlImpl<Tags.TagsTable>(TagsTable), KoinComponent
{
    private val tagRelations: TagRelations by inject()

    object TagsTable: IdTable<TagId>("tags") {
        override val id = tagId("id").autoIncrement().entityId()
        val name = varchar("name", 100).index()
        val description = text("description").default("")
        val type = enumerationByName<TagType>("type", 20).index().default(TagType.NORMAL)
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = Tag(
        id = row[table.id].value,
        name = row[table.name],
        description = row[table.description],
        type = row[table.type],
    )

    private fun tableWithRelations() = Join(table).join(tagRelations.table, JoinType.LEFT, id, tagRelations.table.tag)

    suspend fun hasTag(name: String, type: TagType): Boolean = query()
    {
        table
            .select(id)
            .where{ (TagsTable.name eq name) and (TagsTable.type eq type) }
            .count() > 0
    }

    suspend fun getPostTags(pid: PostId): List<Tag> = query()
    {
        tableWithRelations()
            .selectAll()
            .where { post eq pid }
            .map { deserialize(it) }
    }

    suspend fun addTag(name: String, type: TagType, description: String = ""): Unit = query()
    {
        table.insert {
            it[TagsTable.name] = name
            it[TagsTable.type] = type
            it[TagsTable.description] = description
        }
    }

    suspend fun removeTag(name: String, type: TagType): Boolean = query()
    {
        table.deleteWhere { (TagsTable.name eq name) and (TagsTable.type eq type) } > 0
    }

    /*
     * 根据名称和类型修改标签
     */
    suspend fun editTag(name: String, type: TagType, description: String): Boolean = query()
    {
        table.update ({ (TagsTable.name eq name) and (TagsTable.type eq type) } ) {
            it[TagsTable.description] = description
        } > 0
    }

    suspend fun getTagById(id: TagId): Tag? = query()
    {
        table
            .selectAll()
            .where { TagsTable.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun searchTags(key: String, type: TagType, begin: Long, count: Int): Slice<Tag> = query()
    {
        tableWithRelations()
            .selectAll()
            .where { TagsTable.type eq type }
            .andWhere { name like "%${key}%" }
            .asSlice(begin, count)
            .map { deserialize(it) }
    }

    suspend fun getAllTags(type: TagType, begin: Long, count: Int): Slice<Tag> = query()
    {
        table
            .selectAll()
            .where { TagsTable.type eq type }
            .orderBy(name)
            .asSlice(begin, count)
            .map { deserialize(it) }
    }
}