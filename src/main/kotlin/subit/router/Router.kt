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
import org.koin.ktor.ext.inject
import subit.config.systemConfig
import subit.dataClasses.PermissionLevel
import subit.database.Prohibits
import subit.database.checkParameters
import subit.router.admin.admin
import subit.router.bannedWords.bannedWords
import subit.router.block.block
import subit.router.comment.comment
import subit.router.files.files
import subit.router.home.home
import subit.router.notice.notice
import subit.router.oauth.oauth
import subit.router.posts.posts
import subit.router.privateChat.privateChat
import subit.router.report.report
import subit.router.tags.tag
import subit.router.teapot.teapot
import subit.router.user.user
import subit.router.utils.finishCall
import subit.router.utils.getLoginUser
import subit.router.wordMarkings.wordMarking
import subit.utils.HttpStatus
import subit.utils.respond
import subit.utils.statuses

fun Application.router() = routing()
{
    val rootPath = this.application.environment.rootPath

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
        val prohibits: Prohibits by inject()
        intercept(ApplicationCallPipeline.Call)
        {
            //检测是否在维护中
            if (systemConfig.systemMaintaining)
            {
                call.respond(HttpStatus.Maintaining)
                finish()
            }

            getLoginUser()?.apply()
            {
                if (this.permission < PermissionLevel.NORMAL || prohibits.isProhibited(this.id))
                    finishCall(HttpStatus.Prohibit)
            }

            // 检查参数是否包含违禁词
            checkParameters()
        }
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
            privateChat()
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