package subit.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.config.systemConfig
import subit.dataClasses.*
import subit.database.Users
import subit.plugin.contentNegotiation.contentNegotiationJson

@Suppress("MemberVisibilityCanBePrivate")
object SSO: KoinComponent
{
    val users: Users by inject()
    private val httpClient = HttpClient(Java)
    {
        engine()
        {
            pipelining = true
            protocolVersion = java.net.http.HttpClient.Version.HTTP_2
        }
        install(ContentNegotiation)
        {
            json(contentNegotiationJson)
        }
    }

    suspend fun getUser(userId: UserId): SsoUser? = withContext(Dispatchers.IO)
    {
        runCatching { httpClient.get (systemConfig.ssoServer + "/info/${userId.value}").body<Response<SsoUser>>().data }.getOrNull()
    }

    suspend fun getUser(token: String): SsoUserFull? = withContext(Dispatchers.IO)
    {
        runCatching { httpClient.get(systemConfig.ssoServer + "/info/0") { bearerAuth(token) }.body<Response<SsoUserFull>>().data }.getOrNull()
    }

    suspend fun getUserFull(token: String): UserFull?
    {
        val ssoUser = getUser(token) ?: return null
        val dbUser = users.getOrCreateUser(ssoUser.id)
        return UserFull.from(ssoUser, dbUser)
    }

    /**
     * 相比[Users.getOrCreateUser],该方法会验证[userId]在sso中存在, 但前者不论sso中是否存在都会创建用户
     */
    suspend fun getDbUser(userId: UserId): DatabaseUser?
    {
        val ssoUser = getUser(userId) ?: return null
        return users.getOrCreateUser(ssoUser.id)
    }

    suspend fun getUserAndDbUser(userId: UserId): Pair<SsoUser, DatabaseUser>?
    {
        val ssoUser = getUser(userId) ?: return null
        val dbUser = users.getOrCreateUser(userId)
        return ssoUser to dbUser
    }
}