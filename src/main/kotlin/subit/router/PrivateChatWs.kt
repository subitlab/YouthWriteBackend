@file:Suppress("PackageDirectoryMismatch")

package subit.router.privateChatWs

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import subit.dataClasses.PrivateChat
import subit.dataClasses.UserId
import subit.logger.YouthWriteLogger
import subit.router.utils.example
import subit.router.utils.finishCall
import subit.router.utils.getLoginUser
import subit.utils.HttpStatus
import subit.utils.PrivateChatUtil

fun Route.privateChatWs() = route("/privateChatWs", {
    tags = listOf("私信")
    description = "私信的WebSocket实现"
    request()
    {
        body<PrivateChatPacket.Receive>()
        {
            description = """
                websocket 数据包
                
                数据包类型包括: ${PrivateChatPacket.Receive.Type.entries.joinToString { it.name }}
                - Message: 表示发送一条新的私信, to表示接收方, content表示内容
                - Read: 表示标记一条私信为已读, from表示发送方, 如果为null表示标记所有私信为已读
                - Block: 表示屏蔽某人, userId表示对方的id
                - Unblock: 表示取消屏蔽某人, userId表示对方的id
            """.trimIndent()
            example("发送一条新的私信", PrivateChatPacket.Receive.messageExample)
            example("标记一条私信为已读", PrivateChatPacket.Receive.readExample)
            example("标记所有私信为已读", PrivateChatPacket.Receive.readAllExample)
            example("屏蔽某人", PrivateChatPacket.Receive.blockExample)
            example("取消屏蔽某人", PrivateChatPacket.Receive.unblockExample)
        }
    }
    response()
    {
        HttpStatusCode.OK to {
            description = """
                websocket 数据包
                
                数据包类型包括: ${PrivateChatPacket.Send.Type.entries.joinToString { it.name }}
                - Message: 表示有新的私信, 包括发送和接收, send为true表示发送, false表示接收
                - UnreadCount: 表示未读私信数量变化, user和count分别表示用户id和对该用户的未读数量, totalCount表示总数量
                - Block: 表示被屏蔽状态变化, user表示用户id, block表示是否屏蔽对方, isBlocked表示是否被对方屏蔽
            """.trimIndent()
            body<PrivateChatPacket.Send>()
            {
                example("发送/接收到新的私信", PrivateChatPacket.Send.messageExample)
                example("未读私信数量变化", PrivateChatPacket.Send.unreadCountExample)
                example("被屏蔽状态变化", PrivateChatPacket.Send.blockExample)
            }
        }
    }
})
{
    privateChatWsImpl()
    get { finishCall(HttpStatus.BadRequest.subStatus("请使用WebSocket连接")) }
}

private val logger = YouthWriteLogger.getLogger()

private sealed interface PrivateChatPacket<T: PrivateChatPacket.PacketType>
{
    sealed interface PacketType
    val type: T

    @Serializable
    sealed class Receive(override val type: Type): PrivateChatPacket<Receive.Type>
    {
        @Serializable
        enum class Type: PacketType
        {
            MESSAGE, READ, READ_ALL, BLOCK, UNBLOCK
        }
        @Serializable
        @SerialName("MESSAGE")
        data class Message(val to: UserId, val content: String): Receive(Type.MESSAGE)
        @Serializable
        @SerialName("READ")
        data class Read(val from: UserId?): Receive(Type.READ)
        @Serializable
        @SerialName("BLOCK")
        data class Block(val userId: UserId): Receive(Type.BLOCK)
        @Serializable
        @SerialName("UNBLOCK")
        data class Unblock(val userId: UserId): Receive(Type.UNBLOCK)

        companion object
        {
            val messageExample = Message(UserId(1), "hello")
            val readExample = Read(UserId(1))
            val readAllExample = Read(null)
            val blockExample = Block(UserId(1))
            val unblockExample = Unblock(UserId(1))
        }
    }

    @Serializable
    sealed class Send(override val type: Type): PrivateChatPacket<Send.Type>
    {
        @Serializable
        enum class Type: PacketType
        {
            MESSAGE, UNREAD_COUNT, BLOCK
        }
        @Serializable
        @SerialName("MESSAGE")
        data class Message(val message: PrivateChat): Send(Type.MESSAGE)
        @Serializable
        @SerialName("UNREAD_COUNT")
        data class UnreadCount(val user: UserId, val count: Long, val totalCount: Long): Send(Type.UNREAD_COUNT)
        @Serializable
        @SerialName("BLOCK")
        data class Block(val user: UserId, val block: Boolean, val isBlocked: Boolean): Send(Type.BLOCK)

        companion object
        {
            val messageExample = Message(PrivateChat.example)
            val unreadCountExample = UnreadCount(UserId(1), 1, 2)
            val blockExample = Block(UserId(1), true, false)
        }
    }
}

private fun Route.privateChatWsImpl() = webSocket()
{
    val loginUser = call.getLoginUser()?.id ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "未登录"))

    PrivateChatUtil.client(loginUser)
    {
        onSend {
            sendSerialized(PrivateChatPacket.Send.Message(it))
        }
        onReceive {
            sendSerialized(PrivateChatPacket.Send.Message(it))
        }
        onUnreadCountChange { user, count, totalCount ->
            sendSerialized(PrivateChatPacket.Send.UnreadCount(user, count, totalCount))
        }
        onBlockChange { user, block, isBlocked ->
            sendSerialized(PrivateChatPacket.Send.Block(user, block, isBlocked))
        }

        while (true)
        {
            try
            {
                val packet = receiveDeserialized<PrivateChatPacket.Receive>()
                when (packet)
                {
                    is PrivateChatPacket.Receive.Message -> send(packet.to, packet.content)
                    is PrivateChatPacket.Receive.Read -> packet.from?.let { read(it) } ?: readAll()
                    is PrivateChatPacket.Receive.Block -> block(packet.userId, true)
                    is PrivateChatPacket.Receive.Unblock -> block(packet.userId, false)
                }
            }
            catch (_: ClosedReceiveChannelException)
            {
                break
            }
            catch (e: Throwable)
            {
                logger.warning("Error in private chat websocket", e)
            }
        }
    }
}