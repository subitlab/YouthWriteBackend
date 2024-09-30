@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.terminal

import cn.org.subit.route.terminal.Type.*
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import subit.Loader
import subit.config.loggerConfig
import subit.console.command.CommandSet
import subit.dataClasses.PermissionLevel
import subit.dataClasses.UserFull
import subit.logger.ForumLogger
import subit.logger.ToConsoleHandler
import java.util.logging.Handler
import java.util.logging.LogRecord

private val logger = ForumLogger.getLogger()

private val loggerFlow = MutableSharedFlow<Packet<String>>(
    replay = 100,
    extraBufferCapacity = 100,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
private val sharedFlow = loggerFlow.asSharedFlow()

private val init: Unit = run {
    ForumLogger.globalLogger.logger.addHandler(object : Handler()
    {
        init
        {
            this.formatter = ToConsoleHandler.formatter
        }
        override fun publish(record: LogRecord)
        {
            if (!loggerConfig.check(record)) return
            if (record.loggerName.startsWith("io.ktor.websocket")) return
            loggerFlow.tryEmit(Packet(MESSAGE, formatter.format(record)))
        }
        override fun flush() = Unit
        override fun close() = Unit
    })
}

fun Route.terminal() = route("/terminal", {
    hidden = true
})
{
    init
    webSocket("/api")
    {
        val loginUser = call.principal<UserFull>()
        if (loginUser == null || loginUser.permission != PermissionLevel.ROOT)
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Permission denied"))

        val job = launch { sharedFlow.collect(::sendSerialized) }

        class WebSocketCommandSender(user: UserFull): CommandSet.CommandSender
        {
            override val name: String = "WebSocket('${user.username}' (id: ${user.id}))"
            override suspend fun out(line: String) = sendSerialized(Packet(MESSAGE,parseLine(line, false)))
            override suspend fun err(line: String) = sendSerialized(Packet(MESSAGE,parseLine(line, true)))
            override suspend fun clear() = sendSerialized(Packet(CLEAR, null))
        }

        val sender = WebSocketCommandSender(loginUser)

        runCatching {
            while (true)
            {
                val packet = receiveDeserialized<Packet<String>>()
                when (packet.type)
                {
                    COMMAND -> CommandSet.invokeCommand(sender, packet.data)
                    TAB -> sendSerialized(Packet(TAB, CommandSet.invokeTabComplete(packet.data)))
                    MESSAGE, CLEAR -> Unit
                }
            }
        }.onFailure { exception ->
            logger.info("WebSocket exception: ${exception.localizedMessage}")
        }.also {
            job.cancel()
        }
    }

    get("")
    {
        val html = Loader.getResource("terminal.html")!!.readAllBytes().decodeToString().replace("\${root}", application.environment.rootPath)
        call.respondBytes(html.toByteArray(), ContentType.Text.Html, HttpStatusCode.OK)
    }

    get("/icon")
    {
        call.respondBytes(Loader.getResource("logo/SubIT-icon.png")!!.readAllBytes(), ContentType.Image.PNG, HttpStatusCode.OK)
    }
}

@Serializable
private enum class Type
{
    // request
    COMMAND,
    // request & response
    TAB,
    // response
    MESSAGE,
    // response
    CLEAR,
}

@Serializable
private data class Packet<T>(val type: Type, val data: T)