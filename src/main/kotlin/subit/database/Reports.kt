package subit.database

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import subit.dataClasses.*
import subit.database.utils.asSlice
import subit.database.utils.singleOrNull

class Reports: DaoSqlImpl<Reports.ReportsTable>(ReportsTable)
{
    object ReportsTable: IdTable<ReportId>("reports")
    {
        override val id = reportId("id").autoIncrement().entityId()
        val reportBy = reference("user", Users.UsersTable)
        val objectType = enumerationByName("object_type", 16, ReportObject::class).index()
        val objectId = long("object_id").index()
        val reason = text("reason")
        val handledBy = reference("handled_by", Users.UsersTable).nullable().index()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = Report(
        row[ReportsTable.id].value,
        row[ReportsTable.objectType],
        row[ReportsTable.objectId],
        row[ReportsTable.reportBy].value,
        row[ReportsTable.reason]
    )

    suspend fun addReport(objectType: ReportObject, id: Long, user: UserId, reason: String): Unit = query()
    {
        insert {
            it[ReportsTable.objectType] = objectType
            it[objectId] = id
            it[reportBy] = user
            it[ReportsTable.reason] = reason
        }
    }

    suspend fun getReport(id: ReportId): Report? = query()
    {
        ReportsTable.selectAll().where { ReportsTable.id eq id }.singleOrNull()?.let(::deserialize)
    }

    suspend fun handleReport(id: ReportId, user: UserId): Unit = query()
    {
        ReportsTable.update({ ReportsTable.id eq id }) { it[handledBy] = user }
    }

    suspend fun getReports(
        begin: Long,
        count: Int,
        handled: Boolean?
    ):Slice<Report> = query()
    {
        return@query (if (handled == null) ReportsTable.selectAll()
        else ReportsTable.selectAll().where {
            if (handled) (handledBy neq null)
            else (handledBy eq null)
        }).asSlice(begin, count).map(::deserialize)
    }
}