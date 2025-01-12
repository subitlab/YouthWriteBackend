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

    override suspend fun changeInfo(
        block: BlockId,
        name: String?,
        description: String?,
        parent: BlockId?,
        posting: PermissionLevel?,
        commenting: PermissionLevel?,
        reading: PermissionLevel?,
        anonymous: PermissionLevel?
    )
    {
        val b = map[block] ?: return
        map[block] = b.copy(
            name = name ?: b.name,
            description = description ?: b.description,
            parent = parent ?: b.parent,
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

    override suspend fun getChildren(
        loginUser: UserFull?,
        parent: BlockId?,
        begin: Long,
        count: Int
    ): Slice<Block> = loginUser.withPermission()
    {
        map.values
            .filter { it.parent == parent }
            .filter { canRead(it) }
            .asSequence()
            .asSlice(begin, count)
    }

    override suspend fun getBlocks(
        loginUser: UserFull?,
        editable: Boolean,
        key: String?,
        begin: Long,
        count: Int
    ): Slice<Block> = loginUser.withPermission()
    {
        map.values
            .filter { if (editable) canPost(it) else canRead(it) }
            .asSequence()
            .filter { key == null || it.name.contains(key) }
            .asSlice(begin, count)
    }
}