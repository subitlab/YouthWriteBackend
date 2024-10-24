@file:Suppress("PackageDirectoryMismatch")

package subit.router.teapot

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import subit.Loader
import subit.utils.HttpStatus.Companion.ImATeapot

fun Route.teapot() = route("/teapot", { hidden = true })
{
    val rootPath = this.application.rootPath
    get("")
    {
        val html =
            Loader.getResource("teapot.html")!!
                .readAllBytes()
                .decodeToString()
                .replace("\${image}", "$rootPath/teapot/image/light")
        call.respondBytes(html.toByteArray(), ContentType.Text.Html, HttpStatusCode.ImATeapot)
    }

    route("/image")
    {
        get("/light")
        {
            val image = Loader.getResource("logo/Teapot-light.png")!!.readAllBytes()
            call.respondBytes(image, ContentType.Image.PNG, HttpStatusCode.ImATeapot)
        }

        get("/dark")
        {
            val image = Loader.getResource("logo/Teapot-dark.png")!!.readAllBytes()
            call.respondBytes(image, ContentType.Image.PNG, HttpStatusCode.ImATeapot)
        }
    }
}