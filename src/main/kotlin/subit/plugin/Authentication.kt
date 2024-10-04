@file:Suppress("PackageDirectoryMismatch")

package subit.plugin.authentication

import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.Authentication
import io.ktor.server.request.*
import io.ktor.server.response.*
import subit.config.apiDocsConfig
import subit.utils.SSO

/**
 * 安装登陆验证服务
 */
fun Application.installAuthentication() = install(Authentication)
{
    // 此登陆仅用于api文档的访问, 见ApiDocs插件
    basic("auth-api-docs")
    {
        realm = "Access to the Swagger UI"
        validate()
        {
            if (it.name == apiDocsConfig.name && it.password == apiDocsConfig.password)
                UserIdPrincipal(it.name)
            else null
        }
    }

    bearer("forum-auth")
    {
        authHeader {
            val token = it.request.header(HttpHeaders.Authorization) ?: run {
                val t = parseHeaderValue(it.request.header(HttpHeaders.SecWebSocketProtocol))
                val index = t.indexOfFirst { headerValue -> headerValue.value == "Bearer" }
                if (index == -1) return@authHeader null
                it.response.header(HttpHeaders.SecWebSocketProtocol, "Bearer")
                t.getOrNull(index + 1)?.value?.let { token -> "Bearer $token" }
            }
            token?.let(::parseAuthorizationHeader)
        }
        authenticate {
            val token = it.token
            val user = SSO.getUserFull("Bearer $token")
            return@authenticate user
        }
    }
}