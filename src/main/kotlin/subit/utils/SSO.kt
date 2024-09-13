package subit.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import subit.config.systemConfig
import subit.dataClasses.*
import subit.database.Users
import java.net.HttpURLConnection
import java.net.URL

@Suppress("MemberVisibilityCanBePrivate")
object SSO: KoinComponent
{
    val users: Users by inject()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowStructuredMapKeys = true
        encodeDefaults = true
    }

    private fun decodeSsoUser(response: String): SsoUser?
    {
        runCatching {
            return json.decodeFromString<Response<SsoUserFull>>(response).data
        }

        runCatching {
            return json.decodeFromString<Response<SsoUserInfo>>(response).data
        }

        return null
    }

    suspend fun getUser(userId: UserId): SsoUser? = withContext(Dispatchers.IO)
    {
        val url = URL(systemConfig.ssoServer + "/info/${userId.value}")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connect()
        val response =
            runCatching { connection.inputStream.bufferedReader().readText() }.getOrNull() ?: return@withContext null
        connection.disconnect()
        return@withContext decodeSsoUser(response)
    }

    suspend fun getUser(token: String): SsoUserFull? = withContext(Dispatchers.IO)
    {
        val url = URL(systemConfig.ssoServer + "/info/0")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", token)
        connection.requestMethod = "GET"
        connection.connect()
        val response =
            runCatching { connection.inputStream.bufferedReader().readText() }.getOrNull() ?: return@withContext null
        connection.disconnect()
        return@withContext decodeSsoUser(response) as? SsoUserFull
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