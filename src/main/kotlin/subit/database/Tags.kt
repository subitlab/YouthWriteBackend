package subit.database

import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.Statement
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.Slice
import subit.database.TagRelations.TagRelationsTable.post
import subit.database.Tags.TagsTable.id
import subit.database.utils.asSlice
import java.sql.ResultSet

class Tags: DaoSqlImpl<Tags.TagsTable>(TagsTable), KoinComponent
{
    private val tagRelations: TagRelations by inject()

    object TagsTable: IdTable<TagId>("tags") {
        override val id = tagId("id").autoIncrement().entityId()
        val name = varchar("name", 100).index()
        val description = text("description").default("")
        val type = enumerationByName<TagType>("type", 20).index().default(TagType.NORMAL)
        val parent = reference("parent", TagsTable, ReferenceOption.CASCADE).index().nullable()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = Tag(
        id = row[table.id].value,
        name = row[table.name],
        description = row[table.description],
        type = row[table.type],
        parent = row[table.parent]?.value
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

    suspend fun removeTagById(id: TagId): Boolean = query()
    {
        table.deleteWhere { TagsTable.id eq id } > 0
    }

    /*
    传null为不修改
     */
    suspend fun editTag(id: TagId, name: String? = null, description: String? = null): Boolean = query()
    {
        table.update ({ TagsTable.id eq id }) {
            if(name != null) it[TagsTable.name] = name
            if(description != null) it[TagsTable.description] = description
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

    /**
     * 连续的空白字符(包括空格和换行)的匹配正则表达式
     */
    private val whiteSpaceRegex = Regex("\\s+")

    /**
     * 一个查询, 可以获得以[rootId]为根的子树内的所有tag的id, 也会包括[rootId]自己
     */
    private inner class GetDescendantIdsQuery(
        val rootId: TagId
    ): Query(
        org.jetbrains.exposed.sql.Slice(TagsTable, listOf(id)),
        null
    )
    {
        @Language("SQL")
        val sql = """
            WITH RECURSIVE SubTree AS (
                SELECT id, parent
                FROM tags
                WHERE id = ${rootId.value}
                UNION ALL
                SELECT n.id, n.parent
                FROM tags n
                INNER JOIN SubTree subTree ON n.parent = subTree.id
            )
            SELECT SubTree.id AS id
            FROM SubTree
        """.trimIndent().replace(whiteSpaceRegex, " ")

        override val queryToExecute: Statement<ResultSet>
            get() = this

        override fun prepareSQL(builder: QueryBuilder): String
        {
            builder.append(sql)
            return builder.toString()
        }
    }

    suspend fun getTagsList(
        type: TagType,
        key: String?,
        childOf: TagId?,
        descendantOf: TagId?,
        begin: Long,
        count: Int,
    ): Slice<Tag> = query()
    {
        val descendantIds = descendantOf?.let { GetDescendantIdsQuery(it).alias("descendantIds") }

        Join(table)
            .let {
                if (descendantIds != null) it.join(descendantIds, JoinType.INNER,
                    id, descendantIds[id])
                else it
            }
            .select(table.columns)
            .where { table.type eq type }
            .apply { if (key != null) this.andWhere { table.name like "%$key%" } }
            .apply {
                if (childOf != null)
                    if (childOf == TagId(0)) this.andWhere { table.parent.isNull() }
                    else this.andWhere { table.parent eq childOf }
            }
            .orderBy(name)
            .asSlice(begin, count)
            .map { deserialize(it) }
    }

    suspend fun setTagFolder(tagId: TagId, folderId: TagId?): Boolean = query()
    {
        table.update({ id eq tagId }) {
            it[parent] = folderId
        } > 0
    }
}