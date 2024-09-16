package subit.router.utils

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.reflect.*
import subit.utils.HttpStatus
import subit.utils.respond

class CallFinish(val block: suspend ApplicationCall.() -> Unit): RuntimeException()
{
    companion object
}
fun CallFinish(httpStatus: HttpStatus) = CallFinish { respond(httpStatus) }
inline fun <reified T>CallFinish(httpStatus: HttpStatus, body: T) = CallFinish { respond(httpStatus.code, body, typeInfo<T>()) }

fun finishCall(httpStatus: HttpStatus): Nothing = throw CallFinish(httpStatus)
inline fun <reified T>finishCall(httpStatus: HttpStatus, body: T): Nothing = throw CallFinish(httpStatus, body)