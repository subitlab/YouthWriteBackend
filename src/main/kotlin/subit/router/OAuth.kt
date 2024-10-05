@file:Suppress("PackageDirectoryMismatch")

package subit.router.oauth

import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.router.utils.finishCall
import subit.utils.HttpStatus
import subit.utils.SSO
import subit.utils.statuses

fun Route.oauth() = route("oauth", {
    tags("OAuth")
})
{
    post("/login", {
        description = "通过sso登录"
        request {
            body<Login>()
            {
                description = "登录信息, code为oauth授权码"
                required = true
            }
        }
        response {
            statuses<String>(HttpStatus.OK, HttpStatus.LoginSuccessButNotAuthorized, example = "token")
        }
    })
    {
        val login = call.receive<Login>()
        val accessToken = SSO.getAccessToken(login.code) ?: finishCall(HttpStatus.InvalidOAuthCode)
        val status = SSO.getStatus(accessToken) ?: finishCall(HttpStatus.InvalidOAuthCode)
        if (status != SSO.AuthorizationStatus.AUTHORIZED) finishCall(HttpStatus.LoginSuccessButNotAuthorized, accessToken)
        finishCall(HttpStatus.OK, accessToken)
    }
}

@Serializable private data class Login(val code: String)