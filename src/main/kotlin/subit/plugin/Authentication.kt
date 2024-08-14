package subit.plugin

import io.ktor.server.application.*
import io.ktor.server.auth.*
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
        authenticate { this.request.headers["Authorization"]?.let { SSO.getUserFull(it) } }
    }
}