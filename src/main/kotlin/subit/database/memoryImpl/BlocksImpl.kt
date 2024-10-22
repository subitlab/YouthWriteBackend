package subit.database.memoryImpl

import subit.dataClasses.*
import subit.dataClasses.BlockId.Companion.toBlockId
import subit.dataClasses.Slice.Companion.asSlice
import subit.database.Blocks
import subit.router.utils.withPermission
import java.util.*

class BlocksImpl: Blocks
{
    private val map = Collections.synchronizedMap(hashMapOf<BlockId, Block>())
    override suspend fun createBlock(
        name: String,
        description: String,
        parent: BlockId?,
        creator: UserId,
        postingPermission: PermissionLevel,
        commentingPermission: PermissionLevel,
        readingPermission: PermissionLevel,
        anonymousPermission: PermissionLevel
    ): BlockId
    {
        val id = (map.size+1).toBlockId()
        map[id] = Block(
            id = id,
            name = name,
            description = description,
            parent = parent,
            creator = creator,
            posting = postingPermission,
            commenting = commentingPermission,
            reading = readingPermission,
            anonymous = anonymousPermission,
            state = State.NORMAL
        )
        return id
    }

    override suspend fun setPermission(
        block: BlockId,
        posting: PermissionLevel?,
        commenting: PermissionLevel?,
        reading: PermissionLevel?,
        anonymous: PermissionLevel?
    )
    {
        val b = map[block] ?: return
        map[block] = b.copy(
            posting = posting ?: b.posting,
            commenting = commenting ?: b.commenting,
            reading = reading ?: b.reading,
            anonymous = anonymous ?: b.anonymous
        )
    }

    override suspend fun getBlock(block: BlockId): Block? = map[block]
    override suspend fun setState(block: BlockId, state: State)
    {
        val b = map[block] ?: return
        map[block] = b.copy(state = state)
    }

    override suspend fun getChildren(loginUser: UserId?, parent: BlockId?, begin: Long, count: Int): Slice<BlockId> =
        map.values.filter { it.parent == parent }.asSequence().asSlice(begin, count).map { it.id }
    override suspend fun searchBlock(loginUser: UserId?, key: String, begin: Long, count: Int): Slice<BlockId> =
        map.values.filter { it.name.contains(key) }.asSequence().asSlice(begin, count).map { it.id }

    override suspend fun getAllBlocks(
        loginUser: DatabaseUser?,
        editable: Boolean,
        begin: Long,
        count: Int
    ): Slice<Block> = withPermission(loginUser, null)
    {
        map.values.filter { if (editable) canPost(it) else canRead(it) }.asSequence().asSlice(begin, count)
    }
}