package subit.database

import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.koin.core.component.KoinComponent
import subit.dataClasses.UserId
import subit.plugin.contentNegotiation.dataJson
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class Operations: DaoSqlImpl<Operations.OperationsTable>(OperationsTable), KoinComponent
{
    object OperationsTable: Table("operations")
    {
        val admin = reference("operator", Users.UsersTable).index()
        val operationType = varchar("operation_type", 255)
        val operation = text("operation")
        val time = timestampWithTimeZone("time").defaultExpression(CurrentTimestampWithTimeZone).index()
    }

    /**
     * 操作记录
     *
     * 对于发帖等操作, 因为帖子将永远保存在数据库中, 所以不需要记录操作记录.
     * 需要保存的是删除板块, 删除用户等操作, 这些操作将会对数据库中的数据产生永久性影响, 所以需要记录.
     * 因为只是留作备份, 所以没有查询功能, 需要查询时请直接查询数据库.
     * 现在存储是将对象直接存入, 实现应将类型和对象序列化后存入数据库.
     * 且保证数据易反序列, 或序列化为json等易读格式.
     */
    suspend fun <T> addOperation(admin: UserId, operation: T, type: KType): Unit = query()
    {
        insert {
            it[OperationsTable.admin] = admin
            it[operationType] = type.toString()
            it[OperationsTable.operation] =
                if (type.classifier == null) "" else dataJson.encodeToString(serializer(type), operation)
        }
    }
}

suspend inline fun <reified T> Operations.addOperation(admin: UserId, operation: T) =
    addOperation(admin, operation, typeOf<T>())