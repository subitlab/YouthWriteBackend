package subit.router

import cn.org.subit.route.terminal.terminal
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import subit.config.systemConfig
import subit.router.admin.admin
import subit.router.bannedWords.bannedWords
import subit.router.block.block
import subit.router.comment.comment
import subit.router.files.files
import subit.router.home.home
import subit.router.notice.notice
import subit.router.oauth.oauth
import subit.router.posts.posts
import subit.router.privateChatWs.privateChatWs
import subit.router.report.report
import subit.router.tags.tag
import subit.router.teapot.teapot
import subit.router.user.user
import subit.router.utils.checkParameters
import subit.router.utils.finishCall
import subit.router.utils.getLoginUser
import subit.router.utils.withPermission
import subit.router.wordMarkings.wordMarking
import subit.utils.HttpStatus
import subit.utils.statuses

fun Application.router() = routing()
{
    val rootPath = this.application.rootPath

    get("/", { hidden = true })
    {
        call.respondRedirect("$rootPath/api-docs")
    }

    authenticate("auth-api-docs")
    {
        route("/api-docs")
        {
            route("/api.json")
            {
                openApiSpec()
            }
            swaggerUI("$rootPath/api-docs/api.json")
        }
    }

    authenticate("auth", optional = true)
    {
        install(createRouteScopedPlugin("ProhibitPlugin", { })
        {
            onCall {
                //检测是否在维护中
                if (systemConfig.systemMaintaining)
                {
                    finishCall(HttpStatus.Maintaining)
                }

                val loginUser = it.getLoginUser()

                withPermission(loginUser?.toDatabaseUser(), loginUser?.toSsoUser())
                {
                    if (isProhibit()) finishCall(HttpStatus.Prohibit)
                }

                // 检查参数是否包含违禁词
                it.checkParameters()
            }
        })

        route("", {
            response {
                statuses(HttpStatus.Maintaining, HttpStatus.Prohibit, HttpStatus.LoginSuccessButNotAuthorized)
            }
        })
        {
            admin()
            bannedWords()
            block()
            comment()
            files()
            home()
            notice()
            posts()
            privateChatWs()
            report()
            tag()
            teapot()
            terminal()
            user()
            wordMarking()
        }
    }
    oauth()
}