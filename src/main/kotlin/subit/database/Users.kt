package subit.database

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import subit.dataClasses.BlockId
import subit.dataClasses.DatabaseUser
import subit.dataClasses.PermissionLevel
import subit.dataClasses.Slice
import subit.dataClasses.UserId
import subit.database.utils.asSlice
import subit.database.utils.single

class Users: DaoSqlImpl<Users.UsersTable>(UsersTable)
{
    /**
     * 用户信息表
     */
    object UsersTable: IdTable<UserId>("users")
    {
        override val id = userId("id").entityId()
        val penName = varchar("pen_name", 32).nullable().default(null).index() // null为未定义，空字符串为被删除
        val introduction = text("introduction").nullable().default(null)
        val showStars = bool("show_stars").default(true)
        val permission = enumeration<PermissionLevel>("permission").default(PermissionLevel.NORMAL)
        val filePermission = enumeration<PermissionLevel>("file_permission").default(PermissionLevel.NORMAL)
        val likeBlocks = array("like_blocks", BlockIdColumnType()).default(emptyList())
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = DatabaseUser(
        id = row[UsersTable.id].value,
        penName = row[UsersTable.penName],
        introduction = row[UsersTable.introduction] ?: "",
        showStars = row[UsersTable.showStars],
        permission = row[UsersTable.permission],
        filePermission = row[UsersTable.filePermission],
        likeBlocks = row[UsersTable.likeBlocks]
    )

    /**
     * 不能修改为null，null表示不修改
     * 若用户不存在返回false
     */
    suspend fun changeInformation(
        id: UserId,
        introduction: String? = null,
        penName: String? = null,
    ): Boolean = query()
    {
        update({ UsersTable.id eq id }) {
            if(introduction != null) it[UsersTable.introduction] = introduction
            if(penName != null) it[UsersTable.penName] = penName
        } > 0
    }

    /**
     * 若用户不存在返回false
     * null为不修改
     */
    suspend fun changeSettings(
        id: UserId,
        showStars: Boolean? = null,
        likeBlocks: List<BlockId>? = null,
    ): Boolean = query()
    {
        update({ UsersTable.id eq id }) {
            if( showStars != null ) it[UsersTable.showStars] = showStars
            if( likeBlocks != null ) it[UsersTable.likeBlocks] = likeBlocks
        } > 0
    }

    /**
     * 若用户不存在返回false
     */
    suspend fun changePermission(id: UserId, permission: PermissionLevel): Boolean = query()
    {
        update({ UsersTable.id eq id }) { it[UsersTable.permission] = permission } > 0
    }

    /**
     * 若用户不存在返回false
     */
    suspend fun changeFilePermission(id: UserId, permission: PermissionLevel): Boolean = query()
    {
        update({ UsersTable.id eq id }) { it[filePermission] = permission } > 0
    }

    suspend fun getOrCreateUser(id: UserId, penName: String? = null): DatabaseUser = query()
    {
        insertIgnore {
            it[UsersTable.id] = id
            it[UsersTable.penName] = penName
        }
        selectAll().where { UsersTable.id eq id }.single().let(::deserialize)
    }

    suspend fun getUser(id: UserId): DatabaseUser? = query()
    {
        selectAll().where { UsersTable.id eq id }.singleOrNull()?.let(::deserialize)
    }

    suspend fun searchUserByPenName(name: String, begin: Long, count: Int): Slice<UserId> = query()
    {
        select(id)
            .where { UsersTable.penName like "%$name%" }
            .asSlice(begin, count)
            .map { it[id].value }
    }
}