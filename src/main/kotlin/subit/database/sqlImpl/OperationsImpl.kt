package subit.database.sqlImpl

import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.koin.core.component.KoinComponent
import subit.dataClasses.UserId
import subit.database.Operations
import subit.plugin.contentNegotiation.dataJson
import kotlin.reflect.KType

class OperationsImpl: DaoSqlImpl<OperationsImpl.OperationsTable>(OperationsTable), Operations, KoinComponent
{
    object OperationsTable: Table("operations")
    {
        val admin = reference("operator", UsersImpl.UsersTable).index()
        val operationType = varchar("operation_type", 255)
        val operation = text("operation")
        val time = timestampWithTimeZone("time").defaultExpression(CurrentTimestampWithTimeZone).index()
    }

    override suspend fun <T> addOperation(admin: UserId, operation: T, type: KType): Unit = query()
    {
        insert {
            it[OperationsTable.admin] = admin
            it[operationType] = type.toString()
            it[OperationsTable.operation] =
                if (type.classifier == null) "" else dataJson.encodeToString(serializer(type), operation)
        }
    }
}