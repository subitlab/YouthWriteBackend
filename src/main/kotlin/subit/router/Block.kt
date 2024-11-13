@file:Suppress("PackageDirectoryMismatch")

package subit.router.block

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.BlockId.Companion.toBlockIdOrNull
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.*
import subit.router.utils.*
import subit.utils.HttpStatus
import subit.utils.SSO
import subit.utils.respond
import subit.utils.statuses

fun Route.block() = route("/block", {
    tags = listOf("板块")
})
{
    post("/new", {
        description = "创建板块, 创建根板块需要全局管理员权限, 其他情况需要在父板块中有管理员权限."
        request {
            body<NewBlock>
            {
                required = true
                description = "新板块信息, parent为null表示创建根板块."
                example(
                    "example", NewBlock(
                        "板块名称",
                        "板块描述",
                        BlockId(1),
                        PermissionLevel.ADMIN,
                        PermissionLevel.ADMIN,
                        PermissionLevel.ADMIN,
                        PermissionLevel.ADMIN
                    )
                )
            }
        }
        response {
            statuses<WarpBlockId>(HttpStatus.OK, example = WarpBlockId(BlockId(0)))
            statuses(HttpStatus.Forbidden, HttpStatus.Unauthorized)
        }
    }) { newBlock() }

    put("/{id}", {
        description = "修改板块信息"
        request {
            pathParameter<BlockId>("id")
            {
                required = true
                description = "板块ID"
            }
            body<EditBlockInfo>
            {
                required = true
                description = "新板块信息"
                example("example", EditBlockInfo(name = "板块名称", description = "板块描述"))
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Forbidden, HttpStatus.Unauthorized)
        }
    }) { editBlockInfo() }

    post("/changePermission", {
        description = "修改用户在板块的权限"
        request {
            body<ChangePermission>
            {
                required = true
                description = "新权限"
                example("example", ChangePermission(UserId(0), BlockId(0), PermissionLevel.ADMIN))
            }
        }
        response {
            statuses(HttpStatus.OK, HttpStatus.Forbidden, HttpStatus.Unauthorized)
        }
    }) { changePermission() }

    get("/all", {
        description = "获得所有板块, 只包含当前用户有权限看的板块"
        request {
            paged()
            queryParameter<Boolean>("editable")
            {
                description = "是否只获取当前用户有权限编辑的板块, 不填视为false"
                required = false
            }
            queryParameter<String>("key")
            {
                description = "要求包含某一关键字"
                required = false
            }
        }
        response {
            statuses<Slice<Block>>(HttpStatus.OK, example = sliceOf(Block.example))
        }
    }) { getAllBlocks() }

    get("/all/tree", {
        description = "以树形结构获得所有当前用户可见的板块"
        request {
            queryParameter<Boolean>("editable")
            {
                description = "是否只获取当前用户有权限编辑的板块, 不填视为false"
                required = false
            }
        }
        response {
            statuses<Set<BlockTree>>(HttpStatus.OK, example = setOf(BlockTree.leafExample, BlockTree.example))
        }
    }) { getBlockTree() }

    route("/{id}", {
        request {
            pathParameter<BlockId>("id")
            {
                required = true
                description = "板块ID"
            }
        }
    })
    {
        get("", {
            description = "获取板块信息"
            response {
                statuses<Block>(HttpStatus.OK)
                statuses(HttpStatus.Forbidden, HttpStatus.Unauthorized)
            }
        }) { getBlockInfo() }

        delete("", {
            description = "删除板块"
            response {
                statuses(HttpStatus.OK, HttpStatus.Forbidden, HttpStatus.Unauthorized)
            }
        }) { deleteBlock() }

        get("/children", {
            description = "获取板块的子板块, 若id为0则表示获取没有父板块的板块"
            request {
                paged()
            }
            response {
                statuses<Slice<Block>>(HttpStatus.OK, example = sliceOf(Block.example))
                statuses(HttpStatus.Forbidden, HttpStatus.Unauthorized)
            }
        }) { getChildren() }

        get("/permission/{user}", {
            description = "获取用户在板块的权限"
            request {
                pathParameter<UserId>("user")
                {
                    required = true
                    description = "用户ID, 0表示当前用户"
                }
            }
            response {
                statuses<PermissionLevel>(HttpStatus.OK)
                statuses(HttpStatus.Forbidden, HttpStatus.Unauthorized)
            }
        }) { getPermission() }
    }
}

@Serializable
private data class WarpBlockId(val block: BlockId)

@Serializable
private data class NewBlock(
    val name: String,
    val description: String,
    val parent: BlockId?,
    val postingPermission: PermissionLevel,
    val commentingPermission: PermissionLevel,
    val readingPermission: PermissionLevel,
    val anonymousPermission: PermissionLevel,
)

private suspend fun Context.newBlock()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val newBlock = call.receiveAndCheckBody<NewBlock>()
    val blocks = get<Blocks>()
    if (newBlock.parent != null)
    {
        checkPermission { checkHasAdminIn(newBlock.parent) }
        blocks.getBlock(newBlock.parent) ?: return call.respond(HttpStatus.BadRequest)
    }
    else checkPermission { checkHasGlobalAdmin() }
    val id = blocks.createBlock(
        name = newBlock.name,
        description = newBlock.description,
        parent = newBlock.parent,
        creator = loginUser.id,
        postingPermission = newBlock.postingPermission,
        commentingPermission = newBlock.commentingPermission,
        readingPermission = newBlock.readingPermission,
        anonymousPermission = newBlock.anonymousPermission
    )
    get<Operations>().addOperation(loginUser.id, newBlock)
    call.respond(HttpStatus.OK, WarpBlockId(id))
}

