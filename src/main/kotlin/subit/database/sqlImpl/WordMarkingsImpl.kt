package subit.database.sqlImpl

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.koin.core.component.KoinComponent
import subit.dataClasses.*
import subit.database.WordMarkings

class WordMarkingsImpl: DaoSqlImpl<WordMarkingsImpl.WordMarkingsTable>(WordMarkingsTable), WordMarkings, KoinComponent
{
    object WordMarkingsTable: IdTable<WordMarkingId>("word_markings")
    {
        override val id = wordMarkingId("id").autoIncrement().entityId()
        val postVersion = reference("post_version", PostVersionsImpl.PostVersionsTable)
        val comment = reference("comment", PostsImpl.PostsTable)
        val start = integer("start")
        val end = integer("end")
        val state = enumerationByName<WordMarkingState>("state", 20).default(WordMarkingState.NORMAL)
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = WordMarkingInfo(
        id = row[WordMarkingsTable.id].value,
        postVersion = row[WordMarkingsTable.postVersion].value,
        comment = row[WordMarkingsTable.comment].value,
        start = row[WordMarkingsTable.start],
        end = row[WordMarkingsTable.end],
        state = row[WordMarkingsTable.state]
    )

    override suspend fun getWordMarking(wid: WordMarkingId): WordMarkingInfo? = query()
    {
        selectAll().where { id eq wid }.singleOrNull()?.let { deserialize(it) }
    }

    override suspend fun getWordMarking(postVersion: PostVersionId, comment: PostId): WordMarkingInfo? = query()
    {
        selectAll()
            .andWhere { WordMarkingsTable.postVersion eq postVersion }
            .andWhere { WordMarkingsTable.comment eq comment }
            .singleOrNull()
            ?.let { deserialize(it) }
    }

    override suspend fun addWordMarking(
        postVersion: PostVersionId,
        comment: PostId,
        start: Int,
        end: Int,
        state: WordMarkingState
    ): WordMarkingId = query()
    {
        insertAndGetId {
            it[this.postVersion] = postVersion
            it[this.comment] = comment
            it[this.start] = start
            it[this.end] = end
            it[this.state] = state
        }.value
    }

    override suspend fun batchAddWordMarking(wordMarkings: List<WordMarkingInfo>): List<WordMarkingId> = query()
    {
        batchInsert(wordMarkings)
        {
            this[postVersion] = it.postVersion
            this[comment] = it.comment
            this[start] = it.start
            this[end] = it.end
            this[state] = it.state
        }.map { it[id].value }
    }

    override suspend fun getWordMarkings(postVersion: PostVersionId): List<WordMarkingInfo> = query()
    {
        selectAll().where { WordMarkingsTable.postVersion eq postVersion }.map(::deserialize)
    }
}