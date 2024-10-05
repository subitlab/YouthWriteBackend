package subit.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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

    @Serializable
    enum class AuthorizationStatus
    {
        UNAUTHORIZED,
        AUTHORIZED,
        CANCELED,
    }

    private const val MAX_ACCESS_TOKEN_VALID_TIME = 2592000

    @Serializable
    private data class AccessTokenResponse(
        val accessToken: String,
        val accessTokenExpiresIn: Int,
        val refreshToken: String,
        val refreshTokenExpiresIn: Int,
        val tokenType: String
    )


    suspend fun getUser(accessToken: String): SsoUserFull? = withContext(Dispatchers.IO)
    {
        runCatching { httpClient.get(systemConfig.ssoServer + "/serviceApi/info") { bearerAuth(accessToken) }.body<Response<SsoUserFull>>().data }.getOrNull()
    }

    suspend fun getAccessToken(id: UserId): String? = withContext(Dispatchers.IO)
    {
        runCatching { httpClient.get(systemConfig.ssoServer + "/serviceApi/accessToken?user=${id.value}") { bearerAuth(systemConfig.ssoSecret) }.body<Response<String>>().data }.getOrNull()
    }

    suspend fun getStatus(accessToken: String): AuthorizationStatus? = withContext(Dispatchers.IO)
    {
        runCatching { httpClient.get(systemConfig.ssoServer + "/serviceApi/oauth/status") { bearerAuth(accessToken) }.body<Response<AuthorizationStatus>>().data }.getOrNull()
    }

    suspend fun getAccessToken(code: String): String? = withContext(Dispatchers.IO)
    {
        runCatching {
            httpClient.get(systemConfig.ssoServer + "/serviceApi/oauth/accessToken?time=${MAX_ACCESS_TOKEN_VALID_TIME}")
            {
                bearerAuth(systemConfig.ssoSecret)
                header("Oauth-Code", "Bearer $code")
            }.body<Response<AccessTokenResponse>>().data?.accessToken
        }.getOrNull()
    }

    suspend fun getUserFull(accessToken: String): UserFull?
    {
        val ssoUser = getUser(accessToken) ?: return null
        val dbUser = users.getOrCreateUser(ssoUser.id)
        return UserFull.from(ssoUser, dbUser)
    }

    /**
     * 相比[Users.getOrCreateUser],该方法会验证[userId]在sso中存在, 但前者不论sso中是否存在都会创建用户
     */
    suspend fun getDbUser(userId: UserId): DatabaseUser?
    {
        val ssoUser = getAccessToken(userId) ?: return null
        return users.getOrCreateUser(userId)
    }

    suspend fun getUserAndDbUser(userId: UserId): Pair<SsoUser, DatabaseUser>?
    {
        val ssoUser = getAccessToken(userId)?.let { getUser(it) } ?: return null
        val dbUser = users.getOrCreateUser(userId)
        return ssoUser to dbUser
    }
}