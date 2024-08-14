package subit

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.util.pipeline.*
import org.koin.core.component.KoinComponent
import subit.dataClasses.SsoUserFull
import subit.dataClasses.UserFull

/**
 * JWT验证
 */
@Suppress("MemberVisibilityCanBePrivate")
object JWTAuth: KoinComponent
{
    fun PipelineContext<*, ApplicationCall>.getLoginUser(): UserFull? = call.principal<UserFull>()
}
