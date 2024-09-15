package subit.database.sqlImpl.utils

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.rowNumber
import subit.dataClasses.Slice

fun Query.asSlice(begin: Long, limit: Int): Slice<ResultRow> = runCatching()
{
    val totalSize = Count(TheAny).over().alias("_Slice_TotalSize_")
    val rowNumber = rowNumber().over().orderBy(*this.orderByExpressions.toTypedArray()).alias("_Slice_RowNumber_")
    val isData = booleanParam(true).alias("_Slice_IsData_")

    val query = this.copy()
    query.set = Slice(
        this.set.source,
        this.set.fields + rowNumber + totalSize
    )
    (query.orderByExpressions as MutableList).clear()
    val q = query.alias("_Slice_Query_")

    val q1 = q.aliasOnly()
        .select(query.set.fields + isData, listOf(TheAny, isData))
        .andWhere { rowNumber.aliasOnlyExpression() greaterEq longParam(begin + 1) }
        .andWhere { rowNumber.aliasOnlyExpression() lessEq longParam(begin + limit) }
    val q2 = q.aliasOnly().select(this.set.fields.map { Null } + Null + totalSize + booleanParam(false))

    val resQ = q1.union(q2)
    val list = WithQuery(q, resQ)
        .apply { prepareSQL(QueryBuilder(false)).let(Slice.logger::config) }
        .toList()

    val resCount = list.first()[totalSize]
    val resList = list.filter { it[isData] }
    return Slice(resCount, begin, resList)
}.getOrElse { it.printStackTrace(); Slice.empty() }

fun Query.single() = asSlice(0, 1).list[0]
fun Query.singleOrNull() = asSlice(0, 1).list.firstOrNull()

private object TheAny: Expression<Any>()
{
    override fun toQueryBuilder(queryBuilder: QueryBuilder)
    {
        queryBuilder.append("*")
    }
}

private object Null: Expression<Any?>()
{
    override fun toQueryBuilder(queryBuilder: QueryBuilder)
    {
        queryBuilder.append("NULL")
    }
}

/**
 * 选择[columns]列, 但在实际生成的sql中使用[realColumns]列
 */
private fun ColumnSet.select(columns: List<Expression<*>>, realColumns: List<Expression<*>>): Query = object: Query(
    Slice(this@select, columns),
    null
)
{
    override val queryToExecute: Query
        get() = Query(Slice(this@select, columns), this.where)
            .also { this.copyTo(it) }
            .adjustSelect { select(realColumns) }

    override fun prepareSQL(builder: QueryBuilder): String = queryToExecute.prepareSQL(builder)
    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = prepareSQL(QueryBuilder(prepared))
    override fun copy(): Query = this@select
        .select(columns, realColumns)
        .also {
            this.copyTo(it)
            val where = this.where ?: return@also
            it.where { where }
        }
}

private class WithQuery(private val with: QueryAlias, val query: AbstractQuery<*>): Query(
    Slice(
        with,
        query.set.fields
    ),
    null
)
{
    override fun prepareSQL(builder: QueryBuilder): String
    {
        builder {
            append("WITH ")
            append(with.alias)
            append(" AS (")
            with.query.prepareSQL(this)
            append(") ")
            query.prepareSQL(this)
        }
        return builder.toString()
    }
}

private fun QueryAlias.aliasOnly() = object: Table(this.alias)
{}