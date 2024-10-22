package subit.dataClasses

import kotlinx.serialization.Serializable

/**
 * 板块信息
 * @property id 板块ID
 * @property name 板块名称
 * @property description 板块描述
 * @property parent 父板块ID
 * @property creator 创建者ID
 * @property posting 发帖权限
 * @property commenting 评论权限
 * @property reading 阅读权限
 */
@Serializable
data class Block(
    val id: BlockId,
    val name: String,
    val description: String,
    val parent: BlockId?,
    val creator: UserId,
    val posting: PermissionLevel,
    val commenting: PermissionLevel,
    val reading: PermissionLevel,
    val anonymous: PermissionLevel,
    val state: State
)
{
    companion object
    {
        val example = Block(
            BlockId(1),
            "板块名称",
            "板块描述",
            null,
            UserId(1),
            PermissionLevel.ADMIN,
            PermissionLevel.ADMIN,
            PermissionLevel.ADMIN,
            PermissionLevel.ADMIN,
            State.NORMAL
        )
    }
}