@Serializable
private data class EditBlockInfo(
    val name: String? = null,
    val description: String? = null,
    val parent: BlockId? = null,
    val postingPermission: PermissionLevel? = null,
    val commentingPermission: PermissionLevel? = null,
    val readingPermission: PermissionLevel? = null,
    val anonymousPermission: PermissionLevel? = null,
)

private suspend fun Context.editBlockInfo()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val id = call.parameters["id"]?.toBlockIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val editBlockInfo = call.receiveAndCheckBody<EditBlockInfo>()
    val block = get<Blocks>().getBlock(id) ?: return call.respond(HttpStatus.NotFound)
    val parent = editBlockInfo.parent?.let { get<Blocks>().getBlock(it) }
    checkPermission {
        checkRead(block)
        checkHasAdminIn(id)
    }
    if (parent != null && editBlockInfo.readingPermission != null && editBlockInfo.readingPermission < parent.reading)
        finishCall(HttpStatus.BadRequest.subStatus("读取权限不能低于父板块"))

    val blocks = get<Blocks>()
    blocks.changeInfo(
        block = id,
        name = editBlockInfo.name,
        description = editBlockInfo.description,
        parent = editBlockInfo.parent,
        posting = editBlockInfo.postingPermission,
        commenting = editBlockInfo.commentingPermission,
        reading = editBlockInfo.readingPermission,
        anonymous = editBlockInfo.anonymousPermission
    )
    get<Operations>().addOperation(loginUser.id, editBlockInfo)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.getBlockInfo()
{
    val id = call.parameters["id"]?.toBlockIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val block = get<Blocks>().getBlock(id) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkRead(block) }
    call.respond(HttpStatus.OK, block)
}

private suspend fun Context.deleteBlock()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val id = call.parameters["id"]?.toBlockIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val blocks = get<Blocks>()
    val block = blocks.getBlock(id) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkChangeState(block, State.DELETED) }
    blocks.setState(id, State.DELETED)
    get<Operations>().addOperation(loginUser.id, id)
    if (loginUser.id != block.creator) get<Notices>().createNotice(
        Notice.SystemNotice(
            user = block.creator,
            content = "您的板块 ${block.name} 已被删除"
        )
    )
    call.respond(HttpStatus.OK)
}

@Serializable
private data class ChangePermission(
    val user: UserId,
    val block: BlockId,
    val permission: PermissionLevel
)

private suspend fun Context.changePermission()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changePermission = call.receiveAndCheckBody<ChangePermission>()
    val block = get<Blocks>().getBlock(changePermission.block) ?: return call.respond(HttpStatus.NotFound)
    val user = SSO.getDbUser(changePermission.user) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkChangePermission(block, user, changePermission.permission) }
    get<Permissions>().setPermission(
        bid = changePermission.block,
        uid = changePermission.user,
        permission = changePermission.permission
    )
    get<Operations>().addOperation(loginUser.id, changePermission)
    get<Notices>().createNotice(
        Notice.SystemNotice(
            user = changePermission.user,
            content = "您在板块 ${block.name} 的权限已被修改"
        )
    )
    call.respond(HttpStatus.OK)
}

private suspend fun Context.getPermission()
{
    getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val bid = call.parameters["id"]?.toBlockIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val uid = call.parameters["user"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val blocks = get<Blocks>()
    checkPermission()
    {
        checkRead(blocks.getBlock(bid) ?: return call.respond(HttpStatus.NotFound))
        if (uid != UserId(0)) checkHasAdminIn(bid)
    }
    val user =
        if (uid == UserId(0)) getLoginUser()?.toDatabaseUser()
        else SSO.getDbUser(uid)
    call.respond(HttpStatus.OK, checkPermission(user) { getPermission(bid) })
}

private suspend fun Context.getChildren()
{
    val id1 = call.parameters["id"]?.toBlockIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val id = if (id1 == BlockId(0)) null else id1
    val (begin, count) = call.getPage()
    val blocks = get<Blocks>()

    checkPermission()
    {
        val block = id?.let { blocks.getBlock(it) }
        if (block != null) checkRead(block)
    }

    blocks.getChildren(getLoginUser(), id, begin, count).let { call.respond(HttpStatus.OK, it) }
}

private suspend fun Context.getAllBlocks()
{
    val (begin, count) = call.getPage()
    val editable = call.parameters["editable"].toBoolean()
    val key = call.parameters["key"]
    val res = get<Blocks>().getBlocks(getLoginUser(), editable, key, begin, count)
    finishCall(HttpStatus.OK, res)
}

@Serializable
data class BlockTree(val info: Block, val children: Set<BlockTree>)
{
    companion object
    {
        val leafExample = BlockTree(Block.example, setOf())
        val example = BlockTree(Block.example, setOf(leafExample))
    }
}

private fun MutableSet<Block>.toBlockTree(root: Block? = null): Set<BlockTree>
{
    return this
        .filter { it.parent == root?.id }
        .also { this.removeAll(it.toSet()) }
        .map { BlockTree(it, this.toBlockTree(it)) }
        .toSet()
}

private suspend fun Context.getBlockTree()
{
    val editable = call.parameters["editable"].toBoolean()
    val allBlocks = get<Blocks>().getBlocks(getLoginUser(), editable, null, 0, Int.MAX_VALUE).list.toMutableSet()
    call.respond(HttpStatus.OK, allBlocks.toBlockTree())
}