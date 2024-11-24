package subit.utils

import kotlinx.datetime.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.dataClasses.PrivateChat
import subit.dataClasses.UserId
import subit.database.PrivateChats
import java.util.*

object PrivateChatUtil: KoinComponent
{
    private val privateChats: PrivateChats by inject()
    private val clients = Collections.synchronizedMap(mutableMapOf<UserId, MutableList<PrivateChatClient>>())
    private val lock: Locks<UserId> = Locks()

    private suspend fun getClients(user: UserId): List<PrivateChatClient> = lock.withLock(user)
    {
        (clients[user] ?: mutableListOf()).toList()
    }

    private suspend fun addClient(user: UserId, client: PrivateChatClient)
    {
        lock.withLock(user)
        {
            clients.getOrPut(user) { mutableListOf() }.add(client)
        }
    }

    private suspend fun removeClient(user: UserId, client: PrivateChatClient)
    {
        lock.withLock(user)
        {
            val l = clients[user] ?: return
            l.remove(client)
            if (l.isEmpty()) clients.remove(user)
        }
    }

    internal suspend inline fun client(user: UserId, block: PrivateChatClient.()->Unit)
    {
        val client = PrivateChatClient(user)
        addClient(user, client)
        try
        {
            client.block()
        }
        finally
        {
            removeClient(user, client)
        }
    }

    private suspend fun send(from: UserId, to: UserId, message: String)
    {
        if (privateChats.getIsBlock(from, to)) return
        val msg = privateChats.addPrivateChat(from, to, message)
        getClients(to).forEach { it.onReceive.invoke(msg) }
        getClients(from).forEach { it.onSend.invoke(msg) }
    }

    private suspend fun block(from: UserId, to: UserId, block: Boolean)
    {
        privateChats.setIsBlock(from, to, block)
        val isBlocked = privateChats.getIsBlock(to, from)
        getClients(from).forEach { it.onBlockChange.invoke(to, block, isBlocked) }
        getClients(to).forEach { it.onBlockChange.invoke(from, isBlocked, block) }
    }

    private suspend fun read(from: UserId, to: UserId)
    {
        privateChats.setRead(from, to)
        val count = privateChats.getUnreadCount(from)
        getClients(from).forEach { it.onUnreadCountChange.invoke(to, 0, count) }
    }

    private suspend fun readAll(from: UserId)
    {
        privateChats.setReadAll(from)
        getClients(from).forEach { it.onUnreadCountChange.invoke(from, 0, 0) }
    }

    class PrivateChatClient(private val user: UserId)
    {
        var onReceive: suspend (message: PrivateChat)->Unit = {}
        var onSend: suspend (message: PrivateChat)->Unit = {}
        var onUnreadCountChange: suspend (user: UserId, count: Long, totalCount: Long)->Unit = { _, _, _ -> }
        var onBlockChange: suspend (user: UserId, block: Boolean, isBlocked: Boolean)->Unit = { _, _, _ -> }

        suspend fun send(user: UserId, message: String) = send(this.user, user, message)
        suspend fun block(user: UserId, isBlock: Boolean) = block(this.user, user, isBlock)
        suspend fun read(user: UserId) = read(this.user, user)
        suspend fun readAll() = readAll(this.user)
        suspend fun loadMore(user: UserId, time: Instant, count: Int) =
            privateChats.getPrivateChatsBefore(this.user, user, time, 0, count)

        fun onReceive(block: suspend (message: PrivateChat)->Unit)
        {
            onReceive = block
        }

        fun onUnreadCountChange(block: suspend (user: UserId, count: Long, totalCount: Long)->Unit)
        {
            onUnreadCountChange = block
        }

        /**
         * block: 是否拉黑对方, isBlocked: 对方是否拉黑自己
         */
        fun onBlockChange(block: suspend (user: UserId, block: Boolean, isBlocked: Boolean)->Unit)
        {
            onBlockChange = block
        }

        fun onSend(block: suspend (message: PrivateChat)->Unit)
        {
            onSend = block
        }
    }
}