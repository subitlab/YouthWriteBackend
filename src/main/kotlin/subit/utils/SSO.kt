package subit.utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.config.systemConfig
import subit.dataClasses.*
import subit.database.Users
import subit.logger.YouthWriteLogger
import subit.plugin.contentNegotiation.contentNegotiationJson

@Suppress("MemberVisibilityCanBePrivate")
object SSO: KoinComponent
{
    val users: Users by inject()
    private val logger by YouthWriteLogger
    private val httpClient = HttpClient(Java)
    {
        engine()
        {
            pipelining = true
            dispatcher = Dispatchers.IO
            protocolVersion = java.net.http.HttpClient.Version.HTTP_2
        }
        install(ContentNegotiation)
        {
            json(contentNegotiationJson)
        }
    }

    @Serializable
    @Suppress("unused")
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

    @Serializable
    private data class Information<User>(
        val user: User,
        val service: BasicServiceInfo,
    )

    @Serializable
    data class BasicServiceInfo(
        val id: Int,
        val name: String,
        val description: String,
    )

    suspend fun getUser(accessToken: String): SsoUserFull? = withContext(Dispatchers.IO)
    {
        runCatching {
            val url = systemConfig.ssoServer + "/serviceApi/info"
            logger.finer("url: $url")
            logger.finer("  accessToken: $accessToken")
            val data = httpClient.get(url)
            {
                bearerAuth(accessToken)
            }.body<Response<Information<SsoUserFull>>>().data
            if (data?.service?.id != systemConfig.ssoServerId) null else data.user
        }.getOrElse { logger.fine("error in sso", it); null }
    }

    suspend fun getAccessToken(id: UserId): String? = withContext(Dispatchers.IO)
    {
        runCatching {
            val url = systemConfig.ssoServer + "/serviceApi/accessToken"
            logger.finer("url: $url")
            logger.finer("  user: $id")
            logger.finer("  time: $MAX_ACCESS_TOKEN_VALID_TIME")
            httpClient.get(url)
            {
                bearerAuth(systemConfig.ssoSecret)
                parameter("user", id)
                parameter("time", MAX_ACCESS_TOKEN_VALID_TIME)
            }.body<Response<AccessTokenResponse>>().data?.accessToken
        }.getOrElse { logger.fine("error in sso", it); null }
    }

    suspend fun getStatus(accessToken: String): AuthorizationStatus? = withContext(Dispatchers.IO)
    {
        runCatching {
            val url = systemConfig.ssoServer + "/serviceApi/oauth/status"
            logger.finer("url: $url")
            logger.finer("  accessToken: $accessToken")
            httpClient.get(url)
            {
                bearerAuth(accessToken)
            }.body<Response<AuthorizationStatus>>().data
        }.getOrElse { logger.fine("error in sso", it); null }
    }

    suspend fun getAccessToken(code: String): String? = withContext(Dispatchers.IO)
    {
        runCatching {
            val url = systemConfig.ssoServer + "/serviceApi/oauth/accessToken"
            logger.finer("url: $url")
            logger.finer("  code: $code")
            logger.finer("  time: $MAX_ACCESS_TOKEN_VALID_TIME")
            httpClient.get(url)
            {
                bearerAuth(systemConfig.ssoSecret)
                header("Oauth-Code", "Bearer $code")
                parameter("time", MAX_ACCESS_TOKEN_VALID_TIME)
            }.body<Response<AccessTokenResponse>>().data?.accessToken
        }.getOrElse { logger.fine("error in sso", it); null }
    }

    suspend fun hasUser(id: UserId) = getAccessToken(id) != null

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
        @Suppress("UNUSED_VARIABLE")
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