package subit.database.sqlImpl

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.*
import subit.dataClasses.Slice
import subit.database.Blocks
import subit.database.Permissions
import subit.database.sqlImpl.BlocksImpl.BlocksTable.id
import subit.database.sqlImpl.utils.asSlice
import subit.database.sqlImpl.utils.singleOrNull
import subit.router.utils.PermissionGroup
import subit.router.utils.permissionGroup
import subit.router.utils.withPermission

/**
 * 板块数据库交互类
 */
class BlocksImpl: DaoSqlImpl<BlocksImpl.BlocksTable>(BlocksTable), Blocks, KoinComponent
{
    private val permissions: Permissions by inject()

    object BlocksTable: IdTable<BlockId>("blocks")
    {
        override val id = blockId("id").autoIncrement().entityId()
        val name = varchar("name", 100).index()
        val description = text("description")
        val parent = reference("parent", BlocksTable, ReferenceOption.CASCADE, ReferenceOption.CASCADE).nullable()
            .default(null)
            .index()
        val creator = reference("creator", UsersImpl.UsersTable).index()
        val state = enumerationByName<State>("state", 20).default(State.NORMAL)
        val posting = enumeration<PermissionLevel>("posting").default(PermissionLevel.NORMAL)
        val commenting = enumeration<PermissionLevel>("commenting").default(PermissionLevel.NORMAL)
        val reading = enumeration<PermissionLevel>("reading").default(PermissionLevel.NORMAL)
        val anonymous = enumeration<PermissionLevel>("anonymous").default(PermissionLevel.NORMAL)
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    private fun deserializeBlock(row: ResultRow): Block = Block(
        id = row[BlocksTable.id].value,
        name = row[BlocksTable.name],
        description = row[BlocksTable.description],
        parent = row[BlocksTable.parent]?.value,
        creator = row[BlocksTable.creator].value,
        posting = row[BlocksTable.posting],
        commenting = row[BlocksTable.commenting],
        reading = row[BlocksTable.reading],
        anonymous = row[BlocksTable.anonymous],
        state = row[BlocksTable.state]
    )

    private suspend fun Query.checkPermission(loginUser: UserFull?, permissionGroup: PermissionGroup, editable: Boolean): Query?
    {
        val permissionsTable = (permissions as PermissionsImpl).table
        // 如果全局管理员就没任何限制
        if (permissionGroup.hasGlobalAdmin) return this
        // 如果被封禁或没有实名认证且尝试编辑, 则返回null
        if (editable && (permissionGroup.isProhibit() || !permissionGroup.hasRealName)) return null

        if (loginUser != null)
        {
            groupBy(*(table.columns + permissionsTable.block).toTypedArray())
            if (editable) andHaving { permissionsTable.permission.max() greaterEq table.posting }
            else andHaving { permissionsTable.permission.max() greaterEq table.reading }
        }
        else
        {
            if (editable) andHaving { Op.FALSE }
            else andHaving { table.reading lessEq PermissionLevel.NORMAL }
        }
        andWhere { table.state eq State.NORMAL }
        return this
    }

    private suspend fun Join.joinPermission(loginUser: UserFull?, permissionGroup: PermissionGroup, editable: Boolean): Join?
    {
        val permissionsTable = (permissions as PermissionsImpl).table
        return (
            // 如果是全局管理员, 则不需要权限表
            if (permissionGroup.hasGlobalAdmin) this
            // 如果被封禁或没有实名认证且尝试编辑, 则返回null
            else if (editable && (permissionGroup.isProhibit() || !permissionGroup.hasRealName)) null
            // 如果登录用户不为空, 则尝试连接权限表
            else if (loginUser != null) this.join(permissionsTable, JoinType.LEFT, id, permissionsTable.block) { permissionsTable.user eq loginUser.id }
            else this
        )
    }

    override suspend fun createBlock(
        name: String,
        description: String,
        parent: BlockId?,
        creator: UserId,
        postingPermission: PermissionLevel,
        commentingPermission: PermissionLevel,
        readingPermission: PermissionLevel,
        anonymousPermission: PermissionLevel
    ): BlockId = query()
    {
        insertAndGetId {
            it[BlocksTable.name] = name
            it[BlocksTable.description] = description
            it[BlocksTable.parent] = parent
            it[BlocksTable.creator] = creator
            it[posting] = postingPermission
            it[commenting] = commentingPermission
            it[reading] = readingPermission
            it[anonymous] = anonymousPermission
        }.value
    }

    override suspend fun setPermission(
        block: BlockId,
        posting: PermissionLevel?,
        commenting: PermissionLevel?,
        reading: PermissionLevel?,
        anonymous: PermissionLevel?
    ): Unit = query()
    {
        update({ id eq block })
        {
            if (posting != null) it[BlocksTable.posting] = posting
            if (commenting != null) it[BlocksTable.commenting] = commenting
            if (reading != null) it[BlocksTable.reading] = reading
            if (anonymous != null) it[BlocksTable.anonymous] = anonymous
        }
    }

    override suspend fun getBlock(block: BlockId): Block? = query()
    {
        selectAll().where { id eq block }.singleOrNull()?.let(::deserializeBlock)
    }

    override suspend fun setState(block: BlockId, state: State): Unit = query()
    {
        update({ id eq block })
        {
            it[BlocksTable.state] = state
        }
    }

    override suspend fun getChildren(loginUser: UserFull?, parent: BlockId?, begin: Long, count: Int): Slice<Block> = query()
    {
        val permissionGroup = loginUser.permissionGroup()
        Join(table)
            .joinPermission(loginUser, permissionGroup, editable = false)
            ?.select(BlocksTable.columns)
            ?.where { BlocksTable.parent eq parent }
            ?.checkPermission(loginUser, permissionGroup, editable = false)
            ?.orderBy(id, SortOrder.DESC)
            ?.asSlice(begin, count)
            ?.map(::deserializeBlock)
        ?: Slice.empty()
    }

    override suspend fun getBlocks(
        loginUser: UserFull?,
        editable: Boolean,
        key: String?,
        begin: Long,
        count: Int
    ): Slice<Block> = query()
    {
        val permissionGroup = loginUser.permissionGroup()
        Join(table)
            .joinPermission(loginUser, permissionGroup, editable)
            ?.select(table.columns)
            ?.apply { if (key != null) this.andWhere { table.name like "%$key%" } }
            ?.checkPermission(loginUser, permissionGroup, editable)
            ?.orderBy(table.id, SortOrder.ASC)
            ?.asSlice(begin, count)
            ?.map(::deserializeBlock)
        ?: Slice.empty()
    }
}