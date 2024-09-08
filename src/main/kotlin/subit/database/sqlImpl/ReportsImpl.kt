package subit.database.sqlImpl

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import subit.dataClasses.*
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.Slice.Companion.singleOrNull
import subit.database.Reports

class ReportsImpl: DaoSqlImpl<ReportsImpl.ReportsTable>(ReportsTable), Reports
{
    object ReportsTable: IdTable<ReportId>("reports")
    {
        override val id = reportId("id").autoIncrement().entityId()
        val reportBy = reference("user", UsersImpl.UsersTable)
        val objectType = enumerationByName("object_type", 16, ReportObject::class).index()
        val objectId = long("object_id").index()
        val reason = text("reason")
        val handledBy = reference("handled_by", UsersImpl.UsersTable).nullable().index()
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = Report(
        row[ReportsTable.id].value,
        row[ReportsTable.objectType],
        row[ReportsTable.objectId],
        row[ReportsTable.reportBy].value,
        row[ReportsTable.reason]
    )

    override suspend fun addReport(objectType: ReportObject, id: Long, user: UserId, reason: String): Unit = query()
    {
        insert {
            it[ReportsTable.objectType] = objectType
            it[objectId] = id
            it[reportBy] = user
            it[ReportsTable.reason] = reason
        }
    }

    override suspend fun getReport(id: ReportId): Report? = query()
    {
        ReportsTable.selectAll().where { ReportsTable.id eq id }.singleOrNull()?.let(::deserialize)
    }

    override suspend fun handleReport(id: ReportId, user: UserId): Unit = query()
    {
        ReportsTable.update({ ReportsTable.id eq id }) { it[handledBy] = user }
    }

    override suspend fun getReports(
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